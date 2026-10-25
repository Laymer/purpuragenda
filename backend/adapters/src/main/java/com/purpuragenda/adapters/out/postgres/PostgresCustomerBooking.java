package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.CustomerQuota;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.scheduling.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Uses the scheduling transaction's bound connection. Session references never constitute authority. */
public final class PostgresCustomerBooking implements CustomerBookingUnitOfWork {
    private final PostgresScheduling scheduling;
    private final JdbcTemplate jdbc;
    public PostgresCustomerBooking(DataSource source) { this(source,Set.of("zoom.us")); }
    public PostgresCustomerBooking(DataSource source,Set<String> meetingHosts) {
        scheduling=new PostgresScheduling(source,meetingHosts);jdbc=new JdbcTemplate(source);
    }
    @Override public Receipt create(Command command,Function<Creation,Receipt> work) {
        Objects.requireNonNull(command);Objects.requireNonNull(work);
        return scheduling.resolved(now -> {
            Authority authority=authorize(command.session(),now);
            var key=new CommandKey(new Scope(authority.actor(),"CREATE_BOOKING",null),command.key(),command.fingerprint());
            return new PostgresScheduling.Resolved(key,receipt -> {
                // A consumed CREATE may recover its one successful booking, never start another attempt.
                if(!authority.fresh() && receipt.code()!=Code.IDEMPOTENCY_KEY_REUSED
                        && !(receipt.success() && Objects.equals(authority.booking(),receipt.entityId()))) throw denied();
                return receipt;
            },tx -> {
                if(!authority.fresh())throw denied();
                return work.apply(new CreationState(tx,authority));
            });
        });
    }
    private record Session(UUID id,String browser,String kind,UUID grant,UUID customer,UUID account,
                           Instant issued,Instant lastSeen,Instant expires,Instant revoked) { }
    private record Grant(UUID id,UUID customer,String browser,String kind,UUID booking,Instant issued,
                         Instant expires,Instant consumed,Instant revoked) { }
    private record Authority(UUID actor,Customer customer,Grant grant,UUID booking,boolean fresh) { }
    private Authority authorize(SessionBinding binding,Instant now) {
        Session initial=session(binding,false);if(initial==null)throw unauthenticated();
        Grant grant=null;UUID actor;
        if(initial.kind().equals("GUEST")) {
            List<Grant> grants=jdbc.query("SELECT * FROM access_grant WHERE id=? FOR UPDATE",(r,n)->new Grant(
                    r.getObject("id",UUID.class),r.getObject("customer_id",UUID.class),r.getString("browser_hash"),r.getString("kind"),
                    r.getObject("booking_id",UUID.class),time(r,"issued_at"),time(r,"expires_at"),time(r,"consumed_at"),time(r,"revoked_at")),initial.grant());
            if(grants.size()!=1)throw denied();grant=grants.getFirst();
            if(!grant.kind().equals("CREATE") || grant.revoked()!=null || grant.issued()==null || grant.issued().isAfter(now)
                    || !Objects.equals(grant.browser(),binding.browserHash()) || !grant.customer().equals(initial.customer()))throw denied();
            if(grant.consumed()==null) { if(!now.isBefore(grant.expires()))throw denied(); }
            else {
                List<UUID> children=jdbc.query("""
                    SELECT id FROM access_grant WHERE parent_grant_id=? AND kind='MANAGE' AND booking_id=?
                      AND customer_id=? AND browser_hash=? AND revoked_at IS NULL AND issued_at<=? AND expires_at>?
                    FOR UPDATE
                    """,(r,n)->r.getObject(1,UUID.class),grant.id(),grant.booking(),grant.customer(),binding.browserHash(),stamp(now),stamp(now));
                if(children.size()!=1)throw denied();
            }
            actor=grant.id();
        } else if(initial.kind().equals("ACCOUNT")) {
            List<UUID> accounts=jdbc.query("SELECT id FROM customer_account WHERE id=? AND customer_id=? FOR UPDATE",
                    (r,n)->r.getObject(1,UUID.class),initial.account(),initial.customer());
            if(accounts.size()!=1)throw denied();actor=accounts.getFirst();
        } else throw denied();
        // Lock order: resource -> grant/account -> session -> booking/customer. Recheck after the lock.
        Session current=session(binding,true);
        if(current==null || current.revoked()!=null || current.issued().isAfter(now) || !now.isBefore(current.expires())
                || !now.isBefore(current.lastSeen().plusSeconds(1800)) || !current.kind().equals(initial.kind())
                || !Objects.equals(current.grant(),initial.grant()) || !Objects.equals(current.account(),initial.account())
                || !Objects.equals(current.customer(),initial.customer()))throw unauthenticated();
        Customer customer=jdbc.queryForObject("SELECT id,email_key FROM customer_identity WHERE id=?",
                (r,n)->new Customer(r.getObject(1,UUID.class),r.getString(2)),current.customer());
        // An independent HTTP session touch can occur after the command's captured time. Never move it backwards.
        jdbc.update("UPDATE app_session SET last_seen_at=GREATEST(last_seen_at,?) WHERE id=?",stamp(now),current.id());
        return new Authority(actor,Objects.requireNonNull(customer),grant,grant==null?null:grant.booking(),grant==null || grant.consumed()==null);
    }
    private Session session(SessionBinding binding,boolean lock) {
        return jdbc.query("SELECT * FROM app_session WHERE id=? AND browser_hash=?"+(lock?" FOR UPDATE":""),(r,n)->new Session(
                r.getObject("id",UUID.class),r.getString("browser_hash"),r.getString("kind"),r.getObject("grant_id",UUID.class),
                r.getObject("customer_id",UUID.class),r.getObject("account_id",UUID.class),time(r,"issued_at"),time(r,"last_seen_at"),
                time(r,"absolute_expires_at"),time(r,"revoked_at")),binding.sessionId(),binding.browserHash()).stream().findFirst().orElse(null);
    }
    private final class CreationState implements Creation {
        private final Transaction tx;private final Authority authority;
        CreationState(Transaction tx,Authority authority) { this.tx=tx;this.authority=authority; }
        public Customer customer() { tx.now();return authority.customer(); }
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
        private void created(Booking value) {
            if(!value.customerId().equals(authority.customer().id()) || value.origin()!=Booking.Origin.CUSTOMER
                    || value.version()!=1 || !value.createdAt().equals(now()) || !booking(value.id()).filter(value::equals).isPresent())
                throw new Failure(Failure.Kind.STORAGE_FAILURE);
        }
        public void bindCreation(Booking value) {
            created(value);Grant grant=authority.grant();if(grant==null)return;
            int consumed=jdbc.update("UPDATE access_grant SET booking_id=?,consumed_at=? WHERE id=? AND consumed_at IS NULL AND revoked_at IS NULL",
                    value.id(),stamp(now()),grant.id());
            if(consumed!=1)throw new Failure(Failure.Kind.STORAGE_FAILURE);
            jdbc.update("""
                INSERT INTO access_grant(id,customer_id,kind,booking_id,expires_at,issued_at,browser_hash,parent_grant_id)
                VALUES(?,?,'MANAGE',?,?,?,?,?)
                """,UUID.randomUUID(),value.customerId(),value.id(),stamp(grant.issued().plusSeconds(7200)),stamp(now()),grant.browser(),grant.id());
        }
        public void createdEffects(Booking value) {
            created(value);
            UUID event=UUID.nameUUIDFromBytes(("booking-requested/"+value.id()+"/"+value.version()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jdbc.update("""
                INSERT INTO audit_event(id,booking_id,actor_id,actor_kind,type,occurred_at,version)
                VALUES(?,?,?,?,'REQUESTED',?,?)
                """,event,value.id(),authority.actor(),authority.grant()==null?"ACCOUNT":"GUEST",stamp(now()),value.version());
            jdbc.update("INSERT INTO outbox_event(id,booking_id,type,version,created_at) VALUES(?,?,'REQUESTED',?,?)",
                    event,value.id(),value.version(),stamp(now()));
        }
    }
    private static Instant time(ResultSet row,String column) throws SQLException { Timestamp value=row.getTimestamp(column);return value==null?null:value.toInstant(); }
    private static Timestamp stamp(Instant value) { return Timestamp.from(value); }
    private static Failure denied() { return new Failure(Failure.Kind.FORBIDDEN); }
    private static Failure unauthenticated() { return new Failure(Failure.Kind.AUTHENTICATION_REQUIRED); }
}
