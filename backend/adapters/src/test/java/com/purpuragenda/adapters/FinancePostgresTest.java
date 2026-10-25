package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.adapters.out.security.Secrets;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.FinanceUnitOfWork;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.port.out.SessionStore;
import com.purpuragenda.application.scheduling.AdministratorFinance;
import com.purpuragenda.application.scheduling.CustomerBookingChanges;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.payment.PaymentLedger;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL administrator money commands: persisted authority, independent facts, rollback and races. */
@Timeout(300)
@ResourceLock("postgres-migration-fixture")
class FinancePostgresTest {
    final CustomerCreationPostgresTest c=new CustomerCreationPostgresTest();
    @TempDir Path directory;
    PostgresFinance adapter;AdministratorFinance finance;Admin admin;
    record Admin(SessionBinding binding,UUID id) { }
    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void setup() throws Exception {
        c.directory=directory;c.setup();adapter=new PostgresFinance(c.f.source);finance=new AdministratorFinance(adapter);admin=admin();
    }
    @AfterEach void cleanup() { c.cleanup(); }

    Admin admin() {
        UUID id=UUID.randomUUID(),session=UUID.randomUUID();String browser="d".repeat(64);
        c.jdbc.update("INSERT INTO admin_user(id,email_key,password_hash,totp_secret,enrolled,enabled) VALUES(?,'owner@example.test','synthetic-hash','synthetic-secret',true,true)",id);
        c.jdbc.update("""
            INSERT INTO app_session(id,token_hash,browser_hash,csrf_hash,kind,admin_id,admin_security_version,issued_at,last_seen_at,absolute_expires_at,authenticated_at)
            SELECT ?,?,?,?,'ADMIN',?,(SELECT security_version FROM admin_user WHERE id=?),t,t,t+interval '8 hours',t FROM (SELECT clock_timestamp() t) captured
            """,session,Secrets.hash("synthetic-admin-token"+session),browser,Secrets.hash("synthetic-admin-csrf"),id,id);
        return new Admin(new SessionBinding(session,browser),id);
    }
    /** Place the admin session at a known position relative to a captured instant. */
    void sessionAt(Instant captured,long idleSeconds,long authenticatedAgoSeconds) {
        Instant issued=captured.minusSeconds(Math.max(idleSeconds,authenticatedAgoSeconds)+60);
        c.jdbc.update("UPDATE app_session SET issued_at=?,last_seen_at=?,authenticated_at=?,absolute_expires_at=?::timestamptz+interval '8 hours' WHERE id=?",
                Timestamp.from(issued),Timestamp.from(captured.minusSeconds(idleSeconds)),Timestamp.from(captured.minusSeconds(authenticatedAgoSeconds)),Timestamp.from(issued),admin.binding().sessionId());
    }
    UUID pending(String key,int day,int hour) { Receipt r=c.create(c.guest(key+"@example.test"),key,c.request(day,hour));assertTrue(r.success());return r.entityId(); }
    final Instant verified=Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    AdministratorFinance.ReceiptRequest money(long minor,String currency,String reference) {
        return new AdministratorFinance.ReceiptRequest(new Money(minor,currency),"synthetic-bank",reference,verified);
    }
    Receipt record(UUID booking,String key,AdministratorFinance.ReceiptRequest request) { return finance.recordReceipt(admin.binding(),booking,key,request); }
    Failure fail(org.junit.jupiter.api.function.Executable work) { return c.failure(work); }
    int count(String sql) { return c.count(sql); }
    Instant expires(UUID booking) { return c.jdbc.queryForObject("SELECT expires_at FROM booking WHERE id=?",(r,n)->r.getTimestamp(1).toInstant(),booking); }
    AdministratorFinance at(Instant captured) { return new AdministratorFinance(new PostgresFinance(c.clock(captured,new AtomicInteger()))); }

