package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.adapters.out.security.Secrets;
import com.purpuragenda.application.port.out.CustomerCorrectionStore;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.SchedulingCommands;
import com.purpuragenda.domain.privacy.TemporaryExport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Only the explicit confirm path applies a correction; previews contain no plaintext at rest. */
public final class PostgresCustomerCorrections implements CustomerCorrectionStore {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;private final PostgresScheduling scheduling;private final Secrets secrets;
    public PostgresCustomerCorrections(DataSource source,Secrets secrets,Set<String> hosts){jdbc=new JdbcTemplate(Objects.requireNonNull(source));scheduling=new PostgresScheduling(source,hosts);this.secrets=Objects.requireNonNull(secrets);}
    private record Payload(String purpose,UUID id,UUID customer,UUID actor,Kind kind,UUID target,String baseline,long version,Map<String,String> values,String reason,Instant created,Instant expires) { @Override public String toString(){return "CorrectionPayload[REDACTED]";} }
    private record Row(UUID id,UUID customer,UUID actor,Kind kind,UUID target,Instant created,Instant expires,String digest,String encrypted,boolean confirmed) { }
    private static Timestamp stamp(Instant value){return Timestamp.from(value);}
    private static Failure missing(){return new Failure(Failure.Kind.NOT_FOUND);}
    private static Failure storage(){return new Failure(Failure.Kind.STORAGE_FAILURE);}
    private static String fingerprint(Change change){return CommandKey.fingerprint(List.of(change.kind().name(),PersistedJson.write(change.values()),change.reason()));}
    private static String hash(Target target){return Secrets.hash(PersistedJson.write(List.of(target.kind(),target.targetId(),target.version(),new TreeMap<>(target.values()))));}
    private static Map<String,String> fields(String document){try{return JSON.readValue(document,new TypeReference<TreeMap<String,String>>(){});}catch(java.io.IOException invalid){throw storage();}}
    @Override public Target target(SessionBinding session,UUID customer,Kind kind,UUID target){
        Objects.requireNonNull(session);Objects.requireNonNull(customer);Objects.requireNonNull(kind);Objects.requireNonNull(target);
        try{return scheduling.locked(tx->{PostgresAdminBooking.authorize(jdbc,session,tx.now());return current(customer,kind,target);});}
        catch(Failure failure){throw failure;}catch(RuntimeException failure){throw storage();}
    }
    private Target current(UUID customer,Kind kind,UUID target){
        if(jdbc.query("SELECT id FROM customer_identity WHERE id=?",(r,n)->r.getObject(1,UUID.class),customer).isEmpty())throw missing();
        long revision=jdbc.queryForObject("SELECT COALESCE(max(revision),1) FROM privacy_correction_fact WHERE kind=? AND target_id=?",Long.class,kind.name(),target);
        if(kind==Kind.PROFILE){
            if(!target.equals(customer))throw missing();
            // Same account-before-profile order as self-service profile writes; resource lock is already held.
            jdbc.query("SELECT id FROM customer_account WHERE customer_id=? FOR UPDATE",(r,n)->r.getObject(1,UUID.class),customer);
            return jdbc.query("SELECT version,document::text FROM customer_profile WHERE customer_id=? FOR UPDATE",(r,n)->{
                var values=fields(r.getString(2));if(values.isEmpty())values=Map.of("firstName","","lastName","","telephone","");return new Target(kind,target,r.getLong(1),values);
            },customer).stream().findFirst().orElseThrow(PostgresCustomerCorrections::missing);
        }
        if(kind==Kind.NOTE)return jdbc.query("SELECT n.note FROM booking_note n JOIN booking b ON b.id=n.booking_id WHERE n.id=? AND b.customer_id=? FOR UPDATE OF n",(r,n)->new Target(kind,target,revision,Map.of("text",r.getString(1))),target,customer).stream().findFirst().orElseThrow(PostgresCustomerCorrections::missing);
        return jdbc.query("SELECT COALESCE((SELECT m.document->'intake' FROM booking_amendment m WHERE m.booking_id=b.id AND m.document->>'kind'='INTAKE_CORRECTION' ORDER BY (m.document->>'revision')::bigint DESC LIMIT 1),s.document->'intake')::text FROM booking b JOIN booking_snapshot s ON s.booking_id=b.id WHERE b.id=? AND b.customer_id=? AND COALESCE((s.document->>'intakeErased')::boolean,false)=false FOR UPDATE OF b",(r,n)->new Target(kind,target,revision,fields(r.getString(1))),target,customer).stream().findFirst().orElseThrow(PostgresCustomerCorrections::missing);
    }
    @Override public Receipt prepare(SessionBinding session,UUID customer,UUID target,String key,Change change){
        Objects.requireNonNull(session);Objects.requireNonNull(customer);Objects.requireNonNull(target);Objects.requireNonNull(change);
        String fingerprint=fingerprint(change);
        try{return scheduling.resolved(now->{
            UUID actor=PostgresAdminBooking.authorize(jdbc,session,now);Target before=current(customer,change.kind(),target);
            if(change.kind()==Kind.INTAKE)Change.intake(change.values()).validateAt(now);
            return new PostgresScheduling.Resolved(new CommandKey(new Scope(actor,"PREPARE_CORRECTION",target),key,fingerprint),receipt->{
                if(receipt.success()){Row row=row(receipt.entityId(),actor,now);if(!row.customer().equals(customer)||!row.target().equals(target)||row.kind()!=change.kind()||receipt.version()!=1)throw storage();
                    if(row.confirmed()){if(jdbc.queryForObject("SELECT count(*) FROM privacy_correction_fact WHERE id=? AND request_fingerprint=?",Integer.class,row.id(),fingerprint)!=1)throw storage();}
                    else {Payload p=decrypt(row);if(!fingerprint(new Change(p.kind(),p.values(),p.reason())).equals(fingerprint))throw storage();}}return receipt;
            },tx->SchedulingCommands.within(tx,()->{
                UUID id=UUID.randomUUID();Instant expires=new TemporaryExport(now).expiresAt();
                var payload=new Payload("CUSTOMER_CORRECTION_V1",id,customer,actor,change.kind(),target,hash(before),before.version(),change.values(),change.reason(),now,expires);
                String plain=PersistedJson.write(payload);String digest=Secrets.hash(plain);
                jdbc.update("INSERT INTO privacy_correction(id,customer_id,actor_id,kind,target_id,created_at,expires_at,digest,encrypted_proposal) VALUES(?,?,?,?,?,?,?,?,?)",id,customer,actor,change.kind().name(),target,stamp(now),stamp(expires),digest,secrets.encrypt(plain));
                jdbc.update("INSERT INTO privacy_request VALUES(?,?,'CORRECTION','PREVIEW',?,?)",id,customer,change.reason(),stamp(now));audit(id,customer,actor,change.kind(),target,"CORRECTION_PREPARED",change.reason(),now);
                return new Receipt(Code.OK,id,1);
            }));
        });}catch(Failure|com.purpuragenda.domain.DomainFailure failure){throw failure;}catch(RuntimeException failure){throw storage();}
    }
    @Override public Preview preview(SessionBinding session,UUID id){
        Objects.requireNonNull(session);Objects.requireNonNull(id);
        try{return scheduling.locked(tx->{
            UUID actor=PostgresAdminBooking.authorize(jdbc,session,tx.now());Row row=row(id,actor,tx.now());if(row.confirmed())throw missing();Payload payload=decrypt(row);
            Target before=current(row.customer(),row.kind(),row.target());if(!hash(before).equals(payload.baseline()))throw missing();
            return new Preview(id,row.customer(),before,payload.values(),payload.reason(),row.digest(),row.expires(),1);
        });}catch(Failure failure){throw failure;}catch(RuntimeException failure){throw storage();}
    }
    @Override public Receipt confirm(SessionBinding session,UUID id,String key,long version,Review review){
        Objects.requireNonNull(session);Objects.requireNonNull(id);Objects.requireNonNull(review);
        try{return scheduling.resolved(now->{
            UUID actor=PostgresAdminBooking.authorize(jdbc,session,now);Row row=row(id,actor,now);
            return new PostgresScheduling.Resolved(new CommandKey(new Scope(actor,"CONFIRM_CORRECTION",id),key,CommandKey.fingerprint(List.of(Long.toString(version),review.digest()))),receipt->{
                if(receipt.success()&&(!row.confirmed()||!id.equals(receipt.entityId())||receipt.version()!=2||jdbc.queryForObject("SELECT count(*) FROM privacy_correction_fact WHERE id=? AND customer_id=? AND kind=? AND target_id=?",Integer.class,id,row.customer(),row.kind().name(),row.target())!=1))throw storage();return receipt;
            },tx->SchedulingCommands.within(tx,()->{
                if(version!=1||row.confirmed()||!row.digest().equals(review.digest()))return Receipt.failure(Code.STALE_VERSION);
                Payload payload=decrypt(row);Target before=current(row.customer(),row.kind(),row.target());if(!hash(before).equals(payload.baseline()))return Receipt.failure(Code.STALE_VERSION);
                if(row.kind()==Kind.INTAKE)Change.intake(payload.values()).validateAt(now);
                long next=Math.addExact(before.version(),1);UUID booking=null;
                if(row.kind()==Kind.PROFILE)jdbc.update("UPDATE customer_profile SET document=?::jsonb,version=? WHERE customer_id=?",PersistedJson.write(payload.values()),next,row.customer());
                if(row.kind()==Kind.NOTE){booking=jdbc.queryForObject("SELECT booking_id FROM booking_note WHERE id=?",UUID.class,row.target());jdbc.update("UPDATE booking_note SET note=? WHERE id=?",payload.values().get("text"),row.target());}
                if(row.kind()==Kind.INTAKE){booking=row.target();jdbc.update("INSERT INTO booking_amendment VALUES(?,?,?,?::jsonb,?,?)",id,booking,actor,PersistedJson.write(Map.of("kind","INTAKE_CORRECTION","revision",next,"intake",payload.values())),payload.reason(),stamp(now));}
                String prior=row.kind()==Kind.NOTE?CommandKey.fingerprint(List.of(before.values().get("text"))):hash(before);
                jdbc.update("INSERT INTO privacy_correction_fact VALUES(?,?,?,?,?,?,?)",id,row.customer(),row.kind().name(),row.target(),next,prior,fingerprint(new Change(payload.kind(),payload.values(),payload.reason())));
                jdbc.update("DELETE FROM privacy_export WHERE customer_id=?",row.customer());
                jdbc.update("UPDATE privacy_correction SET confirmed_at=?,encrypted_proposal=NULL WHERE id=?",stamp(now),id);
                jdbc.update("UPDATE privacy_request SET state='CONFIRMED' WHERE id=?",id);
                audit(id,row.customer(),actor,row.kind(),row.target(),"CUSTOMER_CORRECTED",payload.reason(),now);
                return new Receipt(Code.OK,id,2);
            }));
        });}catch(Failure failure){throw failure;}catch(RuntimeException failure){throw storage();}
    }
    private Row row(UUID id,UUID actor,Instant now){return jdbc.query("SELECT * FROM privacy_correction WHERE NOT erased AND id=? AND actor_id=? AND created_at<=? AND expires_at>? FOR UPDATE",(r,n)->new Row(r.getObject("id",UUID.class),r.getObject("customer_id",UUID.class),r.getObject("actor_id",UUID.class),Kind.valueOf(r.getString("kind")),r.getObject("target_id",UUID.class),r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant(),r.getString("digest"),r.getString("encrypted_proposal"),r.getTimestamp("confirmed_at")!=null),id,actor,stamp(now),stamp(now)).stream().findFirst().orElseThrow(PostgresCustomerCorrections::missing);}
    private Payload decrypt(Row row){
        String plain=secrets.decrypt(row.encrypted());if(!Secrets.hash(plain).equals(row.digest()))throw storage();Payload p=PersistedJson.read(plain,Payload.class);
        if(!"CUSTOMER_CORRECTION_V1".equals(p.purpose())||!row.id().equals(p.id())||!row.customer().equals(p.customer())||!row.actor().equals(p.actor())||row.kind()!=p.kind()||!row.target().equals(p.target())||!row.created().equals(p.created())||!row.expires().equals(p.expires()))throw storage();
        new Change(p.kind(),p.values(),p.reason());return p;
    }
    private void audit(UUID id,UUID customer,UUID actor,Kind kind,UUID target,String type,String reason,Instant now){jdbc.update("INSERT INTO audit_event(id,actor_id,actor_kind,type,occurred_at,reason,metadata) VALUES(?,?,'ADMIN',?,?,?,?::jsonb)",UUID.randomUUID(),actor,type,stamp(now),reason,PersistedJson.write(Map.of("correctionId",id,"customerId",customer,"kind",kind,"targetId",target)));}
    @Override public int purge(int limit){
        if(limit<1||limit>500)throw new IllegalArgumentException("INVALID_CORRECTION_PURGE_LIMIT");
        try{return jdbc.update("WITH cutoff AS MATERIALIZED(SELECT clock_timestamp() AS now) DELETE FROM privacy_correction WHERE id IN (SELECT c.id FROM privacy_correction c CROSS JOIN cutoff WHERE c.expires_at<=cutoff.now AND c.confirmed_at IS NULL ORDER BY c.id LIMIT ? FOR UPDATE OF c SKIP LOCKED)",limit);}catch(RuntimeException failure){throw storage();}
    }
}
