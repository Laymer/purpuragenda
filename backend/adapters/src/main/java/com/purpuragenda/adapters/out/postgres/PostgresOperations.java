package com.purpuragenda.adapters.out.postgres;
import com.purpuragenda.application.port.in.Actor;
import com.purpuragenda.application.port.out.OperationsGateway;
import com.purpuragenda.adapters.codec.Json;
import com.purpuragenda.adapters.out.security.Secrets;
import com.purpuragenda.domain.DomainFailure;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class PostgresOperations implements OperationsGateway {
    private final JdbcTemplate jdbc;private final TransactionTemplate transaction;private final Secrets secrets;
    public PostgresOperations(JdbcTemplate jdbc,PlatformTransactionManager manager,Secrets secrets){this.jdbc=jdbc;transaction=new TransactionTemplate(manager);this.secrets=secrets;}
    public List<Map<String,Object>> read(String area,UUID target){return switch(area){
        case "audit"->jdbc.queryForList("SELECT id,booking_id,actor_kind,type,reason,occurred_at,version FROM audit_event ORDER BY occurred_at DESC LIMIT 200");
        case "overrides"->jdbc.queryForList("SELECT booking_version,policy_version,rules::text,blackout_ids::text,reason,created_at FROM booking_override WHERE booking_id=? ORDER BY created_at",target);
        case "notes"->jdbc.queryForList("SELECT id,note,created_at FROM booking_note WHERE booking_id=? ORDER BY created_at",target);
        case "payments"->jdbc.queryForList("SELECT p.*,COALESCE(o.due_minor,0) AS due_minor FROM payment_entry p LEFT JOIN refund_obligation o ON o.receipt_id=p.id WHERE p.booking_id=? ORDER BY p.verified_at",target);
        case "notifications"->jdbc.queryForList("SELECT id,booking_id,audience,template,version,due_at,state,attempts,safe_error FROM notification_job ORDER BY due_at DESC LIMIT 200");
        case "reconciliation"->jdbc.queryForList("SELECT b.id AS booking_id,b.reference,p.currency,sum(o.due_minor-COALESCE((SELECT sum(r.amount_minor) FROM payment_entry r WHERE r.receipt_id=p.id),0)) AS due_minor FROM refund_obligation o JOIN payment_entry p ON p.id=o.receipt_id JOIN booking b ON b.id=p.booking_id GROUP BY b.id,b.reference,p.currency HAVING sum(o.due_minor-COALESCE((SELECT sum(r.amount_minor) FROM payment_entry r WHERE r.receipt_id=p.id),0))>0");
        case "customers"->jdbc.queryForList("SELECT c.id,c.email_key,c.verified_at,EXISTS(SELECT 1 FROM customer_account a WHERE a.customer_id=c.id) AS has_account FROM customer_identity c ORDER BY verified_at DESC LIMIT 200");
        case "privacy"->jdbc.queryForList("SELECT id,customer_id,kind,state,created_at FROM privacy_request ORDER BY created_at DESC LIMIT 200");
        default->throw new DomainFailure("NOT_FOUND");
    };}
    public UUID exportCustomer(UUID customer,Actor actor,String reason){return transaction.execute(status->{
        var identity=jdbc.queryForList("SELECT id,email_key,verified_at FROM customer_identity WHERE id=?",customer);if(identity.isEmpty())throw new DomainFailure("NOT_FOUND");
        Map<String,Object> data=new LinkedHashMap<>();data.put("identity",identity);data.put("profile",jdbc.queryForList("SELECT version,document::text FROM customer_profile WHERE customer_id=?",customer));
        data.put("bookings",jdbc.queryForList("SELECT b.*,s.document::text AS original_snapshot FROM booking b JOIN booking_snapshot s ON s.booking_id=b.id WHERE b.customer_id=?",customer));
        data.put("notes",jdbc.queryForList("SELECT n.note,n.created_at FROM booking_note n JOIN booking b ON b.id=n.booking_id WHERE b.customer_id=?",customer));
        data.put("amendments",jdbc.queryForList("SELECT a.document::text,a.reason,a.created_at FROM booking_amendment a JOIN booking b ON b.id=a.booking_id WHERE b.customer_id=?",customer));
        data.put("payments",jdbc.queryForList("SELECT p.* FROM payment_entry p JOIN booking b ON b.id=p.booking_id WHERE b.customer_id=?",customer));
        data.put("deliveryContactChanges",jdbc.queryForList("SELECT c.* FROM booking_contact_change c JOIN booking b ON b.id=c.booking_id WHERE b.customer_id=?",customer));
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO privacy_export(id,encrypted_document,expires_at) VALUES(?,?,clock_timestamp()+interval '10 minutes')",id,secrets.encrypt(Json.write(data)));audit(actor,"CUSTOMER_EXPORTED",reason);return id;
    });}
    public String downloadExport(UUID id){var rows=jdbc.queryForList("SELECT encrypted_document FROM privacy_export WHERE id=? AND expires_at>clock_timestamp()",String.class,id);if(rows.isEmpty())throw new DomainFailure("NOT_FOUND");return secrets.decrypt(rows.getFirst());}
    public UUID requestErasure(UUID customer,Actor actor,String reason){return transaction.execute(status->{if(jdbc.queryForObject("SELECT count(*) FROM customer_identity WHERE id=?",Integer.class,customer)!=1)throw new DomainFailure("NOT_FOUND");UUID id=UUID.randomUUID();jdbc.update("INSERT INTO privacy_request(id,customer_id,kind,state,reason,created_at) VALUES(?,?,'ERASURE','REVIEW_REQUIRED',?,clock_timestamp())",id,customer,reason);audit(actor,"ERASURE_REVIEW_REQUESTED",reason);return id;});}
    public void retry(UUID id,Actor actor,String reason){transaction.executeWithoutResult(status->{int changed=jdbc.update("UPDATE notification_job SET state='QUEUED',due_at=clock_timestamp(),safe_error=NULL WHERE id=? AND state='FAILED'",id);if(changed!=1)throw new DomainFailure("INVALID_TRANSITION");audit(actor,"NOTIFICATION_RETRY",reason);});}
    public Map<String,Object> profile(UUID customer){var rows=jdbc.queryForList("SELECT version,document::text FROM customer_profile WHERE customer_id=?",customer);return rows.isEmpty()?Map.of("version",0,"fields",Map.of()):Map.of("version",rows.getFirst().get("version"),"fields",Json.read((String)rows.getFirst().get("document"),Map.class));}
    public void profile(UUID customer,long expected,Map<String,String> fields){transaction.executeWithoutResult(status->{
        jdbc.queryForObject("SELECT id FROM customer_identity WHERE id=? FOR UPDATE",UUID.class,customer);
        Map<String,Object> old=profile(customer);if(((Number)old.get("version")).longValue()!=expected)throw new DomainFailure("STALE_VERSION");
        jdbc.update("INSERT INTO customer_profile(customer_id,version,document) VALUES(?,?,?::jsonb) ON CONFLICT(customer_id) DO UPDATE SET version=excluded.version,document=excluded.document",customer,expected+1,Json.write(fields));
    });}
    private void audit(Actor actor,String type,String reason){jdbc.update("INSERT INTO audit_event(id,actor_id,actor_kind,type,reason,occurred_at) VALUES(?,?,?,?,?,clock_timestamp())",UUID.randomUUID(),actor.id(),actor.kind().name(),type,reason);}
}