    @Test void receiptOnALiveHoldIsOneImmutableFactWithAuditOutboxAndReplayAndLeavesTheBookingUntouched() {
        UUID id=pending("a",3,9);Booking before=c.load(id);
        Receipt first=record(id,"receipt",money(1500,"EUR","synthetic-ref-1"));
        assertTrue(first.success());assertEquals(before.version(),first.version());assertEquals(before,c.load(id));
        assertEquals(1,count("payment_entry WHERE kind='RECEIPT' AND id='"+first.entityId()+"' AND booking_id='"+id+"' AND amount_minor=1500 AND currency='EUR'"));
        assertEquals(0,count("refund_obligation"));assertEquals(1,count("audit_event WHERE type='PAYMENT_RECORDED' AND actor_kind='ADMIN' AND actor_id='"+admin.id()+"'"));
        assertEquals(1,count("outbox_event WHERE type='PAYMENT_RECORDED'"));
        assertEquals(admin.id()+"/RECORD_RECEIPT/"+id,c.f.value("SELECT scope FROM idempotency_record WHERE scope LIKE '%RECORD_RECEIPT%'"));
        assertFalse(c.f.value("SELECT metadata::text FROM audit_event WHERE type='PAYMENT_RECORDED'").contains("synthetic-ref-1"));
        assertEquals(first,record(id,"receipt",money(1500,"EUR","synthetic-ref-1")));
        assertEquals(1,count("payment_entry"));assertEquals(1,count("outbox_event WHERE type='PAYMENT_RECORDED'"));
        assertEquals(Code.IDEMPOTENCY_KEY_REUSED,record(id,"receipt",money(1501,"EUR","synthetic-ref-1")).code());
        assertEquals(1,count("payment_entry"));
        c.f.database.denied("23514","UPDATE payment_entry SET amount_minor=1");c.f.database.denied("23514","DELETE FROM payment_entry");
    }

    @Test void exactActualMoneyIncludingForeignCurrencyAndAmountsAboveTheServiceCeilingIsRecordedWithoutConversion() {
        UUID id=pending("a",3,9);
        assertTrue(record(id,"jpy",money(250_000_000L,"JPY","synthetic-jpy")).success());
        assertTrue(record(id,"big",money(200_000_000L,"EUR","synthetic-big")).success());
        assertTrue(record(id,"small",money(1,"EUR","synthetic-small")).success());
        assertEquals(Code.INVALID_PAYMENT,record(id,"overflow",money(Long.MAX_VALUE,"EUR","synthetic-overflow")).code());
        assertEquals(3,count("payment_entry"));assertEquals("250000000",c.f.value("SELECT sum(amount_minor) FROM payment_entry WHERE currency='JPY'"));
        assertEquals(0,count("payment_entry WHERE currency NOT IN ('EUR','JPY')"));
    }

    @Test void receiptsForCancelledAndExpiredBookingsAreLateReconciliationNotOccupancyOrRevival() {
        var session=c.guest("late@example.test");UUID cancelled=c.create(session,"c",c.request(3,9)).entityId();
        assertTrue(new CustomerBookingChanges(new PostgresBookingChanges(c.f.source)).cancel(c.binding(session),cancelled,"cancel",new CustomerBookingChanges.Cancellation(1)).success());
        UUID due=c.f.database.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        Booking cancelledBefore=c.load(cancelled);
        assertTrue(record(cancelled,"late-1",money(900,"EUR","synthetic-late-1")).success());
        assertTrue(record(due,"late-2",money(800,"USD","synthetic-late-2")).success());
        assertEquals(Booking.State.CANCELLED,c.load(cancelled).state());assertEquals(cancelledBefore,c.load(cancelled));
        assertEquals(Booking.State.EXPIRED,c.load(due).state());assertEquals(0,count("booking_occupancy WHERE released_at IS NULL AND booking_id IN ('"+cancelled+"','"+due+"')"));
        assertEquals(2,count("refund_obligation WHERE reason='LATE_RECEIPT'"));
        assertEquals(1,count("refund_obligation o JOIN payment_entry p ON p.id=o.receipt_id WHERE p.booking_id='"+due+"' AND o.due_minor=800 AND o.reason='LATE_RECEIPT'"));
        assertEquals(2,count("outbox_event WHERE type='PAYMENT_RECONCILIATION_REQUIRED'"));assertEquals(2,count("audit_event WHERE type='PAYMENT_RECONCILIATION_REQUIRED'"));
        // The expiry reclamation preceding the late receipt left its own system effects.
        assertEquals(1,count("outbox_event WHERE type='EXPIRED'"));
        // A late receipt still reports a refund due through the P06 projection.
        var seen=new PaymentLedger[1];
        adapter.execute(new FinanceUnitOfWork.Command(admin.binding(),due,FinanceUnitOfWork.Operation.RECORD_RECEIPT,"inspect",CommandKey.fingerprint(List.of("inspect"))),tx->{
            tx.authorizedBooking();seen[0]=tx.ledger();return Receipt.failure(Code.INVALID_TRANSITION);
        });
        var summary=seen[0].summary(c.load(due).snapshot().service().price());
        assertEquals(PaymentLedger.Status.REFUND_DUE,summary.status());assertEquals(Map.of("USD",800L),summary.refundDueByCurrency());
    }

    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void holdExpiryEqualityDecidesWhetherAReceiptIsLateByMicrosecond(long micros) {
        UUID id=pending("a",3,9);Instant expiry=expires(id);Instant captured=expiry.plusNanos(micros*1000);sessionAt(captured,60,60);
        var result=at(captured).recordReceipt(admin.binding(),id,"edge",new AdministratorFinance.ReceiptRequest(new Money(100,"EUR"),"bank","edge-"+micros,captured));
        assertTrue(result.success());
        assertEquals(micros<0?0:1,count("refund_obligation"));assertEquals(micros<0?Booking.State.PENDING_CONFIRMATION:Booking.State.EXPIRED,c.load(id).state());
    }

    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void verifiedTimeMayEqualButNotExceedTheCapturedTimeByAMicrosecond(long micros) {
        UUID id=pending("a",3,9);Instant captured=c.now().plusSeconds(60);sessionAt(captured,60,60);
        Receipt result=at(captured).recordReceipt(admin.binding(),id,"edge",new AdministratorFinance.ReceiptRequest(new Money(100,"EUR"),"bank","v",captured.plusNanos(micros*1000)));
        assertEquals(micros<=0?Code.OK:Code.INVALID_PAYMENT,result.code());assertEquals(micros<=0?1:0,count("payment_entry"));
    }

