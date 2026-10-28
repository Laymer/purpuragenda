package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.application.port.out.AccountDataStore;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.booking.Money;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Account lock serializes optional-profile writes. Guest grants never authorize profile or history. */
public final class PostgresAccountData implements AccountDataStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate reader,writer;
    public PostgresAccountData(DataSource source) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(source));var manager=new DataSourceTransactionManager(source);manager.setEnforceReadOnly(true);
        reader=new TransactionTemplate(manager);reader.setReadOnly(true);reader.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        writer=new TransactionTemplate(manager);writer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        for(var tx:List.of(reader,writer)) { tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setTimeout(15); }
    }
    @Override public Snapshot profile(SessionBinding session) {
        return read(session,(account,now)->new Snapshot(account.email(),current(account.customer(),false),now));
    }
    @Override public History history(SessionBinding session,Cursor before,int limit) {
        DomainFailure.require(limit>=1 && limit<=100,"INVALID_HISTORY_LIMIT");
        return read(session,(account,now)->{
            List<Object> arguments=new ArrayList<>(List.of(stamp(now),account.customer()));
            String cursor="";if(before!=null) { cursor=" AND (b.start_at,b.id)<(?,?)";arguments.add(stamp(before.start()));arguments.add(before.id()); }arguments.add(limit+1);
            List<BookingSummary> rows=jdbc.query("""
                SELECT b.id,b.reference,b.state,b.start_at,b.session_end,
                  (b.state='PENDING_CONFIRMATION' AND b.expires_at<=?) AS expired,
                  s.document->'service'->>'name' AS name,
                  (s.document->'service'->'price'->>'minor')::bigint AS amount,
                  s.document->'service'->'price'->>'currency' AS currency
                FROM booking b JOIN booking_snapshot s ON s.booking_id=b.id WHERE b.customer_id=?
                """+cursor+" ORDER BY b.start_at DESC,b.id DESC LIMIT ?",(r,n)->new BookingSummary(r.getObject("id",UUID.class),r.getString("reference"),
                    Booking.State.valueOf(r.getString("state")),r.getTimestamp("start_at").toInstant(),r.getTimestamp("session_end").toInstant(),
                    r.getBoolean("expired"),r.getString("name"),new Money(r.getLong("amount"),r.getString("currency"))),arguments.toArray());
            boolean more=rows.size()>limit;List<BookingSummary> page=List.copyOf(rows.subList(0,Math.min(rows.size(),limit)));
            Cursor next=more?new Cursor(page.getLast().start(),page.getLast().id()):null;return new History(page,next,now);
        });
    }
    private <T>T read(SessionBinding session,BiFunction<Authority,Instant,T> work) {
        Objects.requireNonNull(session);
        try { return reader.execute(status->{ Instant now=now();return work.apply(authorize(session,now),now); }); }
        catch(Failure failure) { throw failure; }catch(RuntimeException failure) { throw unavailable(); }
    }
    @Override public Receipt change(Command command,UnaryOperator<Profile> update) {
        Objects.requireNonNull(command);Objects.requireNonNull(update);
        try { return writer.execute(status->{
            jdbc.execute("SET LOCAL lock_timeout='2s'");
            jdbc.query("SELECT a.id FROM customer_account a JOIN app_session s ON s.account_id=a.id WHERE s.id=? AND s.browser_hash=? FOR UPDATE OF a",
                    (r,n)->r.getObject(1,UUID.class),command.session().sessionId(),command.session().browserHash());
            PersistedSession.find(jdbc,command.session(),true);Instant now=now();Authority account=authorize(command.session(),now);
            var key=new CommandKey(new Scope(account.id(),"ACCOUNT_PROFILE",account.customer()),command.key(),command.fingerprint());
            jdbc.update("UPDATE app_session SET last_seen_at=? WHERE id=?",stamp(now),command.session().sessionId());
            var cached=jdbc.query("SELECT fingerprint,receipt::text FROM idempotency_record WHERE scope=? AND key=?",(r,n)->
                    key.fingerprint().equals(r.getString(1))?PersistedJson.read(r.getString(2),Receipt.class):Receipt.failure(Code.IDEMPOTENCY_KEY_REUSED),key.scope().storageKey(),key.key());
            if(!cached.isEmpty())return cached.getFirst();
            Profile current=current(account.customer(),true);Receipt receipt;
            try {
                Profile next=Objects.requireNonNull(update.apply(current));
                if(next.version()!=Math.addExact(current.version(),1))throw unavailable();
                int changed=jdbc.update("""
                    INSERT INTO customer_profile(customer_id,version,document) VALUES(?,?,?::jsonb)
                    ON CONFLICT(customer_id) DO UPDATE SET version=EXCLUDED.version,document=EXCLUDED.document
                    WHERE customer_profile.version=?
                    """,account.customer(),next.version(),PersistedJson.write(next.fields()),current.version());
                if(changed!=1)throw unavailable();
                jdbc.update("INSERT INTO audit_event(id,actor_id,actor_kind,type,occurred_at,version) VALUES(?,?,'ACCOUNT','ACCOUNT_PROFILE_CHANGED',?,?)",UUID.randomUUID(),account.id(),stamp(now),next.version());
                receipt=new Receipt(Code.OK,account.customer(),next.version());
            } catch(DomainFailure failure) { if(!failure.code().equals("STALE_VERSION"))throw failure;receipt=Receipt.failure(Code.STALE_VERSION); }
            jdbc.update("INSERT INTO idempotency_record(scope,key,fingerprint,receipt,created_at) VALUES(?,?,?,?::jsonb,?)",key.scope().storageKey(),key.key(),key.fingerprint(),PersistedJson.write(receipt),stamp(now));
            return receipt;
        }); }catch(Failure | DomainFailure failure) { throw failure; }catch(RuntimeException failure) { throw unavailable(); }
    }
    private Profile current(UUID customer,boolean lock) {
        return jdbc.query("SELECT version,document::text FROM customer_profile WHERE customer_id=?"+(lock?" FOR UPDATE":""),
                (r,n)->new Profile(r.getLong(1),PersistedJson.read(r.getString(2),Fields.class)),customer).stream().findFirst().orElseGet(Profile::empty);
    }
    private record Authority(UUID id,UUID customer,String email) { @Override public String toString() { return "AccountAuthority[REDACTED]"; } }
    private Authority authorize(SessionBinding session,Instant now) {
        record Row(String kind,boolean permitted,Authority account) { }
        var rows=jdbc.query("""
            SELECT s.kind,a.id,a.customer_id,a.login_email,COALESCE(
              s.revoked_at IS NULL AND s.issued_at<=? AND s.last_seen_at<=? AND s.absolute_expires_at>?
              AND s.last_seen_at+interval '30 minutes'>? AND s.authenticated_at<=?
              AND a.credential_version=s.account_credential_version AND a.customer_id=s.customer_id
              AND a.enrolled_at<=? AND a.password_changed_at<=? AND a.password_hash IS NOT NULL
              AND a.login_email=i.email_key,false) AS permitted
            FROM app_session s LEFT JOIN customer_account a ON a.id=s.account_id
            LEFT JOIN customer_identity i ON i.id=a.customer_id WHERE s.id=? AND s.browser_hash=?
            """,(r,n)->new Row(r.getString("kind"),r.getBoolean("permitted"),new Authority(r.getObject("id",UUID.class),r.getObject("customer_id",UUID.class),r.getString("login_email"))),
                stamp(now),stamp(now),stamp(now),stamp(now),stamp(now),stamp(now),stamp(now),session.sessionId(),session.browserHash());
        if(rows.isEmpty())throw PersistedSession.unauthenticated();Row row=rows.getFirst();
        if(!row.kind().equals("ACCOUNT"))throw PersistedSession.denied();if(!row.permitted())throw PersistedSession.unauthenticated();return row.account();
    }
    private Instant now() { return jdbc.queryForObject("SELECT clock_timestamp()",(r,n)->r.getTimestamp(1).toInstant()); }
    private static Timestamp stamp(Instant value) { return Timestamp.from(value); }
    private static Failure unavailable() { return new Failure(Failure.Kind.STORAGE_FAILURE); }
    @Override public String toString() { return "AccountDataStore[REDACTED]"; }
}
