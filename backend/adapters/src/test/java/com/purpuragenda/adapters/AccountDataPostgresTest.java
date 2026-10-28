package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.postgres.PostgresAccountData;
import com.purpuragenda.adapters.out.security.*;
import com.purpuragenda.application.AccountData;
import com.purpuragenda.application.port.out.AccountDataStore.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.domain.DomainFailure;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(180)
@ResourceLock("postgres-migration-fixture")
class AccountDataPostgresTest {
    final CustomerCreationPostgresTest c=new CustomerCreationPostgresTest();
    @TempDir Path directory;PostgresAccountData store;AccountData app;
    static String encoded;
    @BeforeAll static void start() { MigrationTest.start();encoded=new Argon2PasswordHash().hash("synthetic-account-passphrase".toCharArray()); }
    @AfterAll static void stop() { MigrationTest.stop();encoded=null; }
    @BeforeEach void setup() throws Exception { c.directory=directory;c.setup();store=new PostgresAccountData(c.f.source);app=new AccountData(store); }
    @AfterEach void cleanup() { c.cleanup(); }
    record Account(SessionBinding binding,UUID customer,UUID id) { }
    Account account(String email) {
        UUID customer=UUID.randomUUID(),account=UUID.randomUUID(),id=UUID.randomUUID();String browser="b".repeat(64);
        c.jdbc.update("INSERT INTO customer_identity VALUES(?,?,clock_timestamp())",customer,email);
        c.jdbc.update("INSERT INTO customer_account(id,customer_id,enrolled_at,login_email,password_hash,password_changed_at) VALUES(?,?,clock_timestamp(),?,?,clock_timestamp())",account,customer,email,encoded);
        c.jdbc.update("""
            INSERT INTO app_session(id,token_hash,browser_hash,csrf_hash,kind,customer_id,account_id,account_credential_version,issued_at,last_seen_at,absolute_expires_at,authenticated_at)
            SELECT ?,?,?,?,'ACCOUNT',?,?,1,t,t,t+interval '12 hours',t FROM (SELECT clock_timestamp() t) captured
            """,id,Secrets.hash("synthetic-account-token"+id),browser,Secrets.hash("synthetic-account-csrf"),customer,account);
        return new Account(new SessionBinding(id,browser),customer,account);
    }
    Fields fields() { return new Fields("Synthetic","Customer","+32400123456"); }
    Failure failure(Runnable work) { Failure failure=assertThrows(Failure.class,work::run);assertNull(failure.getCause());assertFalse(failure.getMessage().contains("synthetic"));return failure; }
    int count(String table) { return c.jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
    @Test void profileIsExplicitVersionedIdempotentAndSeparateFromIdentitySnapshotsAndQuotas() {
        Account owner=account("synthetic@example.test");UUID booking=c.command.create(owner.binding(),"booking",c.request(3,9)).entityId();var original=c.load(booking);
        assertEquals(Profile.empty(),app.profile(owner.binding()).profile());assertEquals("synthetic@example.test",app.profile(owner.binding()).loginEmail());assertEquals(0,count("customer_profile"));
        c.jdbc.update("INSERT INTO customer_profile(customer_id,version,document) VALUES(?,1,'{}'::jsonb)",owner.customer());
        assertEquals(Profile.empty(),app.profile(owner.binding()).profile());assertEquals("{}",c.f.value("SELECT document::text FROM customer_profile WHERE customer_id=?",owner.customer()));
        int audit=count("audit_event"),events=count("outbox_event"),charges=count("request_charge");
        Receipt first=app.update(owner.binding(),"profile",1,fields());assertEquals(2,first.version());assertEquals(first,app.update(owner.binding(),"profile",1,fields()));
        assertEquals(Code.IDEMPOTENCY_KEY_REUSED,app.update(owner.binding(),"profile",1,new Fields("Different","","")).code());
        assertEquals(Code.STALE_VERSION,app.update(owner.binding(),"stale",1,fields()).code());assertEquals(Code.STALE_VERSION,app.update(owner.binding(),"stale",1,fields()).code());
        assertEquals(3,app.update(owner.binding(),"clear",2,new Fields("","","")).version());assertEquals(new Fields("","",""),app.profile(owner.binding()).profile().fields());
        assertEquals(original,c.load(booking));assertEquals(charges,count("request_charge"));assertEquals(events,count("outbox_event"));assertEquals(audit+2,count("audit_event"));
        assertEquals("synthetic@example.test",c.f.value("SELECT login_email FROM customer_account WHERE id=?",owner.id()));
        assertEquals("",c.f.value("SELECT reason FROM audit_event WHERE type='ACCOUNT_PROFILE_CHANGED' LIMIT 1"));assertEquals("{}",c.f.value("SELECT metadata::text FROM audit_event WHERE type='ACCOUNT_PROFILE_CHANGED' LIMIT 1"));
        var other=account("other@example.test");assertEquals(Profile.empty(),app.profile(other.binding()).profile());assertTrue(store.toString().contains("REDACTED"));
    }
    @Test void historyUsesStableIdentityAndCursorOrderingWithoutGuestScopeOrPrivateIntake() {
        Account owner=account("synthetic@example.test"),rival=account("other@example.test");
        UUID first=c.command.create(owner.binding(),"one",c.request(3,9)).entityId();
        c.scheduling.execute(new CommandKey(new Scope(UUID.randomUUID(),"SYNTHETIC_CANCEL",first),"cancel","a".repeat(64)),tx->{var b=tx.booking(first).orElseThrow().cancel(tx.now(),false);tx.save(b);return new Receipt(Code.OK,b.id(),b.version());});
        UUID second=c.command.create(owner.binding(),"two",c.request(3,9)).entityId();UUID third=c.command.create(owner.binding(),"three",c.request(4,9)).entityId();c.command.create(rival.binding(),"rival",c.request(5,9));
        var page=app.history(owner.binding(),null,1);assertEquals(third,page.bookings().getFirst().id());assertNotNull(page.next());
        var middle=app.history(owner.binding(),page.next(),1);var last=app.history(owner.binding(),middle.next(),1);assertNull(last.next());
        assertEquals(Set.of(first,second,third),Set.of(page.bookings().getFirst().id(),middle.bookings().getFirst().id(),last.bookings().getFirst().id()));
        assertEquals(c.jdbc.queryForList("SELECT id FROM booking WHERE customer_id=? ORDER BY start_at DESC,id DESC",UUID.class,owner.customer()),app.history(owner.binding(),null,100).bookings().stream().map(BookingSummary::id).toList());
        assertEquals(c.load(third).snapshot().service().name(),page.bookings().getFirst().serviceName());assertEquals(c.load(third).snapshot().service().price(),page.bookings().getFirst().price());
        assertTrue(app.history(owner.binding(),new Cursor(Instant.EPOCH,UUID.randomUUID()),100).bookings().isEmpty());
        assertEquals(1,app.history(rival.binding(),null,100).bookings().size());
        var guest=c.guest("synthetic@example.test",second);assertEquals(Failure.Kind.FORBIDDEN,failure(()->app.history(c.binding(guest),null,10)).kind());assertEquals(Failure.Kind.FORBIDDEN,failure(()->app.profile(c.binding(guest))).kind());
        assertEquals(Failure.Kind.FORBIDDEN,failure(()->app.update(c.binding(guest),"guest",1,fields())).kind());
    }
    @Test void readsCaptureTimeOnceNeverTouchSessionsAndExposeExpiredPendingWithoutMutation() {
        Account owner=account("synthetic@example.test");UUID booking=c.command.create(owner.binding(),"one",c.request(3,9)).entityId();Instant expiry=c.load(booking).expiresAt();
        // Keep the account session valid at the synthetic later clock while preserving its exact lifetime.
        c.jdbc.update("UPDATE app_session SET issued_at=?,last_seen_at=?,authenticated_at=?,absolute_expires_at=? WHERE id=?",Timestamp.from(expiry.minusSeconds(10)),Timestamp.from(expiry.minusSeconds(10)),Timestamp.from(expiry.minusSeconds(10)),Timestamp.from(expiry.plusSeconds(43190)),owner.binding().sessionId());
        String seen=c.f.value("SELECT last_seen_at FROM app_session WHERE id=?",owner.binding().sessionId());int receipts=count("idempotency_record"),audit=count("audit_event");
        var calls=new AtomicInteger();var timed=new AccountData(new PostgresAccountData(c.clock(expiry,calls)));
        var history=timed.history(owner.binding(),null,10);assertEquals(1,calls.get());assertEquals(expiry,history.observedAt());assertTrue(history.bookings().getFirst().pendingExpired());
        assertEquals(expiry,timed.profile(owner.binding()).observedAt());assertEquals(2,calls.get());assertEquals(seen,c.f.value("SELECT last_seen_at FROM app_session WHERE id=?",owner.binding().sessionId()));
        assertEquals(receipts,count("idempotency_record"));assertEquals(audit,count("audit_event"));assertEquals("PENDING_CONFIRMATION",c.f.value("SELECT state FROM booking WHERE id=?",booking));
    }
    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void idleAndAbsoluteExpiryRejectEqualityAndNeverReplayWithoutAuthority(long micros) {
        Account owner=account("synthetic@example.test");app.update(owner.binding(),"saved",1,fields());
        Instant idle=c.jdbc.queryForObject("SELECT last_seen_at+interval '30 minutes' FROM app_session WHERE id=?",(r,n)->r.getTimestamp(1).toInstant(),owner.binding().sessionId());
        var timed=new AccountData(new PostgresAccountData(c.clock(idle.plusNanos(micros*1000),new AtomicInteger())));
        if(micros<0)assertEquals(2,timed.profile(owner.binding()).profile().version());else assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,failure(()->timed.profile(owner.binding())).kind());
        Instant expiry=c.jdbc.queryForObject("SELECT absolute_expires_at FROM app_session WHERE id=?",(r,n)->r.getTimestamp(1).toInstant(),owner.binding().sessionId());
        c.jdbc.update("UPDATE app_session SET last_seen_at=? WHERE id=?",Timestamp.from(expiry.minusSeconds(1)),owner.binding().sessionId());
        var absolute=new AccountData(new PostgresAccountData(c.clock(expiry.plusNanos(micros*1000),new AtomicInteger())));
        if(micros<0)assertEquals(2,absolute.update(owner.binding(),"saved",1,fields()).version());else assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,failure(()->absolute.update(owner.binding(),"saved",1,fields())).kind());
    }
    @ParameterizedTest @ValueSource(strings={"revoke","epoch","future-seen","future-password"})
    void committedAuthorityChangesBlockReadsAndReceiptReplay(String change) {
        Account owner=account("synthetic@example.test");app.update(owner.binding(),"saved",1,fields());
        switch(change) {
            case "revoke" -> c.jdbc.update("UPDATE app_session SET revoked_at=clock_timestamp() WHERE id=?",owner.binding().sessionId());
            case "epoch" -> c.jdbc.update("UPDATE customer_account SET credential_version=credential_version+1 WHERE id=?",owner.id());
            case "future-seen" -> c.jdbc.update("UPDATE app_session SET last_seen_at=clock_timestamp()+interval '1 minute',authenticated_at=clock_timestamp()+interval '30 seconds' WHERE id=?",owner.binding().sessionId());
            default -> c.jdbc.update("UPDATE customer_account SET password_changed_at=clock_timestamp()+interval '1 minute' WHERE id=?",owner.id());
        }
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,failure(()->app.profile(owner.binding())).kind());assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,failure(()->app.history(owner.binding(),null,1)).kind());assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,failure(()->app.update(owner.binding(),"saved",1,fields())).kind());
    }
    @Test void missingWrongBrowserFutureAndUncredentialedSessionsFailClosed() {
        Account owner=account("synthetic@example.test");
        for(SessionBinding invalid:List.of(new SessionBinding(UUID.randomUUID(),owner.binding().browserHash()),new SessionBinding(owner.binding().sessionId(),"a".repeat(64)))) {
            assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,failure(()->app.profile(invalid)).kind());assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,failure(()->app.update(invalid,"bad",1,fields())).kind());
        }
        Instant issued=c.jdbc.queryForObject("SELECT issued_at FROM app_session WHERE id=?",(r,n)->r.getTimestamp(1).toInstant(),owner.binding().sessionId());
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,failure(()->new AccountData(new PostgresAccountData(c.clock(issued.minusNanos(1000),new AtomicInteger()))).profile(owner.binding())).kind());
        var empty=c.account("uncredentialed@example.test");assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,failure(()->app.profile(empty.binding())).kind());
        try(var anonymous=c.sessions.anonymous()) { assertEquals(Failure.Kind.FORBIDDEN,failure(()->app.profile(c.binding(anonymous.session()))).kind()); }
    }
    @Test void twoIndependentUnitsResolveTwentyIdenticalReceiptsAndCompetingVersionsAtomically() throws Exception {
        Account owner=account("synthetic@example.test");
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Receipt>> work=new ArrayList<>();for(int i=0;i<20;i++)work.add(()->new AccountData(new PostgresAccountData(c.f.source)).update(owner.binding(),"same",1,fields()));
            var results=executor.invokeAll(work);for(var result:results)assertEquals(2,result.get().version());
            assertEquals(1,count("customer_profile"));assertEquals(1,count("idempotency_record"));assertEquals(1,count("audit_event"));
            work.clear();for(int i=0;i<20;i++) { final int key=i;work.add(()->new AccountData(new PostgresAccountData(c.f.source)).update(owner.binding(),"race-"+key,2,new Fields("Synthetic "+key,"",""))); }
            var competing=executor.invokeAll(work);int success=0,stale=0;for(var result:competing) { if(result.get().success())success++;else if(result.get().code()==Code.STALE_VERSION)stale++; }
            assertEquals(1,success);assertEquals(19,stale);assertEquals(3,app.profile(owner.binding()).profile().version());assertEquals(2,count("audit_event"));
        }
    }
    @Test void unexpectedCallbacksRollbackAndCorruptDataFailsWithoutProviderDetails() {
        Account owner=account("synthetic@example.test");var command=new Command(owner.binding(),"bad","a".repeat(64));
        assertEquals(Failure.Kind.STORAGE_FAILURE,failure(()->store.change(command,current->current)).kind());
        assertEquals(Failure.Kind.STORAGE_FAILURE,failure(()->store.change(command,current->null)).kind());
        assertEquals("INVALID_PROFILE",assertThrows(DomainFailure.class,()->store.change(command,current->{throw new DomainFailure("INVALID_PROFILE");})).code());
        assertEquals(0,count("customer_profile"));assertEquals(0,count("idempotency_record"));assertEquals(0,count("audit_event"));
        app.update(owner.binding(),"saved",1,fields());
        for(String corrupt:List.of(
                "{\"code\":\"OK\",\"entityId\":\""+UUID.randomUUID()+"\",\"version\":2}",
                "{\"code\":\"OK\",\"entityId\":\""+owner.customer()+"\",\"version\":1}",
                "{\"code\":\"OK\",\"entityId\":\""+owner.customer()+"\",\"version\":3}",
                "{\"code\":\"OK\",\"entityId\":null,\"version\":0}",
                "{\"code\":\"INVALID_TRANSITION\",\"entityId\":null,\"version\":0}")) {
            c.jdbc.update("UPDATE idempotency_record SET receipt=?::jsonb WHERE key='saved'",corrupt);
            assertEquals(Failure.Kind.STORAGE_FAILURE,failure(()->app.update(owner.binding(),"saved",1,fields())).kind());
        }
        c.jdbc.update("UPDATE idempotency_record SET receipt='{}'::jsonb WHERE key='saved'");
        assertEquals(Failure.Kind.STORAGE_FAILURE,failure(()->app.update(owner.binding(),"saved",1,fields())).kind());
        c.jdbc.update("UPDATE customer_profile SET document='{}'::jsonb WHERE customer_id=?",owner.customer());assertEquals(Failure.Kind.STORAGE_FAILURE,failure(()->app.profile(owner.binding())).kind());
    }
    @Test void databaseFailuresAreSanitizedAndInvalidLimitsNeverQuery() {
        DataSource unavailable=new DelegatingDataSource(c.f.source) { @Override public Connection getConnection() throws SQLException { throw new SQLException("synthetic-provider-detail"); } };
        var broken=new AccountData(new PostgresAccountData(unavailable));var session=new SessionBinding(UUID.randomUUID(),"b".repeat(64));
        assertEquals(Failure.Kind.STORAGE_FAILURE,failure(()->broken.profile(session)).kind());assertEquals(Failure.Kind.STORAGE_FAILURE,failure(()->broken.history(session,null,1)).kind());assertEquals(Failure.Kind.STORAGE_FAILURE,failure(()->broken.update(session,"key",1,fields())).kind());
        assertThrows(DomainFailure.class,()->store.history(session,null,0));assertThrows(DomainFailure.class,()->store.history(session,null,101));assertThrows(NullPointerException.class,()->new PostgresAccountData(null));assertThrows(NullPointerException.class,()->store.profile(null));assertThrows(NullPointerException.class,()->store.change(null,current->current));assertThrows(NullPointerException.class,()->store.change(new Command(session,"key","a".repeat(64)),null));
    }
}