    @Test void duplicateReferenceAcrossBookingsIsAnExpectedFailureThatKeepsEarlierExpiryReclamation() {
        UUID first=pending("a",3,9),second=pending("b",4,9);assertTrue(record(first,"one",money(100,"EUR","shared-ref")).success());
        UUID due=c.f.database.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        Receipt duplicate=record(second,"two",money(100,"EUR","shared-ref"));
        assertEquals(Receipt.failure(Code.DUPLICATE_PAYMENT),duplicate);assertEquals(1,count("payment_entry"));
        assertEquals(Booking.State.EXPIRED,c.load(due).state());assertEquals(1,count("outbox_event WHERE type='EXPIRED'"));
        assertEquals(duplicate,record(second,"two",money(100,"EUR","shared-ref")));assertEquals(1,count("outbox_event WHERE type='EXPIRED'"));
        assertTrue(record(second,"three",money(100,"EUR","different-ref")).success());
    }

    @Test void unknownBookingFailsNotFoundWithoutAnyStoredReceipt() {
        assertEquals(Failure.Kind.NOT_FOUND,fail(()->record(UUID.randomUUID(),"x",money(1,"EUR","r"))).kind());
        assertEquals(0,count("idempotency_record"));assertEquals(0,count("payment_entry"));
    }

