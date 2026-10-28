package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.application.port.out.AdminChangesUnitOfWork;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.CustomerQuota;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.scheduling.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Administrator rejection, cancellation, rescheduling and outcomes. Authority is the locked persisted ADMIN session with
 * fresh MFA (shared with administrator creation); the target booking is locked before any receipt replay. Money facts are
 * never altered: rejection and refundable cancellations add refund follow-up for unrefunded receipts only.
 */
public final class PostgresAdminChanges implements AdminChangesUnitOfWork {
    private final PostgresScheduling scheduling;
    private final JdbcTemplate jdbc;
    public PostgresAdminChanges(DataSource source) { this(source,Set.of("zoom.us")); }
    public PostgresAdminChanges(DataSource source,Set<String> meetingHosts) {
        scheduling=new PostgresScheduling(source,meetingHosts);jdbc=new JdbcTemplate(source);
    }
    @Override public Receipt change(Command command,Function<Change,Receipt> work) {
        Objects.requireNonNull(command);Objects.requireNonNull(work);
        return scheduling.resolved(now -> {
            UUID admin=PostgresAdminBooking.authorize(jdbc,command.session(),now);
            if(jdbc.query("SELECT id FROM booking WHERE id=? FOR UPDATE",(r,n)->r.getObject(1,UUID.class),command.bookingId()).isEmpty())
                throw new Failure(Failure.Kind.NOT_FOUND);
            var key=new CommandKey(new Scope(admin,"ADMIN_"+command.operation().name()+"_BOOKING",command.bookingId()),command.key(),command.fingerprint());
            return new PostgresScheduling.Resolved(key,receipt -> {
                if(receipt.success() && !command.bookingId().equals(receipt.entityId()))throw new Failure(Failure.Kind.STORAGE_FAILURE);
                return receipt;
            },tx -> {
                ChangeState state=new ChangeState(tx,admin,command.bookingId());
                Receipt receipt=Objects.requireNonNull(work.apply(state));
                state.verify(receipt);
                return receipt;
            });
        });
    }
    private final class ChangeState implements Change {
        private final Transaction tx;private final UUID admin,bookingId;
        private Booking baseline;private boolean effects;
        ChangeState(Transaction tx,UUID admin,UUID bookingId) { this.tx=tx;this.admin=admin;this.bookingId=bookingId; }
        public Instant now() { return tx.now(); }
        public List<ServiceDefinition> services() { return tx.services(); }
        public SchedulingPolicy policy() { return tx.policy(); }
        public AvailabilityCalendar calendar() { return tx.calendar(); }
        public Optional<Booking> booking(UUID id) { return tx.booking(id); }
        public List<Booking> bookings(ReservationInterval range) { return tx.bookings(range); }
        public void save(Booking value) { tx.save(value); }
        public List<Booking> duePending() { return tx.duePending(); }
        public void expiryEffects(UUID id,long version) { tx.expiryEffects(id,version); }
        public CustomerQuota quota(UUID customer) { return tx.quota(customer); }
        public boolean chargeCreated(Booking value) { throw new Failure(Failure.Kind.STORAGE_FAILURE); }
        public Receipt attempt(Supplier<Receipt> mutation) { return tx.attempt(mutation); }
        public Booking target() {
            baseline=tx.booking(bookingId).orElseThrow(() -> new Failure(Failure.Kind.STORAGE_FAILURE));return baseline;
        }
        public void rejectedEffects(Booking original,Booking rejected,String reason) {
            transition(original,rejected,Booking.State.REJECTED);obligations("REJECTED");
            event("booking-admin-rejected","REJECTED",rejected,reason,"{}",true);
        }
        public void cancelledEffects(Booking original,Booking cancelled,Classification classification,String reason) {
            transition(original,cancelled,Booking.State.CANCELLED);
            if(classification!=Classification.LATE_CUSTOMER)obligations(classification==Classification.PROVIDER?"PROVIDER_CANCELLED":"CUSTOMER_CANCELLED");
            event("booking-admin-cancelled","CANCELLED",cancelled,reason,"{\"classification\":\""+classification+"\"}",true);
        }
        public void rescheduledEffects(Booking original,Booking moved,String reason,SchedulingRules.Override override) {
            transition(original,moved,original.state());
            if(original.start().equals(moved.start()))throw new Failure(Failure.Kind.STORAGE_FAILURE);
            boolean overridden=!override.rules().isEmpty();
            String rules=PostgresAdminBooking.json(override.rules().stream().map(Enum::name).sorted().toList());
            String blackouts=PostgresAdminBooking.json(override.affectedBlackoutIds().stream().map(UUID::toString).sorted().toList());
            event("booking-admin-rescheduled","RESCHEDULED",moved,reason,"{\"previousStart\":\""+original.start()+"\",\"start\":\""+moved.start()
                    +"\",\"overridden\":"+overridden+",\"rules\":"+rules+",\"blackoutIds\":"+blackouts+"}",true);
            if(overridden)jdbc.update("""
                INSERT INTO booking_override(id,booking_id,booking_version,policy_version,rules,blackout_ids,reason,created_at)
                VALUES(?,?,?,?,?::jsonb,?::jsonb,?,?)
                """,id("booking-override/"+moved.id()+"/"+moved.version()),moved.id(),moved.version(),tx.policy().version(),rules,blackouts,override.reason(),stamp(now()));
        }
        public void outcomeEffects(Booking original,Booking recorded,String reason) {
            if(recorded.state()!=Booking.State.COMPLETED && recorded.state()!=Booking.State.NO_SHOW)throw new Failure(Failure.Kind.STORAGE_FAILURE);
            transition(original,recorded,recorded.state());
            event("booking-admin-outcome",recorded.state().name(),recorded,reason==null?"":reason,"{}",false);
        }
        private void transition(Booking original,Booking next,Booking.State state) {
            Objects.requireNonNull(original);Objects.requireNonNull(next);
            if(baseline==null || effects || !baseline.equals(original) || next.state()!=state
                    || next.version()!=original.version()+1 || !tx.booking(bookingId).filter(next::equals).isPresent())throw new Failure(Failure.Kind.STORAGE_FAILURE);
            effects=true;
        }
        /** Follow-up for each not fully refunded receipt, due its full amount as for customer cancellation; never replaced. */
        private void obligations(String reason) {
            jdbc.update("""
                INSERT INTO refund_obligation(receipt_id,due_minor,reason)
                SELECT p.id,p.amount_minor,? FROM payment_entry p
                LEFT JOIN (SELECT receipt_id,sum(amount_minor) AS refunded FROM payment_entry WHERE kind='REFUND' GROUP BY receipt_id) r ON r.receipt_id=p.id
                WHERE p.booking_id=? AND p.kind='RECEIPT' AND p.amount_minor>COALESCE(r.refunded,0)
                ON CONFLICT(receipt_id) DO NOTHING
                """,reason,bookingId);
        }
        private void event(String prefix,String type,Booking value,String reason,String metadata,boolean notify) {
            UUID event=id(prefix+"/"+value.id()+"/"+value.version());
            jdbc.update("""
                INSERT INTO audit_event(id,booking_id,actor_id,actor_kind,type,reason,occurred_at,version,metadata)
                VALUES(?,?,?,'ADMIN',?,?,?,?,?::jsonb)
                """,event,value.id(),admin,type,reason,stamp(now()),value.version(),metadata);
            if(notify)jdbc.update("INSERT INTO outbox_event(id,booking_id,type,version,created_at) VALUES(?,?,?,?,?)",event,value.id(),type,value.version(),stamp(now()));
        }
        /** A committed change carries exactly its effects; an expected failure rolled back to its savepoint carries none. */
        void verify(Receipt receipt) {
            if(!receipt.success()) { if(effects)throw new Failure(Failure.Kind.STORAGE_FAILURE);return; }
            if(!effects || !bookingId.equals(receipt.entityId())
                    || tx.booking(bookingId).filter(b -> b.version()==receipt.version()).isEmpty())throw new Failure(Failure.Kind.STORAGE_FAILURE);
        }
    }
    private static UUID id(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }
    private static Timestamp stamp(Instant value) { return PersistedSession.stamp(value); }
}
