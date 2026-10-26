package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.application.port.out.AdminBookingUnitOfWork;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.Customer;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.CustomerQuota;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.customer.Input;
import com.purpuragenda.domain.scheduling.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Administrator-created bookings. Authority is the locked persisted ADMIN session of the one enabled, enrolled administrator
 * whose security epoch matches and who authenticated with MFA within the last ten minutes. A caller reference alone is never
 * authority. Lock order: resource -> administrator -> session -> customer/booking rows.
 */
public final class PostgresAdminBooking implements AdminBookingUnitOfWork {
    static final long IDLE_SECONDS=900,FRESH_SECONDS=600;
    private final PostgresScheduling scheduling;
    private final JdbcTemplate jdbc;
    public PostgresAdminBooking(DataSource source) { this(source,Set.of("zoom.us")); }
    public PostgresAdminBooking(DataSource source,Set<String> meetingHosts) {
        scheduling=new PostgresScheduling(source,meetingHosts);jdbc=new JdbcTemplate(source);
    }
    @Override public Receipt create(Command command,Function<Creation,Receipt> work) {
        Objects.requireNonNull(command);Objects.requireNonNull(work);
        return scheduling.resolved(now -> {
            UUID admin=authorize(command.session(),now);
            var key=new CommandKey(new Scope(admin,"ADMIN_CREATE_BOOKING",null),command.key(),command.fingerprint());
            return new PostgresScheduling.Resolved(key,receipt -> receipt,tx -> {
                CreationState state=new CreationState(tx,admin);
                Receipt receipt=Objects.requireNonNull(work.apply(state));
                state.verify(receipt);
                return receipt;
            });
        });
    }
    private record AdminRow(boolean enabled,boolean enrolled,long epoch) { }
    private UUID authorize(SessionBinding binding,Instant now) {
        PersistedAdmin initial=find(binding,false);if(initial==null)throw PersistedSession.unauthenticated();
        if(!initial.kind().equals("ADMIN"))throw PersistedSession.denied();
        AdminRow row=jdbc.query("SELECT enabled,enrolled,security_version FROM admin_user WHERE id=? FOR UPDATE",
                (r,n)->new AdminRow(r.getBoolean(1),r.getBoolean(2),r.getLong(3)),initial.admin())
                .stream().findFirst().orElseThrow(PersistedSession::unauthenticated);
        PersistedAdmin current=find(binding,true);
        if(current==null || !current.valid(initial,row,now))throw PersistedSession.unauthenticated();
        // An independent HTTP touch can occur after the command's captured time. Never move it backwards.
        jdbc.update("UPDATE app_session SET last_seen_at=GREATEST(last_seen_at,?) WHERE id=?",stamp(now),current.id());
        return current.admin();
    }
    /** ADMIN rows always carry the administrator, epoch and authentication time (database CHECK). */
    private record PersistedAdmin(UUID id,String kind,UUID admin,long epoch,Instant issued,Instant lastSeen,Instant expires,
                                  Instant authenticated,Instant revoked) {
        boolean valid(PersistedAdmin initial,AdminRow row,Instant now) {
            return revoked==null && admin.equals(initial.admin()) && row.enabled() && row.enrolled() && epoch==row.epoch()
                    && !issued.isAfter(now) && now.isBefore(expires) && now.isBefore(lastSeen.plusSeconds(IDLE_SECONDS))
                    && !authenticated.isAfter(now) && now.isBefore(authenticated.plusSeconds(FRESH_SECONDS));
        }
    }
    private PersistedAdmin find(SessionBinding binding,boolean lock) {
        return jdbc.query("SELECT * FROM app_session WHERE id=? AND browser_hash=?"+(lock?" FOR UPDATE":""),(r,n)->new PersistedAdmin(
                r.getObject("id",UUID.class),r.getString("kind"),r.getObject("admin_id",UUID.class),r.getLong("admin_security_version"),
                PersistedSession.time(r,"issued_at"),PersistedSession.time(r,"last_seen_at"),PersistedSession.time(r,"absolute_expires_at"),
                PersistedSession.time(r,"authenticated_at"),PersistedSession.time(r,"revoked_at")),binding.sessionId(),binding.browserHash())
                .stream().findFirst().orElse(null);
    }
    private final class CreationState implements Creation {
        private final Transaction tx;private final UUID admin;private boolean effects;
        CreationState(Transaction tx,UUID admin) { this.tx=tx;this.admin=admin; }
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
        public boolean chargeCreated(Booking value) {
            // Administrator origin never records a submission charge; the store enforces it.
            return tx.chargeCreated(value);
        }
        public Receipt attempt(Supplier<Receipt> mutation) { return tx.attempt(mutation); }
        public Customer customer(String email) {
            String key=Input.email(email);Instant at=now();
            jdbc.update("INSERT INTO customer_identity(id,email_key,verified_at) VALUES(?,?,?) ON CONFLICT(email_key) DO NOTHING",
                    UUID.randomUUID(),key,stamp(at));
            return jdbc.queryForObject("SELECT id,email_key FROM customer_identity WHERE email_key=?",
                    (r,n)->new Customer(r.getObject(1,UUID.class),r.getString(2)),key);
        }
        public boolean meetingInUse(String meetingId) {
            return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM booking WHERE meeting_id=?)",Boolean.class,meetingId));
        }
        public void createdEffects(Booking value,SchedulingRules.Override override) {
            if(effects || value.origin()!=Booking.Origin.ADMINISTRATOR || value.version()!=1 || !value.createdAt().equals(now())
                    || !booking(value.id()).filter(value::equals).isPresent())throw new Failure(Failure.Kind.STORAGE_FAILURE);
            effects=true;
            boolean overridden=!override.rules().isEmpty();
            UUID event=UUID.nameUUIDFromBytes(("booking-admin-created/"+value.id()+"/"+value.version()).getBytes(StandardCharsets.UTF_8));
            String rules=json(override.rules().stream().map(Enum::name).sorted().toList());
            String blackouts=json(override.affectedBlackoutIds().stream().map(UUID::toString).sorted().toList());
            jdbc.update("""
                INSERT INTO audit_event(id,booking_id,actor_id,actor_kind,type,reason,occurred_at,version,metadata)
                VALUES(?,?,?,'ADMIN','ADMIN_CREATED',?,?,?,?::jsonb)
                """,event,value.id(),admin,overridden?override.reason():"",stamp(now()),value.version(),
                    "{\"state\":\""+value.state()+"\",\"overridden\":"+overridden+",\"rules\":"+rules+",\"blackoutIds\":"+blackouts+"}");
            jdbc.update("INSERT INTO outbox_event(id,booking_id,type,version,created_at) VALUES(?,?,'ADMIN_CREATED',?,?)",
                    event,value.id(),value.version(),stamp(now()));
            if(overridden)jdbc.update("""
                INSERT INTO booking_override(id,booking_id,booking_version,policy_version,rules,blackout_ids,reason,created_at)
                VALUES(?,?,?,?,?::jsonb,?::jsonb,?,?)
                """,UUID.nameUUIDFromBytes(("booking-override/"+value.id()+"/"+value.version()).getBytes(StandardCharsets.UTF_8)),
                    value.id(),value.version(),value.snapshot().policy().version(),rules,blackouts,override.reason(),stamp(now()));
        }
        /** A committed creation must carry exactly its audit/outbox effects. */
        void verify(Receipt receipt) {
            if(!receipt.success())return;
            if(!effects || tx.booking(receipt.entityId()).filter(b -> b.version()==receipt.version()).isEmpty())
                throw new Failure(Failure.Kind.STORAGE_FAILURE);
        }
    }
    private static String json(List<String> values) {
        // Rule names and UUIDs only: no quoting hazards.
        return values.stream().map(v -> "\""+v+"\"").collect(java.util.stream.Collectors.joining(",","[","]"));
    }
    private static Timestamp stamp(Instant value) { return PersistedSession.stamp(value); }
}