    @Test void onlyAFreshEnabledEnrolledAdminSessionAtTheCurrentEpochMayRecordAndNothingIsStoredOtherwise() {
        UUID id=pending("a",3,9);var request=money(100,"EUR","r");
        var guest=c.guest("guest@example.test");var account=c.account();
        assertEquals(Failure.Kind.FORBIDDEN,fail(()->finance.recordReceipt(c.binding(guest),id,"g",request)).kind());
        assertEquals(Failure.Kind.FORBIDDEN,fail(()->finance.recordReceipt(account.binding(),id,"a",request)).kind());
        try(var anonymous=c.sessions.anonymous()) { assertEquals(Failure.Kind.FORBIDDEN,fail(()->finance.recordReceipt(c.binding(anonymous.session()),id,"n",request)).kind()); }
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,fail(()->finance.recordReceipt(new SessionBinding(UUID.randomUUID(),admin.binding().browserHash()),id,"u",request)).kind());
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,fail(()->finance.recordReceipt(new SessionBinding(admin.binding().sessionId(),"0".repeat(64)),id,"w",request)).kind());
        for(String breakage:List.of("UPDATE app_session SET revoked_at=clock_timestamp()","UPDATE admin_user SET enabled=false","UPDATE admin_user SET enrolled=false",
                "UPDATE admin_user SET security_version=security_version+1","UPDATE admin_user SET password_hash='synthetic-rotated'","UPDATE admin_user SET totp_secret='synthetic-rotated'",
                "UPDATE app_session SET admin_security_version=admin_security_version+1")) {
            Admin fresh=admin;
            c.jdbc.update(breakage+(breakage.contains("app_session")?" WHERE id='"+fresh.binding().sessionId()+"'":""));
            assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,fail(()->record(id,"broken",request)).kind(),breakage);
            c.jdbc.update("DELETE FROM app_session WHERE kind='ADMIN'");c.jdbc.update("DELETE FROM admin_user");admin=admin();
        }
        assertEquals(0,count("payment_entry"));assertEquals(0,count("idempotency_record WHERE scope LIKE '%RECORD_RECEIPT%'"));assertEquals(0,count("outbox_event WHERE type LIKE 'PAYMENT%'"));
    }

    void place(Instant issued,Instant lastSeen,Instant authenticated,Instant expires) {
        c.jdbc.update("UPDATE app_session SET issued_at=?,last_seen_at=?,authenticated_at=?,absolute_expires_at=? WHERE id=?",
                Timestamp.from(issued),Timestamp.from(lastSeen),Timestamp.from(authenticated),Timestamp.from(expires),admin.binding().sessionId());
    }
    Failure.Kind denial(Instant captured,UUID id,String reference) {
        try {
            at(captured).recordReceipt(admin.binding(),id,reference,new AdministratorFinance.ReceiptRequest(new Money(100,"EUR"),"bank",reference,captured.minusSeconds(1)));
            return null;
        } catch(Failure failure) { return failure.kind(); }
    }

    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void adminIdleTimeoutIsStrictAtMicrosecondEquality(long micros) {
        UUID id=pending("a",3,9);Instant t=c.now().plusSeconds(3600);Instant seen=t.minusSeconds(900).minusNanos(micros*1000);
        place(seen.minusSeconds(60),seen,seen.minusSeconds(60),seen.minusSeconds(60).plusSeconds(28800));
        // Passing the idle guard still meets the stricter ten-minute MFA window because authentication cannot follow the last request.
        assertEquals(micros<0?Failure.Kind.FRESH_AUTHENTICATION_REQUIRED:Failure.Kind.AUTHENTICATION_REQUIRED,denial(t,id,"idle"));
        assertEquals(0,count("payment_entry"));
    }

    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void freshMfaWindowIsStrictAtMicrosecondEquality(long micros) {
        UUID id=pending("a",3,9);Instant t=c.now().plusSeconds(3600);Instant authenticated=t.minusSeconds(600).minusNanos(micros*1000);
        place(authenticated.minusSeconds(60),t.minusSeconds(1),authenticated,authenticated.minusSeconds(60).plusSeconds(28800));
        Failure.Kind kind=denial(t,id,"mfa");
        if(micros<0){assertNull(kind);assertEquals(1,count("payment_entry"));}
        else {assertEquals(Failure.Kind.FRESH_AUTHENTICATION_REQUIRED,kind);assertEquals(0,count("payment_entry"));}
    }

    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void absoluteLifetimeIsStrictAtMicrosecondEqualityDespiteRecentActivity(long micros) {
        UUID id=pending("a",3,9);Instant t=c.now().plusSeconds(3600);Instant expires=t.minusNanos(micros*1000);
        place(expires.minusSeconds(28800),t.minusSeconds(30),t.minusSeconds(60),expires);
        Failure.Kind kind=denial(t,id,"absolute");
        if(micros<0)assertNull(kind);else assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,kind);
        assertEquals(micros<0?1:0,count("payment_entry"));
    }

    @Test void sessionIssuedAfterTheCapturedTimeCannotAuthorize() {
        UUID id=pending("a",3,9);Instant t=c.now().plusSeconds(3600);
        place(t.plusNanos(1000),t.plusNanos(1000),t.plusNanos(1000),t.plusNanos(1000).plusSeconds(28800));
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,denial(t,id,"future"));
    }

    @Test void authorityIsRecheckedBeforeAStoredReceiptIsReplayed() {
        UUID id=pending("a",3,9);var request=money(100,"EUR","synthetic-replay");assertTrue(record(id,"replay",request).success());
        c.jdbc.update("UPDATE app_session SET revoked_at=clock_timestamp() WHERE id=?",admin.binding().sessionId());
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,fail(()->record(id,"replay",request)).kind());
        assertEquals(1,count("payment_entry"));
    }

    @Test void revocationWhileWaitingForTheAdministratorLockCannotAuthorize() throws Exception {
        UUID id=pending("a",3,9);String name="finance-lock-"+UUID.randomUUID();c.f.source.setApplicationName(name);
        try(var blocker=c.f.database.connection(MigrationTest.APP);var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);MigrationTest.execute(blocker,"SELECT id FROM admin_user WHERE id=? FOR UPDATE",admin.id());
            var future=pool.submit(()->fail(()->record(id,"blocked",money(100,"EUR","synthetic-blocked"))));boolean waiting=false;long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(System.nanoTime()<limit) {
                if(c.jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock'",Integer.class,name)>0){waiting=true;break;}
                Thread.sleep(10);
            }
            assertTrue(waiting);MigrationTest.execute(blocker,"UPDATE admin_user SET enabled=false WHERE id=?",admin.id());blocker.commit();
            assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,future.get(10,TimeUnit.SECONDS).kind());
        }
        assertEquals(0,count("payment_entry"));
    }

    @ParameterizedTest @ValueSource(strings={"payment_entry","audit_event","outbox_event","refund_obligation"})
    void unexpectedStorageFailureRollsBackTheFactEffectsReceiptAndEarlierReclamation(String table) throws Exception {
        UUID late=c.f.database.booking(MigrationTest.START,"EXPIRED",MigrationTest.snapshot()),live=pending("a",3,9);
        UUID due=c.f.database.booking(MigrationTest.START.plusSeconds(86400),"PENDING_CONFIRMATION",MigrationTest.snapshot());
        String where=table.equals("audit_event")||table.equals("outbox_event")?"NEW.type LIKE 'PAYMENT%'":"true";
        try(var connection=c.f.database.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(connection,"CREATE FUNCTION reject_fact() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF "+where+" THEN RAISE EXCEPTION 'SYNTHETIC_FAILURE'; END IF; RETURN NEW; END $$");
            MigrationTest.execute(connection,"CREATE TRIGGER reject_fact BEFORE INSERT ON "+table+" FOR EACH ROW EXECUTE FUNCTION reject_fact()");
        }
        UUID target=table.equals("refund_obligation")?late:live;
        assertEquals(Failure.Kind.STORAGE_FAILURE,fail(()->record(target,"fail",money(100,"EUR","synthetic-fail"))).kind());
        assertEquals(0,count("payment_entry"));assertEquals(0,count("idempotency_record WHERE scope LIKE '%RECORD_RECEIPT%'"));assertEquals(Booking.State.PENDING_CONFIRMATION,c.load(due).state());
        assertEquals(0,count("outbox_event WHERE type IN ('EXPIRED') OR type LIKE 'PAYMENT%'"));
    }

    @ParameterizedTest @ValueSource(strings={"no-effects","two-effects","never-loaded","failure-with-effects","wrong-booking"})
    void aCommittedFinancialCommandMustCarryExactlyOneFactAndNeverChangeTheBooking(String misuse) {
        UUID id=pending("a",3,9);Booking before=c.load(id);
        var command=new FinanceUnitOfWork.Command(admin.binding(),id,FinanceUnitOfWork.Operation.RECORD_RECEIPT,"misuse",CommandKey.fingerprint(List.of(misuse)));
        Function<FinanceUnitOfWork.Finance,Receipt> work=tx->{
            if(misuse.equals("never-loaded"))return new Receipt(Code.OK,UUID.randomUUID(),before.version());
            Booking booking=tx.authorizedBooking();
            var entry=new PaymentLedger.Entry(UUID.randomUUID(),misuse.equals("wrong-booking")?UUID.randomUUID():id,PaymentLedger.Kind.RECEIPT,new Money(100,"EUR"),"bank","misuse-"+misuse,tx.now(),null);
            switch(misuse) {
                case "no-effects" -> { }
                case "two-effects" -> { tx.receiptRecorded(entry,false);tx.receiptRecorded(new PaymentLedger.Entry(UUID.randomUUID(),id,PaymentLedger.Kind.RECEIPT,new Money(100,"EUR"),"bank","second-"+misuse,tx.now(),null),false); }
                case "failure-with-effects" -> { tx.receiptRecorded(entry,false);return Receipt.failure(Code.INVALID_TRANSITION); }
                default -> tx.receiptRecorded(entry,false);
            }
            return new Receipt(Code.OK,entry.id(),booking.version());
        };
        assertEquals(Failure.Kind.STORAGE_FAILURE,fail(()->adapter.execute(command,work)).kind());
        assertEquals(before,c.load(id));assertEquals(0,count("payment_entry"));assertEquals(0,count("idempotency_record WHERE scope LIKE '%RECORD_RECEIPT%' AND receipt->>'code'='OK'"));
    }

    @Test void maintenanceModeFailsClosedBeforeAnyAuthorityOrFact() {
        UUID id=pending("a",3,9);
        c.jdbc.update("UPDATE operation_control SET maintenance=true");
        assertEquals(Failure.Kind.MAINTENANCE,fail(()->record(id,"other",money(100,"EUR","synthetic-b"))).kind());
    }

    @Test void twentySameKeyReceiptsAcrossTwoInstancesCommitOneFactAndOneReceipt() throws Exception {
        UUID id=pending("a",3,9);var other=new AdministratorFinance(new PostgresFinance(c.f.source));var request=money(100,"EUR","synthetic-same");
        var results=race(i->(i%2==0?finance:other).recordReceipt(admin.binding(),id,"same",request));
        assertEquals(1,new HashSet<>(results).size());assertTrue(results.getFirst().success());
        assertEquals(1,count("payment_entry"));assertEquals(1,count("audit_event WHERE type='PAYMENT_RECORDED'"));assertEquals(1,count("outbox_event WHERE type='PAYMENT_RECORDED'"));
    }

    @Test void twentyDifferentKeysWithOneReferenceCommitExactlyOneFact() throws Exception {
        UUID id=pending("a",3,9);var other=new AdministratorFinance(new PostgresFinance(c.f.source));
        var results=race(i->(i%2==0?finance:other).recordReceipt(admin.binding(),id,"key-"+i,money(100,"EUR","synthetic-shared")));
        assertEquals(1,results.stream().filter(Receipt::success).count());assertEquals(19,results.stream().filter(r->r.code()==Code.DUPLICATE_PAYMENT).count());
        assertEquals(1,count("payment_entry"));
    }

    @Test void twentyDistinctReceiptsAllCommitWithDistinctAuditAndNoBookingChange() throws Exception {
        UUID id=pending("a",3,9);Booking before=c.load(id);var other=new AdministratorFinance(new PostgresFinance(c.f.source));
        var results=race(i->(i%2==0?finance:other).recordReceipt(admin.binding(),id,"key-"+i,money(100+i,"EUR","synthetic-"+i)));
        assertTrue(results.stream().allMatch(Receipt::success));assertEquals(20,results.stream().map(Receipt::entityId).distinct().count());
        assertEquals(20,count("payment_entry"));assertEquals(20,count("audit_event WHERE type='PAYMENT_RECORDED'"));assertEquals(before,c.load(id));
    }

    @Test void receiptRacingAHoldExpiryIsNeverAnOccupancyOrAConfirmationFact() throws Exception {
        UUID id=pending("a",3,9);c.jdbc.update("UPDATE booking SET expires_at=clock_timestamp()+interval '300 milliseconds' WHERE id=?",id);
        var results=race(i->{Thread.sleep(i*30L);return record(id,"key-"+i,money(100,"EUR","synthetic-race-"+i));});
        assertTrue(results.stream().allMatch(Receipt::success));
        Booking after=c.load(id);assertEquals(Booking.State.EXPIRED,after.state());
        // Whichever side of the expiry a receipt fell on, it has exactly one refund follow-up: system expiry or explicit reconciliation.
        assertEquals(20,count("payment_entry"));assertEquals(20,count("refund_obligation"));
        assertEquals(count("refund_obligation WHERE reason='LATE_RECEIPT'"),count("outbox_event WHERE type='PAYMENT_RECONCILIATION_REQUIRED'"));
        assertEquals(count("refund_obligation WHERE reason='EXPIRED'"),count("outbox_event WHERE type='PAYMENT_RECORDED'"));
        assertEquals(0,count("booking_occupancy WHERE released_at IS NULL"));
    }

    List<Receipt> race(Work work) throws Exception {
        var ready=new CountDownLatch(20);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<Future<Receipt>>();
            for(int i=0;i<20;i++){int index=i;futures.add(pool.submit(()->{ready.countDown();release.await();return work.apply(index);}));}
            assertTrue(ready.await(5,TimeUnit.SECONDS));release.countDown();var results=new ArrayList<Receipt>();for(var future:futures)results.add(future.get(90,TimeUnit.SECONDS));return results;
        }
    }
    interface Work { Receipt apply(int index) throws Exception; }
}
