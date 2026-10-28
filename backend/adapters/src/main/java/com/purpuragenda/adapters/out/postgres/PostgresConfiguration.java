package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.application.port.out.ConfigurationQuery;
import com.purpuragenda.application.port.out.ConfigurationUnitOfWork;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.ConfigurationConflicts;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.scheduling.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Administrator schedule and policy persistence. Authority is the locked persisted ADMIN session of the enabled, enrolled
 * administrator at the current security epoch with fresh MFA; no caller flag can substitute. Calendar versions advance by
 * one in business_settings and policy versions are appended immutably; booking snapshots are never touched.
 * Reads require the same persisted authority except fresh MFA.
 */
public final class PostgresConfiguration implements ConfigurationUnitOfWork, ConfigurationQuery {
    static final long ADMIN_IDLE_SECONDS=900,FRESH_MFA_SECONDS=600;
    private final PostgresScheduling scheduling;
    private final JdbcTemplate jdbc;
    public PostgresConfiguration(DataSource source) { this(source,Set.of("zoom.us")); }
    public PostgresConfiguration(DataSource source,Set<String> meetingHosts) {
        scheduling=new PostgresScheduling(source,meetingHosts);jdbc=new JdbcTemplate(source);
    }
    @Override public Receipt execute(Command command,Function<Configuration,Receipt> work) {
        Objects.requireNonNull(command);Objects.requireNonNull(work);
        return scheduling.resolved(now -> {
            UUID admin=authorize(command.session(),now);
            var key=new CommandKey(new Scope(admin,command.operation().name(),null),command.key(),command.fingerprint());
            return new PostgresScheduling.Resolved(key,stored -> stored,tx -> {
                ConfigurationState state=new ConfigurationState(tx,admin);
                Receipt receipt=Objects.requireNonNull(work.apply(state));
                state.verify(receipt);
                return receipt;
            });
        });
    }
    /** Authority and configuration share one read-only repeatable-read snapshot; no lock, touch or fresh-MFA requirement. */
    @Override public <T> T read(SessionBinding session,Function<View,T> work) {
        Objects.requireNonNull(session);Objects.requireNonNull(work);
        return scheduling.read(view -> { PersistedSession.administrator(jdbc,session,view.now());return work.apply(view); });
    }
    private record Row(UUID admin,Long epoch,Instant issued,Instant lastSeen,Instant expires,Instant authenticated,Instant revoked,String kind) { }
    private Row session(SessionBinding binding,boolean lock) {
        return jdbc.query("SELECT admin_id,admin_security_version,issued_at,last_seen_at,absolute_expires_at,authenticated_at,revoked_at,kind FROM app_session WHERE id=? AND browser_hash=?"+(lock?" FOR UPDATE":""),
                (r,n)->new Row(r.getObject(1,UUID.class),(Long)r.getObject(2),PersistedSession.time(r,"issued_at"),PersistedSession.time(r,"last_seen_at"),
                        PersistedSession.time(r,"absolute_expires_at"),PersistedSession.time(r,"authenticated_at"),PersistedSession.time(r,"revoked_at"),r.getString("kind")),
                binding.sessionId(),binding.browserHash()).stream().findFirst().orElse(null);
    }
    /** Lock order: resource -> administrator -> session. Everything is rechecked after the locks, before any receipt. */
    private UUID authorize(SessionBinding binding,Instant now) {
        Row initial=session(binding,false);
        if(initial==null)throw PersistedSession.unauthenticated();
        if(!"ADMIN".equals(initial.kind()))throw PersistedSession.denied();
        List<long[]> admin=jdbc.query("SELECT security_version FROM admin_user WHERE id=? AND enabled AND enrolled FOR UPDATE",(r,n)->new long[]{r.getLong(1)},initial.admin());
        Row row=session(binding,true);
        if(admin.size()!=1 || row==null || !"ADMIN".equals(row.kind()) || !initial.admin().equals(row.admin()))throw PersistedSession.unauthenticated();
        // Concurrent guarded activity may advance lastSeen after resource time was captured, before this row lock.
        // Factor time remains immutable authority and must not be in the command's future.
        if(row.revoked()!=null || row.issued().isAfter(now) || !now.isBefore(row.expires())
                || (row.authenticated()!=null && row.authenticated().isAfter(now))
                || !now.isBefore(row.lastSeen().plusSeconds(ADMIN_IDLE_SECONDS)) || row.epoch()==null || row.epoch()!=admin.getFirst()[0])throw PersistedSession.unauthenticated();
        if(row.authenticated()==null || !now.isBefore(row.authenticated().plusSeconds(FRESH_MFA_SECONDS)))
            throw new Failure(Failure.Kind.FRESH_AUTHENTICATION_REQUIRED);
        jdbc.update("UPDATE app_session SET last_seen_at=GREATEST(last_seen_at,?) WHERE id=?",Timestamp.from(now),binding.sessionId());
        return row.admin();
    }
    private final class ConfigurationState extends TransactionDelegate implements Configuration {
        private final UUID admin;private int saves,effects;
        ConfigurationState(Transaction tx,UUID admin) { super(tx);this.admin=admin; }
        public void saveCalendar(AvailabilityCalendar next) {
            if(saves++>0 || next.version()!=tx.calendar().version()+1)throw new Failure(Failure.Kind.STORAGE_FAILURE);
            jdbc.update("UPDATE business_settings SET calendar=?::jsonb WHERE id=1",PersistedJson.write(next));
            // The stored form must read back to exactly the reviewed calendar.
            if(!tx.calendar().equals(next))throw new Failure(Failure.Kind.STORAGE_FAILURE);
        }
        public void savePolicy(SchedulingPolicy next) {
            if(saves++>0 || next.version()!=tx.policy().version()+1)throw new Failure(Failure.Kind.STORAGE_FAILURE);
            jdbc.update("INSERT INTO policy_version(version,document) VALUES(?,?::jsonb)",next.version(),PersistedJson.write(next));
            jdbc.update("UPDATE business_settings SET policy_version=? WHERE id=1",next.version());
            if(!tx.policy().equals(next))throw new Failure(Failure.Kind.STORAGE_FAILURE);
        }
        public void changedEffects(Operation operation,long version,String reason,List<ConfigurationConflicts.Conflict> acknowledged) {
            if(saves!=1 || effects++>0)throw new Failure(Failure.Kind.STORAGE_FAILURE);
            boolean policy=operation==Operation.UPDATE_POLICY;
            long current=policy?tx.policy().version():tx.calendar().version();
            if(current!=version)throw new Failure(Failure.Kind.STORAGE_FAILURE);
            String change=(policy?"policy":"calendar")+"/"+version;
            jdbc.update("INSERT INTO audit_event(id,actor_id,actor_kind,type,reason,occurred_at,version,metadata) VALUES(?,?,'ADMIN',?,?,?,?,?::jsonb)",
                    id("configuration-changed/"+change),admin,policy?"POLICY_CHANGED":"CALENDAR_CHANGED",reason,Timestamp.from(now()),version,
                    "{\"operation\":\""+operation+"\",\"conflicts\":"+acknowledged.size()+"}");
            for(ConfigurationConflicts.Conflict conflict:acknowledged) {
                Booking stored=tx.booking(conflict.bookingId()).orElseThrow(() -> new Failure(Failure.Kind.STORAGE_FAILURE));
                UUID event=id("configuration-conflict/"+change+"/"+conflict.bookingId());
                String reasons="[\""+String.join("\",\"",conflict.reasons().stream().map(Enum::name).sorted().toList())+"\"]";
                jdbc.update("INSERT INTO audit_event(id,booking_id,actor_id,actor_kind,type,reason,occurred_at,version,metadata) VALUES(?,?,?,'ADMIN','CONFIGURATION_CONFLICT_ACKNOWLEDGED',?,?,?,?::jsonb)",
                        event,stored.id(),admin,reason,Timestamp.from(now()),stored.version(),"{\"operation\":\""+operation+"\",\"reasons\":"+reasons+"}");
                jdbc.update("INSERT INTO outbox_event(id,booking_id,type,version,created_at) VALUES(?,?,'CONFIGURATION_CONFLICT',?,?)",
                        event,stored.id(),stored.version(),Timestamp.from(now()));
            }
        }
        void verify(Receipt receipt) {
            // Expected failures roll the attempted mutation back to its savepoint, so they can never carry effects.
            boolean complete=saves==1 && effects==1;
            if(receipt.success()?!complete:(saves!=0 || effects!=0))throw new Failure(Failure.Kind.STORAGE_FAILURE);
        }
    }
    private static UUID id(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }
}
