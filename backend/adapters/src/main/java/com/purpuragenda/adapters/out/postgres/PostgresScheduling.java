package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.application.port.in.Actor;
import com.purpuragenda.adapters.codec.Json;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.payment.PaymentLedger;
import com.purpuragenda.domain.scheduling.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

public final class PostgresScheduling implements SchedulingUnitOfWork {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock decisionClock;
    public PostgresScheduling(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this(jdbc,manager,null);
    }
    /** Test composition may inject a clock. Operational composition always uses database time. */
    public PostgresScheduling(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock decisionClock){this.jdbc=jdbc;this.decisionClock=decisionClock;transaction=new TransactionTemplate(manager);transaction.setTimeout(15);}
    @Override public <T>T execute(Function<Transaction,T> work) {
        for(int attempt=0;;attempt++)try{
            return transaction.execute(status->{
                jdbc.execute("SET LOCAL lock_timeout='5s'");
                jdbc.queryForObject("SELECT id FROM scheduling_resource WHERE id=1 FOR UPDATE",Integer.class);
                Instant now=capture();
                return work.apply(new Tx(now,status));
            });
        }catch(CannotAcquireLockException e){if(attempt>=2)throw new DomainFailure("RETRY_LATER");}
    }
    @Override public <T>T read(Function<View,T> work) { return transaction.execute(status->work.apply(new Tx(capture(),status))); }
    private Instant capture(){return decisionClock==null?jdbc.queryForObject("SELECT clock_timestamp()",(r,n)->r.getTimestamp(1).toInstant()):decisionClock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    private final class Tx implements Transaction {
        private final Instant now;private final org.springframework.transaction.TransactionStatus status;
        Tx(Instant now,org.springframework.transaction.TransactionStatus status){this.now=now;this.status=status;}
        public Instant now(){return now;}
        public List<ServiceDefinition> services(){return jdbc.query("SELECT * FROM service ORDER BY price_minor,id",(r,n)->new ServiceDefinition(r.getObject("id",UUID.class),r.getLong("version"),r.getString("name"),r.getInt("duration_minutes"),new Money(r.getLong("price_minor"),r.getString("currency")),r.getBoolean("active")));}
        public SchedulingPolicy policy(){return Json.read(jdbc.queryForObject("SELECT p.document::text FROM business_settings s JOIN policy_version p ON p.version=s.policy_version WHERE s.id=1",String.class),SchedulingPolicy.class);}
        public AvailabilityCalendar calendar(){return Json.read(jdbc.queryForObject("SELECT calendar::text FROM business_settings WHERE id=1",String.class),AvailabilityCalendar.class);}
        public List<Booking> bookings(){return jdbc.query("SELECT b.*,s.document::text AS snapshot FROM booking b JOIN booking_snapshot s ON s.booking_id=b.id ORDER BY b.id",(r,n)->booking(r));}
        public String email(UUID customer){var rows=jdbc.queryForList("SELECT email_key FROM customer_identity WHERE id=?",String.class,customer);if(rows.isEmpty())throw new DomainFailure("CUSTOMER_NOT_FOUND");return rows.getFirst();}
        public PaymentLedger ledger(UUID booking){
            var entries=jdbc.query("SELECT * FROM payment_entry WHERE booking_id=? ORDER BY verified_at,id",(r,n)->new PaymentLedger.Entry(r.getObject("id",UUID.class),booking,PaymentLedger.Kind.valueOf(r.getString("kind")),new Money(r.getLong("amount_minor"),r.getString("currency")),r.getString("method"),r.getString("reference"),r.getTimestamp("verified_at").toInstant(),r.getObject("receipt_id",UUID.class)),booking);
            var due=jdbc.query("SELECT o.* FROM refund_obligation o JOIN payment_entry p ON p.id=o.receipt_id WHERE p.booking_id=?",(r,n)->new PaymentLedger.Obligation(r.getObject("receipt_id",UUID.class),r.getLong("due_minor"),r.getString("reason")),booking);
            return new PaymentLedger(entries,due);
        }
        public int requestCount(UUID customer,LocalDate date){return jdbc.queryForObject("SELECT COALESCE((SELECT count FROM customer_request_day WHERE customer_id=? AND submission_day=?),0)",Integer.class,customer,date);}
        public Optional<Receipt> replay(String scope,String key,String fingerprint){
            var rows=jdbc.queryForList("SELECT fingerprint,receipt::text FROM idempotency_record WHERE scope=? AND key=?",scope,key);
            if(rows.isEmpty())return Optional.empty();if(!fingerprint.equals(rows.getFirst().get("fingerprint")))throw new DomainFailure("IDEMPOTENCY_KEY_REUSED");
            return Optional.of(Json.read((String)rows.getFirst().get("receipt"),Receipt.class));
        }
        public void saveReceipt(String scope,String key,String fingerprint,Receipt receipt){jdbc.update("INSERT INTO idempotency_record(scope,key,fingerprint,receipt,created_at) VALUES(?,?,?,?::jsonb,?)",scope,key,fingerprint,Json.write(receipt),ts(now));}
        public Receipt attempt(Supplier<Receipt> mutation){
            Object savepoint=status.createSavepoint();
            try{return mutation.get();}
            catch(DomainFailure e){status.rollbackToSavepoint(savepoint);return new Receipt(e.code(),null,0,null);}
            catch(DataIntegrityViolationException e){status.rollbackToSavepoint(savepoint);String sqlState=sqlState(e);String code="23P01".equals(sqlState)?"SLOT_UNAVAILABLE":"23505".equals(sqlState)?"DUPLICATE_REFERENCE":"INVARIANT_CONFLICT";return new Receipt(code,null,0,constraint(e));}
            finally{status.releaseSavepoint(savepoint);}
        }
        public void save(Booking b){
            Instant ceiling=b.expiresAt();if(b.state()==Booking.State.PENDING_CONFIRMATION){Instant max=b.createdAt().plus(b.snapshot().policy().pendingLifetime()),buffer=b.start().minus(b.snapshot().policy().expiryBuffer());ceiling=max.isBefore(buffer)?max:buffer;}
            jdbc.update("""
              INSERT INTO booking(id,reference,customer_id,service_id,state,origin,created_at,start_at,session_minutes,session_end,reserved_end,business_date,expires_at,expiry_ceiling,delivery_email,meeting_id,meeting_url,confirmation_basis,version,calendar_revision,previously_confirmed)
              VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
              ON CONFLICT(id) DO UPDATE SET state=excluded.state,start_at=excluded.start_at,session_end=excluded.session_end,reserved_end=excluded.reserved_end,business_date=excluded.business_date,expires_at=excluded.expires_at,expiry_ceiling=excluded.expiry_ceiling,delivery_email=excluded.delivery_email,meeting_id=excluded.meeting_id,meeting_url=excluded.meeting_url,confirmation_basis=excluded.confirmation_basis,version=excluded.version,calendar_revision=excluded.calendar_revision,previously_confirmed=excluded.previously_confirmed
              """,b.id(),b.reference(),b.customerId(),b.snapshot().service().id(),b.state().name(),b.origin().name(),ts(b.createdAt()),ts(b.start()),b.snapshot().service().durationMinutes(),ts(b.sessionEnd()),ts(b.reservedEnd()),b.businessDate(),ts(b.expiresAt()),ts(ceiling),b.deliveryEmail(),b.meeting()==null?null:b.meeting().meetingId(),b.meeting()==null?null:b.meeting().participantUrl(),b.confirmationBasis()==null?null:b.confirmationBasis().name(),b.version(),b.calendarRevision(),b.previouslyConfirmed());
            jdbc.update("INSERT INTO booking_snapshot(booking_id,document) VALUES(?,?::jsonb) ON CONFLICT DO NOTHING",b.id(),Json.write(b.snapshot()));
            boolean occupied=Set.of(Booking.State.PENDING_CONFIRMATION,Booking.State.CONFIRMED,Booking.State.COMPLETED,Booking.State.NO_SHOW).contains(b.state());
            jdbc.update("INSERT INTO booking_occupancy(booking_id,resource_id,occupied,released_at) VALUES(?,1,tstzrange(?,?,'[)'),?) ON CONFLICT(booking_id) DO UPDATE SET occupied=excluded.occupied,released_at=excluded.released_at",b.id(),ts(b.start()),ts(b.reservedEnd()),occupied?null:ts(now));
        }
        public void saveLedger(UUID booking,PaymentLedger ledger){
            for(var entry:ledger.entries()){
                // Only the exact existing ID may replay; another reference collision must be an error.
                jdbc.update("INSERT INTO payment_entry(id,booking_id,kind,amount_minor,currency,method,reference,verified_at,receipt_id) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING",entry.id(),booking,entry.kind().name(),entry.amount().minor(),entry.amount().currency(),entry.method(),entry.reference(),ts(entry.verifiedAt()),entry.receiptId());
            }
            for(var due:ledger.obligations())jdbc.update("INSERT INTO refund_obligation(receipt_id,due_minor,reason) VALUES(?,?,?) ON CONFLICT DO NOTHING",due.receiptId(),due.dueMinor(),due.reason());
        }
        public void charge(Booking booking,LocalDate day){jdbc.update("INSERT INTO request_charge(booking_id,customer_id,submission_day,origin) VALUES(?,?,?,'CUSTOMER')",booking.id(),booking.customerId(),day);jdbc.update("INSERT INTO customer_request_day(customer_id,submission_day,count) VALUES(?,?,1) ON CONFLICT(customer_id,submission_day) DO UPDATE SET count=customer_request_day.count+1",booking.customerId(),day);}
        public void bindCreationGrant(Actor actor,UUID booking){
            if(actor.kind()!=Actor.Kind.CREATION_GRANT)return;
            int changed=jdbc.update("UPDATE access_grant SET kind='MANAGEMENT_GRANT',booking_id=?,consumed_at=?,expires_at=? WHERE id=? AND kind='CREATION_GRANT' AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at>?",booking,ts(now),ts(now.plusSeconds(7200)),actor.id(),ts(now));
            if(changed!=1)throw new DomainFailure("GRANT_ALREADY_USED");
        }
        public void event(Booking booking,String type,Actor actor,String reason){
            audit(booking.id(),type,actor,reason,booking.version());
            if(!"OUTCOME".equals(type))jdbc.update("INSERT INTO outbox_event(id,booking_id,type,version,created_at) VALUES(?,?,?,?,?)",UUID.randomUUID(),booking.id(),type,booking.version(),ts(now));
        }
        public void saveService(ServiceDefinition service){jdbc.update("INSERT INTO service(id,version,name,duration_minutes,price_minor,currency,active) VALUES(?,?,?,?,?,'EUR',?) ON CONFLICT(id) DO UPDATE SET version=excluded.version,name=excluded.name,duration_minutes=excluded.duration_minutes,price_minor=excluded.price_minor,active=excluded.active",service.id(),service.version(),service.name(),service.durationMinutes(),service.price().minor(),service.active());audit(null,"SERVICE_CHANGED",null,"",service.version());}
        public void saveConfiguration(SchedulingPolicy policy,AvailabilityCalendar calendar,Actor actor,String reason){jdbc.update("INSERT INTO policy_version(version,document) VALUES(?,?::jsonb)",policy.version(),Json.write(policy));jdbc.update("UPDATE business_settings SET policy_version=?,calendar=?::jsonb WHERE id=1",policy.version(),Json.write(calendar));audit(null,"CONFIGURATION_CHANGED",actor,reason,policy.version());}
        public void addNote(UUID booking,String note,Actor actor){jdbc.update("INSERT INTO booking_note(id,booking_id,actor_id,note,created_at) VALUES(?,?,?,?,?)",UUID.randomUUID(),booking,actor.id(),note,ts(now));audit(booking,"NOTE_ADDED",actor,"",null);}
        public String verifyContact(UUID booking,String tokenHash,String browserHash){var rows=jdbc.queryForList("SELECT id,email_key FROM email_challenge WHERE booking_id=? AND token_hash=? AND browser_hash=? AND purpose='CONTACT' AND consumed_at IS NULL AND expires_at>? FOR UPDATE",booking,tokenHash,browserHash,ts(now));if(rows.isEmpty())throw new DomainFailure("INVALID_TOKEN");jdbc.update("UPDATE email_challenge SET consumed_at=? WHERE id=?",ts(now),rows.getFirst().get("id"));jdbc.update("UPDATE notification_job SET encrypted_envelope=NULL WHERE challenge_id=?",rows.getFirst().get("id"));return (String)rows.getFirst().get("email_key");}
        public void contactChanged(Booking previous,Booking updated,Actor actor){UUID change=UUID.randomUUID();jdbc.update("INSERT INTO booking_contact_change(id,booking_id,old_email,new_email,changed_at) VALUES(?,?,?,?,?)",change,updated.id(),previous.deliveryEmail(),updated.deliveryEmail(),ts(now));UUID event=UUID.randomUUID();jdbc.update("INSERT INTO outbox_event(id,booking_id,type,version,created_at,payload) VALUES(?,?,'CONTACT_CHANGED',?,?,?::jsonb)",event,updated.id(),updated.version(),ts(now),Json.write(Map.of("contactChangeId",change)));audit(updated.id(),"CONTACT_CHANGED",actor,"",updated.version());}
        public void recordOverride(Booking booking,SchedulingRules.Override override,Actor actor){jdbc.update("INSERT INTO booking_override(id,booking_id,booking_version,policy_version,rules,blackout_ids,reason,created_at) VALUES(?,?,?,?,?::jsonb,?::jsonb,?,?)",UUID.randomUUID(),booking.id(),booking.version(),policy().version(),Json.write(override.rules()),Json.write(override.affectedBlackoutIds()),override.reason(),ts(now));audit(booking.id(),"RULES_OVERRIDDEN",actor,override.reason(),booking.version());}
        private void audit(UUID booking,String type,Actor actor,String reason,Long version){jdbc.update("INSERT INTO audit_event(id,booking_id,actor_id,actor_kind,type,reason,occurred_at,version) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID(),booking,actor==null?null:actor.id(),actor==null?"SYSTEM":actor.kind().name(),type,Objects.requireNonNullElse(reason,""),ts(now),version);}
    }
    private static Booking booking(ResultSet r)throws SQLException{
        String meeting=r.getString("meeting_url"),basis=r.getString("confirmation_basis");Timestamp expiry=r.getTimestamp("expires_at");
        return new Booking(r.getObject("id",UUID.class),r.getString("reference"),r.getObject("customer_id",UUID.class),Json.read(r.getString("snapshot"),Booking.Snapshot.class),r.getString("delivery_email"),Booking.State.valueOf(r.getString("state")),Booking.Origin.valueOf(r.getString("origin")),r.getTimestamp("created_at").toInstant(),r.getTimestamp("start_at").toInstant(),expiry==null?null:expiry.toInstant(),meeting==null?null:new Meeting(meeting,r.getString("meeting_id")),basis==null?null:Booking.ConfirmationBasis.valueOf(basis),r.getLong("version"),r.getInt("calendar_revision"),r.getBoolean("previously_confirmed"));
    }
    private static Timestamp ts(Instant instant){return instant==null?null:Timestamp.from(instant);}
    private static String sqlState(Throwable e){for(Throwable t=e;t!=null;t=t.getCause())if(t instanceof SQLException sql)return sql.getSQLState();return "";}
    private static String constraint(Throwable e){for(Throwable t=e;t!=null;t=t.getCause())if(t instanceof org.postgresql.util.PSQLException sql&&sql.getServerErrorMessage()!=null){var error=sql.getServerErrorMessage();return sql.getSQLState()+":"+error.getConstraint()+":"+error.getTable()+":"+error.getColumn();}return null;}
}
