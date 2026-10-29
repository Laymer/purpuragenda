package com.purpuragenda.adapters.out.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.adapters.out.security.Secrets;
import com.purpuragenda.application.port.out.CustomerExportStore;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.SchedulingCommands;
import com.purpuragenda.domain.privacy.TemporaryExport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit data projection: no session, credential, token, envelope, throttle or idempotency content. */
public final class PostgresCustomerExports implements CustomerExportStore {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final int MAX_DOCUMENT=16*1024*1024, CHUNK=8192;
    private final JdbcTemplate jdbc;private final PostgresScheduling scheduling;private final Secrets secrets;
    public PostgresCustomerExports(DataSource source,Secrets secrets,Set<String> hosts){jdbc=new JdbcTemplate(Objects.requireNonNull(source));scheduling=new PostgresScheduling(source,hosts);this.secrets=Objects.requireNonNull(secrets);}
    private record Row(UUID id,UUID customer,UUID actor,Instant created,Instant expires,String digest,boolean released,String envelope) { }
    private static Timestamp stamp(Instant value){return Timestamp.from(value);}
    private static Failure missing(){return new Failure(Failure.Kind.NOT_FOUND);}
    private static Failure storage(){return new Failure(Failure.Kind.STORAGE_FAILURE);}
    private <T>T safe(Supplier<T> work){try{return work.get();}catch(Failure failure){throw failure;}catch(RuntimeException failure){throw storage();}}
    @Override public Receipt prepare(SessionBinding session,UUID customer,String key,String reason){
        Objects.requireNonNull(session);Objects.requireNonNull(customer);String normalized=new com.purpuragenda.application.privacy.AdministratorExports.Request(reason).reason();
        String fingerprint=CommandKey.fingerprint(List.of(normalized));
        return safe(()->scheduling.resolved(now->{
            UUID actor=PostgresAdminBooking.authorize(jdbc,session,now);
            if(jdbc.query("SELECT id FROM customer_identity WHERE id=? FOR UPDATE",(r,n)->r.getObject(1,UUID.class),customer).isEmpty())throw missing();
            var command=new CommandKey(new Scope(actor,"PREPARE_EXPORT",customer),key,fingerprint);
            return new PostgresScheduling.Resolved(command,receipt->{
                if(receipt.success()){Row row=row(receipt.entityId(),actor,now);if(!row.customer().equals(customer)||receipt.version()!=1)throw storage();}
                return receipt;
            },tx->SchedulingCommands.within(tx,()->{
                UUID id=UUID.randomUUID();String document=snapshot(customer);String digest=Secrets.hash(document);
                Instant expires=new TemporaryExport(now).expiresAt();String encrypted=encrypt(id,customer,actor,now,expires,digest,document);
                jdbc.update("INSERT INTO privacy_export(id,customer_id,actor_id,created_at,expires_at,digest,encrypted_document) VALUES(?,?,?,?,?,?,?)",id,customer,actor,stamp(now),stamp(expires),digest,encrypted);
                jdbc.update("INSERT INTO privacy_request(id,customer_id,kind,state,reason,created_at) VALUES(?,?,'EXPORT','PREVIEW',?,?)",id,customer,normalized,stamp(now));
                audit(actor,id,customer,"EXPORT_PREPARED",now,normalized);return new Receipt(Code.OK,id,1);
            }));
        }));
    }
    @Override public Receipt release(SessionBinding session,UUID id,String key,Review review){
        Objects.requireNonNull(session);Objects.requireNonNull(id);Objects.requireNonNull(review);
        return safe(()->scheduling.resolved(now->{
            UUID actor=PostgresAdminBooking.authorize(jdbc,session,now);Row row=row(id,actor,now);
            var command=new CommandKey(new Scope(actor,"RELEASE_EXPORT",id),key,review.digest());
            return new PostgresScheduling.Resolved(command,receipt->{if(receipt.success()&&(!row.released()||!id.equals(receipt.entityId())||receipt.version()!=2))throw storage();return receipt;},
                tx->SchedulingCommands.within(tx,()->{
                    if(!row.digest().equals(review.digest()))return Receipt.failure(Code.STALE_VERSION);
                    if(!row.released()){
                        // Check authenticated payload before release, not just the untrusted database digest.
                        decrypt(row);
                        jdbc.update("UPDATE privacy_export SET released_at=? WHERE id=?",stamp(now),id);
                        jdbc.update("UPDATE privacy_request SET state='RELEASED' WHERE id=?",id);
                        audit(actor,id,row.customer(),"EXPORT_REVIEWED",now,"");
                    }
                    return new Receipt(Code.OK,id,2);
                }));
        }));
    }
    @Override public Document read(SessionBinding session,UUID id,boolean download){
        Objects.requireNonNull(session);Objects.requireNonNull(id);
        return safe(()->scheduling.locked(tx->{
            UUID actor=PostgresAdminBooking.authorize(jdbc,session,tx.now());Row row=row(id,actor,tx.now());
            if(download&&!row.released())throw new Failure(Failure.Kind.FORBIDDEN);
            String document=decrypt(row);if(download)audit(actor,id,row.customer(),"EXPORT_DOWNLOADED",tx.now(),"");
            return new Document(id,row.customer(),row.expires(),row.digest(),row.released(),document);
        }));
    }
    private Row row(UUID id,UUID actor,Instant now){
        return jdbc.query("SELECT * FROM privacy_export WHERE id=? AND actor_id=? AND created_at<=? AND expires_at>? FOR UPDATE",(r,n)->new Row(
            r.getObject("id",UUID.class),r.getObject("customer_id",UUID.class),r.getObject("actor_id",UUID.class),r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant(),r.getString("digest"),r.getTimestamp("released_at")!=null,r.getString("encrypted_document")),id,actor,stamp(now),stamp(now)).stream().findFirst().orElseThrow(PostgresCustomerExports::missing);
    }
    private void audit(UUID actor,UUID export,UUID customer,String type,Instant now,String reason){jdbc.update("INSERT INTO audit_event(id,actor_id,actor_kind,type,occurred_at,reason,metadata) VALUES(?,?,'ADMIN',?,?,?,jsonb_build_object('exportId',?::text,'customerId',?::text))",UUID.randomUUID(),actor,type,stamp(now),reason,export.toString(),customer.toString());}
    private String encrypt(UUID id,UUID customer,UUID actor,Instant created,Instant expires,String digest,String document){
        if(document.length()>MAX_DOCUMENT)throw storage();var chunks=new ArrayList<String>();
        for(int start=0;start<document.length();){int end=Math.min(start+CHUNK,document.length());if(end<document.length()&&Character.isHighSurrogate(document.charAt(end-1)))end--;chunks.add(document.substring(start,end));start=end;}
        var parts=JSON.createArrayNode();
        for(String chunk:chunks){
            var frame=JSON.createObjectNode().put("purpose","CUSTOMER_EXPORT_V1").put("export",id.toString()).put("customer",customer.toString()).put("actor",actor.toString())
                .put("index",parts.size()).put("count",chunks.size()).put("digest",digest).put("created",created.toString()).put("expires",expires.toString()).put("data",chunk);
            parts.add(secrets.encrypt(frame.toString()));}
        return parts.toString();
    }
    private String decrypt(Row row){
        try{
            JsonNode parts=JSON.readTree(row.envelope());if(!parts.isArray()||parts.isEmpty()||parts.size()>4096)throw storage();var document=new StringBuilder();int index=0;
            for(JsonNode part:parts){if(!part.isTextual())throw storage();JsonNode frame=JSON.readTree(secrets.decrypt(part.textValue()));
                if(!"CUSTOMER_EXPORT_V1".equals(frame.path("purpose").asText())||!row.id().toString().equals(frame.path("export").asText())||!row.customer().toString().equals(frame.path("customer").asText())||!row.actor().toString().equals(frame.path("actor").asText())||!frame.path("index").isInt()||frame.path("index").intValue()!=index++||!frame.path("data").isTextual()||frame.path("count").asInt(-1)!=parts.size()||!row.digest().equals(frame.path("digest").asText())||!row.created().toString().equals(frame.path("created").asText())||!row.expires().toString().equals(frame.path("expires").asText()))throw storage();
                document.append(frame.path("data").textValue());if(document.length()>MAX_DOCUMENT)throw storage();}
            String result=document.toString();if(!Secrets.hash(result).equals(row.digest()))throw storage();return result;
        }catch(java.io.IOException invalid){throw storage();}
    }
    @Override public int purge(int limit){
        if(limit<1||limit>500)throw new IllegalArgumentException("INVALID_EXPORT_PURGE_LIMIT");
        // Maintenance deletion has its own boundary; no request/read callback invokes it.
        try{return jdbc.update("WITH cutoff AS MATERIALIZED (SELECT clock_timestamp() AS now) DELETE FROM privacy_export WHERE id IN (SELECT e.id FROM privacy_export e CROSS JOIN cutoff WHERE e.expires_at<=cutoff.now OR e.customer_id IS NULL ORDER BY e.id LIMIT ? FOR UPDATE OF e SKIP LOCKED)",limit);}
        catch(RuntimeException failure){throw storage();}
    }
    private String snapshot(UUID customer){
        // One statement gives one PostgreSQL snapshot even when independent account-profile writers run concurrently.
        return jdbc.queryForObject("""
            SELECT jsonb_build_object('format','purpuragenda-customer-export-v1',
              'identity',jsonb_build_object('id',c.id,'email',c.email_key,'verifiedAt',c.verified_at),
              'account',(SELECT jsonb_build_object('enrolledAt',a.enrolled_at) FROM customer_account a WHERE a.customer_id=c.id),
              'profile',(SELECT p.document FROM customer_profile p WHERE p.customer_id=c.id),
              'bookings',COALESCE((SELECT jsonb_agg(jsonb_build_object(
                'id',b.id,'reference',b.reference,'state',b.state,'createdAt',b.created_at,'start',b.start_at,'end',b.session_end,
                'deliveryEmail',b.delivery_email,'snapshot',s.document,
                'effectiveIntake',COALESCE((SELECT m.document->'intake' FROM booking_amendment m WHERE m.booking_id=b.id AND m.document->>'kind'='INTAKE_CORRECTION' ORDER BY (m.document->>'revision')::bigint DESC LIMIT 1),s.document->'intake'),
                'contactChanges',COALESCE((SELECT jsonb_agg(jsonb_build_object('oldEmail',h.old_email,'newEmail',h.new_email,'changedAt',h.changed_at) ORDER BY h.changed_at,h.id) FROM booking_contact_change h WHERE h.booking_id=b.id),'[]'::jsonb),
                'overrides',COALESCE((SELECT jsonb_agg(jsonb_build_object('rules',o.rules,'reason',o.reason,'createdAt',o.created_at) ORDER BY o.created_at,o.id) FROM booking_override o WHERE o.booking_id=b.id),'[]'::jsonb),
                'refundObligations',COALESCE((SELECT jsonb_agg(jsonb_build_object('receiptId',r.receipt_id,'dueMinor',r.due_minor,'reason',r.reason) ORDER BY r.receipt_id) FROM refund_obligation r JOIN payment_entry p ON p.id=r.receipt_id WHERE p.booking_id=b.id),'[]'::jsonb),
                'notes',COALESCE((SELECT jsonb_agg(jsonb_build_object('id',n.id,'text',n.note,'createdAt',n.created_at) ORDER BY n.created_at,n.id) FROM booking_note n WHERE n.booking_id=b.id),'[]'::jsonb),
                'amendments',COALESCE((SELECT jsonb_agg(jsonb_build_object('id',m.id,'document',m.document,'reason',m.reason,'createdAt',m.created_at) ORDER BY m.created_at,m.id) FROM booking_amendment m WHERE m.booking_id=b.id),'[]'::jsonb),
                'payments',COALESCE((SELECT jsonb_agg(jsonb_build_object('id',p.id,'kind',p.kind,'amountMinor',p.amount_minor,'currency',p.currency,'method',p.method,'reference',p.reference,'verifiedAt',p.verified_at,'receiptId',p.receipt_id) ORDER BY p.verified_at,p.id) FROM payment_entry p WHERE p.booking_id=b.id),'[]'::jsonb),
                'audit',COALESCE((SELECT jsonb_agg(jsonb_build_object('type',a.type,'reason',a.reason,'occurredAt',a.occurred_at) ORDER BY a.occurred_at,a.id) FROM audit_event a WHERE a.booking_id=b.id),'[]'::jsonb)
              ) ORDER BY b.created_at,b.id) FROM booking b JOIN booking_snapshot s ON s.booking_id=b.id WHERE b.customer_id=c.id),'[]'::jsonb))::text
            FROM customer_identity c WHERE c.id=?
            """,String.class,customer);
    }
}
