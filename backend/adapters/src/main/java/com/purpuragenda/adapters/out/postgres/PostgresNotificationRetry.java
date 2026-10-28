package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.application.port.out.NotificationRetryUnitOfWork;
import com.purpuragenda.application.port.out.AdministratorOperationsQuery.NotificationState;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.AdministratorNotificationRetry.Request;
import com.purpuragenda.domain.booking.Booking;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** One audited requeue; no SMTP call, new job, attempt reset or relevance override. */
public final class PostgresNotificationRetry implements NotificationRetryUnitOfWork {
    private final JdbcTemplate jdbc;private final PostgresScheduling scheduling;
    public PostgresNotificationRetry(DataSource source,Set<String> meetingHosts){jdbc=new JdbcTemplate(source);scheduling=new PostgresScheduling(source,meetingHosts);}
    @Override public Receipt retry(Command command,Function<Retry,Receipt> work) {
        Objects.requireNonNull(command);Objects.requireNonNull(work);
        return scheduling.resolved(now->{
            UUID admin=PostgresAdminBooking.authorize(jdbc,command.session(),now);
            boolean suppressed=Boolean.TRUE.equals(jdbc.queryForObject("SELECT delivery_suppressed FROM operation_control WHERE id=1 FOR SHARE",Boolean.class));
            var targets=jdbc.query("SELECT booking_id FROM notification_job WHERE id=? AND booking_id IS NOT NULL AND template<>'AUTH' FOR UPDATE",(r,n)->r.getObject(1,UUID.class),command.jobId());
            if(targets.isEmpty())throw new Failure(Failure.Kind.NOT_FOUND);UUID booking=targets.getFirst();
            var key=new CommandKey(new Scope(admin,"RETRY_NOTIFICATION",command.jobId()),command.key(),command.fingerprint());
            return new PostgresScheduling.Resolved(key,receipt->{
                if(receipt.success()) {
                    var fingerprints=jdbc.query("SELECT reason,metadata->>'attempts' FROM audit_event WHERE id=? AND booking_id=? AND actor_id=? AND type='NOTIFICATION_RETRY_REQUESTED' AND metadata->>'jobId'=?",
                            (r,n)->{try{return CommandKey.fingerprint(Arrays.asList(r.getString(2),r.getString(1)));}catch(RuntimeException invalid){throw storage();}},receipt.entityId(),booking,admin,command.jobId().toString());
                    if(receipt.version()!=1 || fingerprints.size()!=1 || !fingerprints.getFirst().equals(command.fingerprint()))throw storage();
                } else if(!failureCode(receipt.code()) && receipt.code()!=Code.IDEMPOTENCY_KEY_REUSED)throw storage();return receipt;
            },tx->{var state=new State(tx,command,admin,booking,suppressed);Receipt result=Objects.requireNonNull(work.apply(state));state.verify(result);return result;});
        });
    }
    private final class State extends TransactionDelegate implements Retry {
        private final Command command;private final UUID admin,bookingId;private final boolean suppressed;private final Map<UUID,Long> reclaimed=new HashMap<>();
        private Booking booking;private Job baseline;private UUID audit;private int attempts;private boolean attempting;
        State(Transaction tx,Command command,UUID admin,UUID booking,boolean suppressed){super(tx);this.command=command;this.admin=admin;this.bookingId=booking;this.suppressed=suppressed;}
        public Receipt attempt(Supplier<Receipt> mutation) {
            if(attempts++!=0 || !reclaimed.isEmpty())throw storage();return tx.attempt(()->{attempting=true;try{return mutation.get();}finally{attempting=false;}});
        }
        public Job authorizedJob() {
            if(!attempting || baseline!=null)throw storage();booking=tx.booking(bookingId).orElseThrow(PostgresNotificationRetry::storage);
            baseline=jdbc.queryForObject("SELECT state,attempts FROM notification_job WHERE id=?",(r,n)->new Job(command.jobId(),bookingId,NotificationState.valueOf(r.getString(1)),r.getInt(2),suppressed),command.jobId());return baseline;
        }
        public void queued(UUID id,String reason) {
            if(!attempting || baseline==null || audit!=null || id==null || baseline.deliverySuppressed() || baseline.state()!=NotificationState.FAILED)throw storage();
            Request request;try{request=new Request(baseline.attempts(),reason);}catch(RuntimeException invalid){throw storage();}
            if(!request.reason().equals(reason) || !CommandKey.fingerprint(List.of(Long.toString(request.expectedAttempts()),reason)).equals(command.fingerprint()))throw storage();
            audit=id;
            if(jdbc.update("UPDATE notification_job SET state='QUEUED',due_at=?,lease_until=NULL,lease_token=NULL,safe_error=NULL WHERE id=? AND state='FAILED' AND attempts=?",
                    Timestamp.from(now()),command.jobId(),baseline.attempts())!=1)throw storage();
            if(jdbc.update("INSERT INTO audit_event(id,booking_id,actor_id,actor_kind,type,reason,occurred_at,version,metadata) VALUES(?,?,?,'ADMIN','NOTIFICATION_RETRY_REQUESTED',?,?,?,jsonb_build_object('jobId',?::text,'attempts',?::integer))",
                    id,bookingId,admin,reason,Timestamp.from(now()),booking.version(),command.jobId().toString(),baseline.attempts())!=1)throw storage();
        }
        public void save(Booking value) {
            if(attempts!=0)throw storage();Booking original=tx.booking(value.id()).orElseThrow(PostgresNotificationRetry::storage);
            if(original.state()!=Booking.State.PENDING_CONFIRMATION || now().isBefore(original.expiresAt()) || !original.expire(now()).equals(value))throw storage();tx.save(value);reclaimed.put(value.id(),value.version());
        }
        public void expiryEffects(UUID id,long version){if(attempts!=0 || !Objects.equals(reclaimed.remove(id),version))throw storage();tx.expiryEffects(id,version);}
        public boolean chargeCreated(Booking value){throw storage();}
        void verify(Receipt receipt) {
            if(attempts!=1 || baseline==null || !tx.booking(bookingId).filter(booking::equals).isPresent())throw storage();
            if(!receipt.success()){if(audit!=null || !failureCode(receipt.code()))throw storage();return;}
            if(audit==null || !audit.equals(receipt.entityId()) || receipt.version()!=1 || jdbc.queryForObject("SELECT count(*) FROM notification_job WHERE id=? AND booking_id=? AND state='QUEUED' AND attempts=? AND due_at=? AND lease_until IS NULL AND lease_token IS NULL AND safe_error IS NULL",
                    Integer.class,command.jobId(),bookingId,baseline.attempts(),Timestamp.from(now()))!=1)throw storage();
        }
    }
    private static boolean failureCode(Code code){return code==Code.STALE_VERSION || code==Code.DELIVERY_SUPPRESSED || code==Code.NOTIFICATION_NOT_RETRYABLE;}
    private static Failure storage(){return new Failure(Failure.Kind.STORAGE_FAILURE);}
}
