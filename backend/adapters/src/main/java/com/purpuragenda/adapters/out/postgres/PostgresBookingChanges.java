package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.application.port.out.BookingChangesUnitOfWork;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
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
 * Customer cancellation and rescheduling. Authority is derived from locked persisted rows for the exact booking:
 * a management grant (directly or derived from the consumed creation grant) or the account owning the booking.
 * Unknown, unowned and differently scoped bookings all fail identically. Guest history authority does not exist.
 */
public final class PostgresBookingChanges implements BookingChangesUnitOfWork {
    private final PostgresScheduling scheduling;
    private final JdbcTemplate jdbc;
    public PostgresBookingChanges(DataSource source) { this(source,Set.of("zoom.us")); }
    public PostgresBookingChanges(DataSource source,Set<String> meetingHosts) {
        scheduling=new PostgresScheduling(source,meetingHosts);jdbc=new JdbcTemplate(source);
    }
    private record Authority(UUID actor,UUID customer,boolean guest) { }
    @Override public Receipt change(Command command,Function<Change,Receipt> work) {
        Objects.requireNonNull(command);Objects.requireNonNull(work);
        return scheduling.resolved(now -> {
            // Authority is established under the resource lock and before any idempotent receipt can be observed.
            Authority authority=authorize(command.session(),command.bookingId(),now);
            var key=new CommandKey(new Scope(authority.actor(),command.operation().name()+"_BOOKING",command.bookingId()),
                    command.key(),command.fingerprint());
            return new PostgresScheduling.Resolved(key,receipt -> {
                if(receipt.success() && !command.bookingId().equals(receipt.entityId()))throw new Failure(Failure.Kind.STORAGE_FAILURE);
                return receipt;
            },tx -> {
                ChangeState state=new ChangeState(tx,authority,command.bookingId());
                Receipt receipt=Objects.requireNonNull(work.apply(state));
                state.verifyEffects(receipt);
                return receipt;
            });
        });
    }
    private Authority authorize(SessionBinding binding,UUID bookingId,Instant now) {
        PersistedSession initial=PersistedSession.find(jdbc,binding,false);if(initial==null)throw unauthenticated();
        UUID actor;
        if(initial.kind().equals("GUEST")) actor=manageGrant(initial,binding.browserHash(),bookingId,now);
        else if(initial.kind().equals("ACCOUNT")) {
            List<UUID> accounts=jdbc.query("SELECT id FROM customer_account WHERE id=? AND customer_id=? FOR UPDATE",
                    (r,n)->r.getObject(1,UUID.class),initial.account(),initial.customer());
            if(accounts.size()!=1)throw denied();actor=accounts.getFirst();
        } else throw denied();
        // Lock order: resource -> grant/account -> session -> booking/customer. Recheck after the lock.
        PersistedSession current=PersistedSession.find(jdbc,binding,true);
        if(current==null || !current.validAfterLock(initial,now))throw unauthenticated();
        // Unknown and unowned bookings are indistinguishable from an out-of-scope grant.
        List<UUID> owners=jdbc.query("SELECT customer_id FROM booking WHERE id=? FOR UPDATE",(r,n)->r.getObject(1,UUID.class),bookingId);
        if(owners.size()!=1 || !owners.getFirst().equals(current.customer()))throw denied();
        // An independent HTTP session touch can occur after the command's captured time. Never move it backwards.
        jdbc.update("UPDATE app_session SET last_seen_at=GREATEST(last_seen_at,?) WHERE id=?",stamp(now),current.id());
        return new Authority(actor,current.customer(),initial.kind().equals("GUEST"));
    }
    /** Returns the exact-booking MANAGE grant id; a consumed creation grant authorizes only through its single live child. */
    private UUID manageGrant(PersistedSession session,String browser,UUID bookingId,Instant now) {
        List<Grant> grants=jdbc.query("SELECT id,customer_id,browser_hash,kind,booking_id,parent_grant_id,issued_at,expires_at,consumed_at,revoked_at FROM access_grant WHERE id=? FOR UPDATE",
                (r,n)->new Grant(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getObject(5,UUID.class),
                        r.getObject(6,UUID.class),PersistedSession.time(r,"issued_at"),PersistedSession.time(r,"expires_at"),
                        PersistedSession.time(r,"consumed_at"),PersistedSession.time(r,"revoked_at")),session.grant());
        if(grants.size()!=1)throw denied();Grant grant=grants.getFirst();
        if(grant.revoked()!=null || grant.issued()==null || grant.issued().isAfter(now) || !Objects.equals(grant.browser(),browser)
                || !grant.customer().equals(session.customer()) || !bookingId.equals(grant.booking()))throw denied();
        if(grant.kind().equals("MANAGE")) {
            if(grant.consumed()!=null || !now.isBefore(grant.expires()))throw denied();
            if(grant.parent()!=null && jdbc.query("SELECT id FROM access_grant WHERE id=? AND kind='CREATE' AND revoked_at IS NULL FOR UPDATE",
                    (r,n)->r.getObject(1,UUID.class),grant.parent()).size()!=1)throw denied();
            return grant.id();
        }
        if(!grant.kind().equals("CREATE") || grant.consumed()==null)throw denied();
        List<UUID> children=jdbc.query("""
            SELECT id FROM access_grant WHERE parent_grant_id=? AND kind='MANAGE' AND booking_id=?
              AND customer_id=? AND browser_hash=? AND revoked_at IS NULL AND issued_at<=? AND expires_at>?
            FOR UPDATE
            """,(r,n)->r.getObject(1,UUID.class),grant.id(),bookingId,grant.customer(),browser,stamp(now),stamp(now));
        if(children.size()!=1)throw denied();
        return children.getFirst();
    }
    private record Grant(UUID id,UUID customer,String browser,String kind,UUID booking,UUID parent,Instant issued,
                         Instant expires,Instant consumed,Instant revoked) { }
    private final class ChangeState implements Change {
        private final Transaction tx;private final Authority authority;private final UUID bookingId;
        private Booking baseline;private boolean effects;
        ChangeState(Transaction tx,Authority authority,UUID bookingId) { this.tx=tx;this.authority=authority;this.bookingId=bookingId; }
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
        public boolean chargeCreated(Booking value) { return tx.chargeCreated(value); }
        public Receipt attempt(Supplier<Receipt> mutation) { return tx.attempt(mutation); }
        public Booking authorizedBooking() {
            Booking value=tx.booking(bookingId).orElseThrow(() -> new Failure(Failure.Kind.STORAGE_FAILURE));
            if(!value.customerId().equals(authority.customer()))throw denied();
            baseline=value;return value;
        }
        public void cancelledEffects(Booking original,Booking cancelled) {
            transition(original,cancelled,Booking.State.CANCELLED);
            jdbc.update("""
                INSERT INTO refund_obligation(receipt_id,due_minor,reason)
                SELECT p.id,p.amount_minor-COALESCE(r.refunded,0),'CUSTOMER_CANCELLED' FROM payment_entry p
                LEFT JOIN (SELECT receipt_id,sum(amount_minor) AS refunded FROM payment_entry WHERE kind='REFUND' GROUP BY receipt_id) r ON r.receipt_id=p.id
                WHERE p.booking_id=? AND p.kind='RECEIPT' AND p.amount_minor>COALESCE(r.refunded,0)
                ON CONFLICT(receipt_id) DO NOTHING
                """,bookingId);
            event("booking-cancelled","CANCELLED",cancelled,"{}");
        }
        public void rescheduledEffects(Booking original,Booking moved) {
            transition(original,moved,original.state());
            if(original.start().equals(moved.start()))throw new Failure(Failure.Kind.STORAGE_FAILURE);
            event("booking-rescheduled","RESCHEDULED",moved,
                    "{\"previousStart\":\""+original.start()+"\",\"start\":\""+moved.start()+"\"}");
        }
        private void transition(Booking original,Booking next,Booking.State state) {
            Objects.requireNonNull(original);Objects.requireNonNull(next);
            if(baseline==null || effects || !baseline.equals(original) || !next.id().equals(bookingId) || next.state()!=state
                    || next.version()!=original.version()+1 || !next.customerId().equals(authority.customer())
                    || !tx.booking(bookingId).filter(next::equals).isPresent())throw new Failure(Failure.Kind.STORAGE_FAILURE);
            effects=true;
        }
        private void event(String prefix,String type,Booking value,String metadata) {
            UUID event=UUID.nameUUIDFromBytes((prefix+"/"+value.id()+"/"+value.version()).getBytes(StandardCharsets.UTF_8));
            jdbc.update("""
                INSERT INTO audit_event(id,booking_id,actor_id,actor_kind,type,occurred_at,version,metadata)
                VALUES(?,?,?,?,?,?,?,?::jsonb)
                """,event,value.id(),authority.actor(),authority.guest()?"GUEST":"ACCOUNT",type,stamp(now()),value.version(),metadata);
            jdbc.update("INSERT INTO outbox_event(id,booking_id,type,version,created_at) VALUES(?,?,?,?,?)",
                    event,value.id(),type,value.version(),stamp(now()));
        }
        /** A committed version change must carry exactly its customer effects; an unchanged booking carries none. */
        void verifyEffects(Receipt receipt) {
            if(!receipt.success())return;
            if(baseline==null || !bookingId.equals(receipt.entityId()))throw new Failure(Failure.Kind.STORAGE_FAILURE);
            Booking stored=tx.booking(bookingId).orElseThrow(() -> new Failure(Failure.Kind.STORAGE_FAILURE));
            boolean changed=stored.version()!=baseline.version();
            if(changed!=effects || stored.version()!=receipt.version())throw new Failure(Failure.Kind.STORAGE_FAILURE);
        }
    }
    private static Timestamp stamp(Instant value) { return PersistedSession.stamp(value); }
    private static Failure denied() { return PersistedSession.denied(); }
    private static Failure unauthenticated() { return PersistedSession.unauthenticated(); }
}
