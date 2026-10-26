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
    static final Set<String> HOSTS=Set.of("zoom.us");
    PostgresFinance adapter;AdministratorFinance finance;Admin admin;
    record Admin(SessionBinding binding,UUID id) { }
    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void setup() throws Exception {
        c.directory=directory;c.setup();adapter=new PostgresFinance(c.f.source,HOSTS);finance=new AdministratorFinance(adapter,HOSTS);admin=admin();
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
    AdministratorFinance at(Instant captured) { return new AdministratorFinance(new PostgresFinance(c.clock(captured,new AtomicInteger()),HOSTS),HOSTS); }

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
        UUID id=pending("a",3,9);var other=new AdministratorFinance(new PostgresFinance(c.f.source,HOSTS),HOSTS);var request=money(100,"EUR","synthetic-same");
        var results=race(i->(i%2==0?finance:other).recordReceipt(admin.binding(),id,"same",request));
        assertEquals(1,new HashSet<>(results).size());assertTrue(results.getFirst().success());
        assertEquals(1,count("payment_entry"));assertEquals(1,count("audit_event WHERE type='PAYMENT_RECORDED'"));assertEquals(1,count("outbox_event WHERE type='PAYMENT_RECORDED'"));
    }

    @Test void twentyDifferentKeysWithOneReferenceCommitExactlyOneFact() throws Exception {
        UUID id=pending("a",3,9);var other=new AdministratorFinance(new PostgresFinance(c.f.source,HOSTS),HOSTS);
        var results=race(i->(i%2==0?finance:other).recordReceipt(admin.binding(),id,"key-"+i,money(100,"EUR","synthetic-shared")));
        assertEquals(1,results.stream().filter(Receipt::success).count());assertEquals(19,results.stream().filter(r->r.code()==Code.DUPLICATE_PAYMENT).count());
        assertEquals(1,count("payment_entry"));
    }

    @Test void twentyDistinctReceiptsAllCommitWithDistinctAuditAndNoBookingChange() throws Exception {
        UUID id=pending("a",3,9);Booking before=c.load(id);var other=new AdministratorFinance(new PostgresFinance(c.f.source,HOSTS),HOSTS);
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

    static String url(int n) { return "https://zoom.us/j/"+(100000000+n); }
    AdministratorFinance.Confirmation confirmation(UUID id,String url) { return new AdministratorFinance.Confirmation(c.load(id).version(),url); }
    Receipt approve(UUID id,String key,String url) { return finance.approve(admin.binding(),id,key,confirmation(id,url)); }
    Receipt confirmPaid(UUID id,String key,String url) { return finance.confirmExternalPayment(admin.binding(),id,key,confirmation(id,url)); }

    @Test void manualApprovalConfirmsWithAMeetingAuditAndOutboxAndLeavesPaymentFactsUntouched() {
        UUID id=pending("a",3,9);Booking before=c.load(id);
        Receipt result=approve(id,"approve",url(1));
        assertEquals(new Receipt(Code.OK,id,before.version()+1),result);Booking after=c.load(id);
        assertEquals(Booking.State.CONFIRMED,after.state());assertEquals(Booking.ConfirmationBasis.MANUAL_APPROVAL,after.confirmationBasis());
        assertEquals(url(1),after.meeting().participantUrl());assertEquals(before.snapshot(),after.snapshot());assertEquals(before.start(),after.start());
        assertEquals(0,count("payment_entry"));assertEquals(0,count("refund_obligation"));assertEquals(1,count("booking_occupancy WHERE released_at IS NULL AND booking_id='"+id+"'"));
        assertEquals(1,count("audit_event WHERE type='APPROVED' AND actor_kind='ADMIN' AND version="+after.version()));assertEquals(1,count("outbox_event WHERE type='APPROVED'"));
        assertEquals(admin.id()+"/APPROVE_BOOKING/"+id,c.f.value("SELECT scope FROM idempotency_record WHERE scope LIKE '%APPROVE_BOOKING%'"));
        assertEquals(result,finance.approve(admin.binding(),id,"approve",new AdministratorFinance.Confirmation(before.version(),url(1))));
        assertEquals(1,count("outbox_event WHERE type='APPROVED'"));
        assertEquals(Code.IDEMPOTENCY_KEY_REUSED,finance.approve(admin.binding(),id,"approve",new AdministratorFinance.Confirmation(before.version(),url(2))).code());
        assertEquals(Code.INVALID_TRANSITION,finance.approve(admin.binding(),id,"again",new AdministratorFinance.Confirmation(after.version(),url(1))).code());
    }

    @Test void externalPaymentConfirmationNeedsNetSufficientMatchingCurrencyFundsAndAWinningReceiptStaysCommitted() {
        UUID id=pending("a",3,9);long price=c.load(id).snapshot().service().price().minor();
        assertEquals(Code.PAYMENT_INSUFFICIENT,confirmPaid(id,"none",url(1)).code());
        assertTrue(record(id,"usd",money(price*10,"USD","synthetic-usd")).success());
        assertTrue(record(id,"part",money(price-1,"EUR","synthetic-part")).success());
        assertEquals(Code.PAYMENT_INSUFFICIENT,confirmPaid(id,"short",url(1)).code());assertEquals(Booking.State.PENDING_CONFIRMATION,c.load(id).state());
        assertTrue(record(id,"rest",money(1,"EUR","synthetic-rest")).success());
        // The valid payment facts stay committed when the later confirmation fails for another reason.
        assertEquals(Code.INVALID_MEETING,confirmPaid(id,"bad","http://zoom.us/j/123456789").code());
        assertEquals(3,count("payment_entry"));assertEquals(Booking.State.PENDING_CONFIRMATION,c.load(id).state());
        Receipt ok=confirmPaid(id,"good",url(1));assertTrue(ok.success());
        Booking after=c.load(id);assertEquals(Booking.ConfirmationBasis.EXTERNAL_PAYMENT,after.confirmationBasis());
        assertEquals(1,count("audit_event WHERE type='CONFIRMED_EXTERNAL_PAYMENT'"));assertEquals(3,count("payment_entry"));
    }

    @Test void refundDueOrRefundedFundsCannotConfirmByPayment() {
        UUID id=pending("a",3,9);assertTrue(record(id,"r",money(500,"EUR","synthetic-r")).success());
        c.jdbc.update("INSERT INTO payment_entry(id,booking_id,kind,amount_minor,currency,method,reference,verified_at,receipt_id) SELECT ?,booking_id,'REFUND',500,'EUR','synthetic-bank','synthetic-refund',clock_timestamp(),id FROM payment_entry",UUID.randomUUID());
        assertEquals(Code.PAYMENT_INSUFFICIENT,confirmPaid(id,"refunded",url(1)).code());
        assertEquals(Booking.State.PENDING_CONFIRMATION,c.load(id).state());
    }

    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void approvalRequiresALiveHoldStrictlyBeforeExpiryAtMicrosecondEquality(long micros) {
        UUID id=pending("a",3,9);Instant captured=expires(id).plusNanos(micros*1000);sessionAt(captured,60,60);Booking before=c.load(id);
        Receipt result=at(captured).approve(admin.binding(),id,"edge",new AdministratorFinance.Confirmation(before.version(),url(1)));
        if(micros<0){assertTrue(result.success());assertEquals(Booking.State.CONFIRMED,c.load(id).state());}
        else {
            // The reclamation advanced the version, so the reviewed version is stale; the expired hold never revives.
            assertEquals(Code.STALE_VERSION,result.code());assertEquals(Booking.State.EXPIRED,c.load(id).state());
            assertEquals(1,count("outbox_event WHERE type='EXPIRED'"));assertEquals(0,count("outbox_event WHERE type='APPROVED'"));
            assertEquals(Code.INVALID_TRANSITION,at(captured).approve(admin.binding(),id,"edge2",new AdministratorFinance.Confirmation(before.version()+1,url(1))).code());
        }
    }

    @Test void staleVersionIsAnExpectedFailureThatKeepsEarlierExpiryReclamationAndTerminalBookingsNeverRevive() {
        UUID id=pending("a",3,9);Booking before=c.load(id);UUID due=c.f.database.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        assertEquals(Code.STALE_VERSION,finance.approve(admin.binding(),id,"stale",new AdministratorFinance.Confirmation(before.version()+1,url(1))).code());
        assertEquals(before,c.load(id));assertEquals(Booking.State.EXPIRED,c.load(due).state());assertEquals(1,count("outbox_event WHERE type='EXPIRED'"));
        UUID rejected=c.f.database.booking(MigrationTest.START.plusSeconds(86400),"REJECTED",MigrationTest.snapshot());
        assertEquals(Code.INVALID_TRANSITION,approve(rejected,"rejected",url(1)).code());assertEquals(Booking.State.REJECTED,c.load(rejected).state());
    }

    @Test void meetingMustBeValidAndUniqueAndSyntheticDomainsNeedExplicitLocalConfiguration() {
        UUID first=pending("a",3,9),second=pending("b",4,9);assertTrue(approve(first,"one",url(1)).success());
        for(String bad:List.of("http://zoom.us/j/123456789","https://zoom.us.example.test/j/123456789","https://user@zoom.us/j/123456789","https://127.0.0.1/j/123456789","https://zoom.us/j/12","https://zoom.us/start/123456789","https://zoom.us/j/123456789#x","https://meet.example.test/j/123456789"))
            assertEquals(Code.INVALID_MEETING,approve(second,"bad-"+bad.hashCode(),bad).code(),bad);
        assertEquals(Code.MEETING_IN_USE,approve(second,"dup",url(1)).code());
        assertEquals(Code.MEETING_IN_USE,approve(second,"dup-pwd",url(1)+"?pwd=abc").code());
        assertEquals(Booking.State.PENDING_CONFIRMATION,c.load(second).state());
        var local=Set.of("zoom.us","meet.example.test");var configured=new AdministratorFinance(new PostgresFinance(c.f.source,local),local);
        assertTrue(configured.approve(admin.binding(),second,"local",new AdministratorFinance.Confirmation(c.load(second).version(),"https://meet.example.test/j/123456780")).success());
    }

    @Test void aDuplicateMeetingIdRacingPastTheCheckBecomesABusinessReceiptNotAStorageError() {
        UUID first=pending("a",3,9),second=pending("b",4,9);assertTrue(approve(first,"one",url(1)).success());
        var scheduling=new PostgresScheduling(c.f.source);
        Receipt receipt=scheduling.execute(c.internal(),tx->tx.attempt(()->{
            Booking b=tx.booking(second).orElseThrow().approve(tx.now(),Meeting.parse(url(1),Set.of("zoom.us")),Booking.ConfirmationBasis.MANUAL_APPROVAL);
            tx.save(b);return new Receipt(Code.OK,second,b.version());
        }));
        assertEquals(Receipt.failure(Code.MEETING_IN_USE),receipt);assertEquals(Booking.State.PENDING_CONFIRMATION,c.load(second).state());
    }

    @Test void approvalFromGuestOrStaleMfaIsDeniedAndChangesNothing() {
        UUID id=pending("a",3,9);var guest=c.guest("g@example.test");Booking before=c.load(id);
        assertEquals(Failure.Kind.FORBIDDEN,fail(()->finance.approve(c.binding(guest),id,"g",new AdministratorFinance.Confirmation(before.version(),url(1)))).kind());
        Instant t=c.now().plusSeconds(3600);Instant authenticated=t.minusSeconds(600);
        place(authenticated.minusSeconds(60),t.minusSeconds(1),authenticated,authenticated.minusSeconds(60).plusSeconds(28800));
        Failure denied=fail(()->at(t).approve(admin.binding(),id,"stale-mfa",new AdministratorFinance.Confirmation(before.version(),url(1))));
        assertEquals(Failure.Kind.FRESH_AUTHENTICATION_REQUIRED,denied.kind());assertEquals(before,c.load(id));
        assertEquals(0,count("idempotency_record WHERE scope LIKE '%APPROVE_BOOKING%'"));
    }

    @ParameterizedTest @ValueSource(strings={"booking","audit_event","outbox_event","booking_occupancy"})
    void unexpectedStorageFailureRollsBackConfirmationEffectsReceiptAndReclamation(String table) throws Exception {
        UUID id=pending("a",3,9);Booking before=c.load(id);UUID due=c.f.database.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        String where=table.equals("audit_event")||table.equals("outbox_event")?"NEW.type='APPROVED'":table.equals("booking")?"NEW.state='CONFIRMED'":"true";
        try(var connection=c.f.database.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(connection,"CREATE FUNCTION reject_approval() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF "+where+" THEN RAISE EXCEPTION 'SYNTHETIC_FAILURE'; END IF; RETURN NEW; END $$");
            String event=table.equals("booking")||table.equals("booking_occupancy")?"UPDATE":"INSERT";
            MigrationTest.execute(connection,"CREATE TRIGGER reject_approval BEFORE "+event+" ON "+table+" FOR EACH ROW EXECUTE FUNCTION reject_approval()");
        }
        assertEquals(Failure.Kind.STORAGE_FAILURE,fail(()->approve(id,"fail",url(1))).kind());
        assertEquals(before,c.load(id));assertEquals(Booking.State.PENDING_CONFIRMATION,c.load(due).state());
        assertEquals(0,count("idempotency_record WHERE scope LIKE '%APPROVE_BOOKING%'"));assertEquals(0,count("outbox_event WHERE type IN ('APPROVED','EXPIRED')"));
    }

    @ParameterizedTest @ValueSource(strings={"no-effects","effects-without-save","double-effects","wrong-basis","receipt-operation","forged-original","wrong-version","foreign-entity"})
    void everyCommittedConfirmationMustCarryExactlyItsEffects(String misuse) {
        UUID id=pending("a",3,9);Booking before=c.load(id);
        var operation=misuse.equals("receipt-operation")?FinanceUnitOfWork.Operation.RECORD_RECEIPT:FinanceUnitOfWork.Operation.APPROVE_BOOKING;
        var command=new FinanceUnitOfWork.Command(admin.binding(),id,operation,"misuse",CommandKey.fingerprint(List.of(misuse)));
        Function<FinanceUnitOfWork.Finance,Receipt> work=tx->{
            Booking original=tx.authorizedBooking();Meeting meeting=Meeting.parse(url(7),HOSTS);
            Booking confirmed=original.approve(tx.now(),meeting,misuse.equals("wrong-basis")?Booking.ConfirmationBasis.EXTERNAL_PAYMENT:Booking.ConfirmationBasis.MANUAL_APPROVAL);
            switch(misuse) {
                case "no-effects" -> tx.save(confirmed);
                case "effects-without-save" -> tx.confirmedEffects(original,confirmed);
                case "double-effects" -> {tx.save(confirmed);tx.confirmedEffects(original,confirmed);tx.confirmedEffects(original,confirmed);}
                case "forged-original" -> {tx.save(confirmed);tx.confirmedEffects(original.deliveryContact("forged@example.test"),confirmed);}
                default -> {tx.save(confirmed);tx.confirmedEffects(original,confirmed);}
            }
            if(misuse.equals("wrong-version"))return new Receipt(Code.OK,id,confirmed.version()+1);
            return new Receipt(Code.OK,misuse.equals("foreign-entity")?UUID.randomUUID():id,confirmed.version());
        };
        assertEquals(Failure.Kind.STORAGE_FAILURE,fail(()->adapter.execute(command,work)).kind());
        assertEquals(before,c.load(id));assertEquals(0,count("outbox_event WHERE type='APPROVED'"));
    }

    @Test void aStoredConfirmationForAnotherEntityFailsClosedOnReplay() {
        UUID id=pending("a",3,9);Booking before=c.load(id);var request=new AdministratorFinance.Confirmation(before.version(),url(1));
        assertTrue(finance.approve(admin.binding(),id,"k",request).success());
        c.jdbc.update("UPDATE idempotency_record SET receipt=jsonb_set(receipt,'{entityId}',to_jsonb(?::text)) WHERE scope LIKE '%APPROVE_BOOKING%'",UUID.randomUUID().toString());
        assertEquals(Failure.Kind.STORAGE_FAILURE,fail(()->finance.approve(admin.binding(),id,"k",request)).kind());
    }

    @Test void twentySameKeyApprovalsAcrossTwoInstancesCommitOneConfirmationAndOneReceipt() throws Exception {
        UUID id=pending("a",3,9);var other=new AdministratorFinance(new PostgresFinance(c.f.source,HOSTS),HOSTS);var request=new AdministratorFinance.Confirmation(1,url(1));
        var results=race(i->(i%2==0?finance:other).approve(admin.binding(),id,"same",request));
        assertEquals(1,new HashSet<>(results).size());assertTrue(results.getFirst().success());
        assertEquals(1,count("outbox_event WHERE type='APPROVED'"));assertEquals(1,count("audit_event WHERE type='APPROVED'"));
    }

    @Test void twentyDifferentApprovalsAndPaymentConfirmationsOfOneBookingCommitAtMostOnce() throws Exception {
        UUID id=pending("a",3,9);assertTrue(record(id,"pay",money(100000,"EUR","synthetic-pay")).success());var other=new AdministratorFinance(new PostgresFinance(c.f.source,HOSTS),HOSTS);
        var results=race(i->{var who=i%2==0?finance:other;var request=new AdministratorFinance.Confirmation(1,url(i));
            return i<10?who.approve(admin.binding(),id,"a-"+i,request):who.confirmExternalPayment(admin.binding(),id,"c-"+i,request);});
        assertEquals(1,results.stream().filter(Receipt::success).count());
        assertTrue(results.stream().filter(r->!r.success()).allMatch(r->Set.of(Code.STALE_VERSION,Code.INVALID_TRANSITION).contains(r.code())));
        assertEquals(1,count("outbox_event WHERE type IN ('APPROVED','CONFIRMED_EXTERNAL_PAYMENT')"));assertEquals(1,count("payment_entry"));
    }

    @Test void twentyBookingsCannotShareOneMeetingEvenAcrossTwoInstances() throws Exception {
        var ids=new ArrayList<UUID>();for(int i=0;i<20;i++)ids.add(pending("m"+i,3+i,9));
        var other=new AdministratorFinance(new PostgresFinance(c.f.source,HOSTS),HOSTS);
        var results=race(i->(i%2==0?finance:other).approve(admin.binding(),ids.get(i),"share",new AdministratorFinance.Confirmation(1,url(1))));
        assertEquals(1,results.stream().filter(Receipt::success).count());assertEquals(19,results.stream().filter(r->r.code()==Code.MEETING_IN_USE).count());
        assertEquals(1,count("booking WHERE state='CONFIRMED'"));assertEquals(1,count("outbox_event WHERE type='APPROVED'"));
    }

    AdministratorFinance.RefundRequest refund(UUID receipt,long minor,String currency,String reference) {
        return new AdministratorFinance.RefundRequest(receipt,new Money(minor,currency),"synthetic-bank",reference,verified);
    }
    Receipt refund(UUID booking,String key,AdministratorFinance.RefundRequest request) { return finance.recordRefund(admin.binding(),booking,key,request); }
    PaymentLedger.Summary summary(UUID booking) {
        var seen=new PaymentLedger[1];
        adapter.execute(new FinanceUnitOfWork.Command(admin.binding(),booking,FinanceUnitOfWork.Operation.RECORD_RECEIPT,"inspect-"+UUID.randomUUID(),CommandKey.fingerprint(List.of("inspect"))),tx->{
            tx.authorizedBooking();seen[0]=tx.ledger();return Receipt.failure(Code.INVALID_TRANSITION);
        });
        return seen[0].summary(c.load(booking).snapshot().service().price());
    }
    UUID cancelledWithReceipt(String email,long amount,String reference) {
        var session=c.guest(email);UUID id=c.create(session,"create",c.request(3,9)).entityId();
        assertTrue(approve(id,"approve",url(Math.abs(reference.hashCode())%800000000)).success());
        UUID receipt=record(id,"receipt",money(amount,"EUR",reference)).entityId();
        assertTrue(new CustomerBookingChanges(new PostgresBookingChanges(c.f.source)).cancel(c.binding(session),id,"cancel",new CustomerBookingChanges.Cancellation(c.load(id).version())).success());
        return receipt;
    }
    UUID bookingOf(UUID receipt) { return UUID.fromString(c.f.value("SELECT booking_id FROM payment_entry WHERE id=?",receipt)); }

    @Test void refundsConsumeTheCustomerCancellationObligationUntilEverythingIsReturned() {
        UUID receipt=cancelledWithReceipt("a@example.test",1500,"synthetic-cancel");UUID id=bookingOf(receipt);Booking before=c.load(id);
        assertEquals(PaymentLedger.Status.REFUND_DUE,summary(id).status());assertEquals(Map.of("EUR",1500L),summary(id).refundDueByCurrency());
        Receipt first=refund(id,"r1",refund(receipt,500,"EUR","synthetic-refund-1"));
        assertTrue(first.success());assertEquals(before.version(),first.version());assertEquals(before,c.load(id));
        assertEquals(Map.of("EUR",1000L),summary(id).refundDueByCurrency());assertEquals(PaymentLedger.Status.REFUND_DUE,summary(id).status());
        assertEquals(first,refund(id,"r1",refund(receipt,500,"EUR","synthetic-refund-1")));assertEquals(1,count("payment_entry WHERE kind='REFUND'"));
        assertTrue(refund(id,"r2",refund(receipt,1000,"EUR","synthetic-refund-2")).success());
        assertEquals(PaymentLedger.Status.REFUNDED_EXTERNAL,summary(id).status());assertTrue(summary(id).refundDueByCurrency().isEmpty());
        assertEquals(2,count("audit_event WHERE type='REFUND_RECORDED' AND actor_kind='ADMIN'"));assertEquals(2,count("outbox_event WHERE type='REFUND_RECORDED'"));
        assertFalse(c.f.value("SELECT string_agg(metadata::text||reason,'') FROM audit_event WHERE type='REFUND_RECORDED'").contains("synthetic-refund"));
        assertEquals(1,count("refund_obligation"));
        assertEquals(Code.REFUND_EXCEEDS_RECEIPT,refund(id,"r3",refund(receipt,1,"EUR","synthetic-refund-3")).code());
        assertEquals(Code.IDEMPOTENCY_KEY_REUSED,refund(id,"r1",refund(receipt,501,"EUR","synthetic-refund-1")).code());
    }

    @Test void refundsConsumeLateReceiptObligationsForExpiredBookings() {
        UUID due=c.f.database.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        UUID receipt=record(due,"late",money(800,"USD","synthetic-late")).entityId();
        assertEquals(Map.of("USD",800L),summary(due).refundDueByCurrency());
        assertTrue(refund(due,"r",refund(receipt,800,"USD","synthetic-late-refund")).success());
        assertEquals(PaymentLedger.Status.REFUNDED_EXTERNAL,summary(due).status());assertEquals(Booking.State.EXPIRED,c.load(due).state());
    }

    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void aRefundMayNeverExceedWhatItsReceiptStillHoldsAtOneMinorUnit(long delta) {
        UUID id=pending("a",3,9);UUID receipt=record(id,"receipt",money(1000,"EUR","synthetic-r")).entityId();
        assertTrue(refund(id,"first",refund(receipt,400,"EUR","synthetic-1")).success());
        Receipt result=refund(id,"second",refund(receipt,600+delta,"EUR","synthetic-2"));
        assertEquals(delta<=0?Code.OK:Code.REFUND_EXCEEDS_RECEIPT,result.code());
        long refunded=Long.parseLong(c.f.value("SELECT sum(amount_minor) FROM payment_entry WHERE kind='REFUND'"));
        assertEquals(delta<=0?1000+delta:400,refunded);assertTrue(refunded<=1000);
    }

    @Test void currencyReceiptKindAndBookingMustMatchExactlyWithoutInferenceOrConversion() {
        UUID id=pending("a",3,9),other=pending("b",4,9);UUID eur=record(id,"eur",money(1000,"EUR","synthetic-eur")).entityId();
        UUID usd=record(id,"usd",money(1000,"USD","synthetic-usd")).entityId();UUID foreign=record(other,"foreign",money(1000,"EUR","synthetic-foreign")).entityId();
        assertEquals(Code.INVALID_REFUND,refund(id,"k1",refund(eur,100,"USD","synthetic-x1")).code());
        assertEquals(Code.INVALID_REFUND,refund(id,"k2",refund(usd,100,"EUR","synthetic-x2")).code());
        assertEquals(Code.INVALID_REFUND,refund(id,"k3",refund(UUID.randomUUID(),100,"EUR","synthetic-x3")).code());
        assertEquals(Code.INVALID_REFUND,refund(id,"k4",refund(foreign,100,"EUR","synthetic-x4")).code());
        assertTrue(refund(id,"ok",refund(eur,100,"EUR","synthetic-ok")).success());
        UUID refundId=UUID.fromString(c.f.value("SELECT id FROM payment_entry WHERE kind='REFUND'"));
        assertEquals(Code.INVALID_REFUND,refund(id,"k5",refund(refundId,10,"EUR","synthetic-x5")).code());
        assertEquals(1,count("payment_entry WHERE kind='REFUND'"));assertEquals(0,count("refund_obligation"));
    }

    @Test void duplicateReferencesFutureFactsAndTerminalBookingsAreHandledAsIndependentMoneyFacts() {
        UUID first=pending("a",3,9),second=pending("b",4,9);
        UUID r1=record(first,"r1",money(500,"EUR","synthetic-1")).entityId(),r2=record(second,"r2",money(500,"EUR","synthetic-2")).entityId();
        assertTrue(refund(first,"a",refund(r1,100,"EUR","shared-refund")).success());
        assertEquals(Code.DUPLICATE_PAYMENT,refund(second,"b",refund(r2,100,"EUR","shared-refund")).code());
        // A refund reference does not collide with a receipt reference.
        assertTrue(refund(second,"c",refund(r2,100,"EUR","synthetic-1")).success());
        Instant t=c.now().plusSeconds(3600);sessionAt(t,60,60);
        for(long micros:new long[]{0,1}) {
            Receipt result=at(t).recordRefund(admin.binding(),second,"future-"+micros,new AdministratorFinance.RefundRequest(r2,new Money(10,"EUR"),"bank","edge-"+micros,t.plusNanos(micros*1000)));
            assertEquals(micros==0?Code.OK:Code.INVALID_REFUND,result.code());
        }
    }

    @Test void explicitRefundRequirementIsIdempotentAuditedAndOnlyForRealReceiptsOfTheBooking() {
        UUID id=pending("a",3,9),other=pending("b",4,9);UUID receipt=record(id,"r",money(900,"EUR","synthetic-r")).entityId();UUID foreign=record(other,"f",money(900,"EUR","synthetic-f")).entityId();
        var requirement=new AdministratorFinance.RefundRequirement(receipt,"Provider cancelled the session");
        Receipt first=finance.requireRefund(admin.binding(),id,"req",requirement);
        assertEquals(new Receipt(Code.OK,receipt,c.load(id).version()),first);
        assertEquals("900 ADMIN_REQUIRED",c.f.value("SELECT due_minor||' '||reason FROM refund_obligation WHERE receipt_id=?",receipt));
        assertEquals(1,count("audit_event WHERE type='REFUND_REQUIRED' AND reason='Provider cancelled the session'"));assertEquals(1,count("outbox_event WHERE type='REFUND_REQUIRED'"));
        assertEquals(PaymentLedger.Status.REFUND_DUE,summary(id).status());
        assertEquals(first,finance.requireRefund(admin.binding(),id,"req",requirement));
        assertTrue(finance.requireRefund(admin.binding(),id,"again",requirement).success());
        assertEquals(1,count("refund_obligation WHERE receipt_id='"+receipt+"'"));assertEquals(1,count("audit_event WHERE type='REFUND_REQUIRED'"));
        assertEquals(Code.INVALID_REFUND,finance.requireRefund(admin.binding(),id,"foreign",new AdministratorFinance.RefundRequirement(foreign,"Provider cancelled the session")).code());
        assertTrue(refund(id,"full",refund(receipt,900,"EUR","synthetic-full")).success());
        assertEquals(PaymentLedger.Status.REFUNDED_EXTERNAL,summary(id).status());
    }

    @Test void refundCommandsRequireFreshAdministratorAuthorityAndStayIndependentOfBookingVersions() {
        UUID id=pending("a",3,9);UUID receipt=record(id,"r",money(900,"EUR","synthetic-r")).entityId();var guest=c.guest("g@example.test");
        assertEquals(Failure.Kind.FORBIDDEN,fail(()->finance.recordRefund(c.binding(guest),id,"g",refund(receipt,1,"EUR","synthetic-g"))).kind());
        assertEquals(Failure.Kind.FORBIDDEN,fail(()->finance.requireRefund(c.binding(guest),id,"g",new AdministratorFinance.RefundRequirement(receipt,"Provider cancelled the session"))).kind());
        Instant t=c.now().plusSeconds(3600);Instant authenticated=t.minusSeconds(600);
        place(authenticated.minusSeconds(60),t.minusSeconds(1),authenticated,authenticated.minusSeconds(60).plusSeconds(28800));
        assertEquals(Failure.Kind.FRESH_AUTHENTICATION_REQUIRED,fail(()->at(t).recordRefund(admin.binding(),id,"stale",refund(receipt,1,"EUR","synthetic-s"))).kind());
        assertEquals(0,count("payment_entry WHERE kind='REFUND'"));assertEquals(0,count("refund_obligation"));
        // The booking changing version between the money facts never blocks them.
        c.jdbc.update("UPDATE booking SET delivery_email='moved@example.test',version=version+1 WHERE id=?",id);
        sessionAt(c.now(),60,60);assertTrue(refund(id,"after-change",refund(receipt,1,"EUR","synthetic-after")).success());
    }

    @ParameterizedTest @ValueSource(strings={"payment_entry","audit_event","outbox_event","refund_obligation"})
    void unexpectedStorageFailureRollsBackRefundFactsEffectsAndReclamation(String table) throws Exception {
        UUID id=pending("a",3,9);UUID receipt=record(id,"r",money(900,"EUR","synthetic-r")).entityId();
        UUID due=c.f.database.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        String where=table.equals("audit_event")||table.equals("outbox_event")?"NEW.type LIKE 'REFUND%'":table.equals("payment_entry")?"NEW.kind='REFUND'":"true";
        try(var connection=c.f.database.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(connection,"CREATE FUNCTION reject_refund() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF "+where+" THEN RAISE EXCEPTION 'SYNTHETIC_FAILURE'; END IF; RETURN NEW; END $$");
            MigrationTest.execute(connection,"CREATE TRIGGER reject_refund BEFORE INSERT ON "+table+" FOR EACH ROW EXECUTE FUNCTION reject_refund()");
        }
        Failure.Kind kind=fail(()->{
            if(table.equals("refund_obligation"))finance.requireRefund(admin.binding(),id,"fail",new AdministratorFinance.RefundRequirement(receipt,"Provider cancelled the session"));
            else refund(id,"fail",refund(receipt,10,"EUR","synthetic-fail"));
        }).kind();
        assertEquals(Failure.Kind.STORAGE_FAILURE,kind);
        assertEquals(0,count("payment_entry WHERE kind='REFUND'"));assertEquals(0,count("refund_obligation"));assertEquals(Booking.State.PENDING_CONFIRMATION,c.load(due).state());
        assertEquals(0,count("idempotency_record WHERE scope LIKE '%REFUND%'"));assertEquals(0,count("outbox_event WHERE type LIKE 'REFUND%' OR type='EXPIRED'"));
    }

    @ParameterizedTest @ValueSource(strings={"wrong-operation","double-refund","refund-kind","foreign-booking","require-wrong-operation","require-foreign-receipt","require-double"})
    void refundEffectMisuseFailsClosedAndLeavesNoRows(String misuse) {
        UUID id=pending("a",3,9),other=pending("b",4,9);UUID receipt=record(id,"r",money(900,"EUR","synthetic-r")).entityId();UUID foreign=record(other,"f",money(900,"EUR","synthetic-f")).entityId();
        boolean require=misuse.startsWith("require");
        var operation=misuse.equals("wrong-operation")?FinanceUnitOfWork.Operation.RECORD_RECEIPT:misuse.equals("require-wrong-operation")?FinanceUnitOfWork.Operation.RECORD_REFUND
                :require?FinanceUnitOfWork.Operation.REQUIRE_REFUND:FinanceUnitOfWork.Operation.RECORD_REFUND;
        var command=new FinanceUnitOfWork.Command(admin.binding(),id,operation,"misuse",CommandKey.fingerprint(List.of(misuse)));
        Function<FinanceUnitOfWork.Finance,Receipt> work=tx->{
            Booking booking=tx.authorizedBooking();
            if(require) {
                tx.refundRequired(misuse.equals("require-foreign-receipt")?foreign:receipt,"Provider cancelled the session");
                if(misuse.equals("require-double"))tx.refundRequired(receipt,"Provider cancelled the session");
                return new Receipt(Code.OK,receipt,booking.version());
            }
            var entry=new PaymentLedger.Entry(UUID.randomUUID(),misuse.equals("foreign-booking")?other:id,misuse.equals("refund-kind")?PaymentLedger.Kind.RECEIPT:PaymentLedger.Kind.REFUND,
                    new Money(10,"EUR"),"bank","misuse-"+misuse,tx.now(),misuse.equals("refund-kind")?null:receipt);
            tx.refundRecorded(entry);if(misuse.equals("double-refund"))tx.refundRecorded(entry);
            return new Receipt(Code.OK,entry.id(),booking.version());
        };
        assertEquals(Failure.Kind.STORAGE_FAILURE,fail(()->adapter.execute(command,work)).kind());
        assertEquals(0,count("payment_entry WHERE kind='REFUND'"));assertEquals(0,count("refund_obligation"));
    }

    @Test void aCommittedRefundCommandWithNoEffectIsRejectedExceptForTheIdempotentRequirement() {
        UUID id=pending("a",3,9);var command=new FinanceUnitOfWork.Command(admin.binding(),id,FinanceUnitOfWork.Operation.RECORD_REFUND,"none",CommandKey.fingerprint(List.of("none")));
        assertEquals(Failure.Kind.STORAGE_FAILURE,fail(()->adapter.execute(command,tx->new Receipt(Code.OK,tx.authorizedBooking().id(),tx.authorizedBooking().version()))).kind());
    }

    @Test void aDifferentUniqueViolationInsideTheAttemptIsNotReportedAsAMeetingConflict() {
        UUID id=pending("a",3,9);var scheduling=new PostgresScheduling(c.f.source);
        Failure failure=fail(()->scheduling.execute(c.internal(),tx->tx.attempt(()->{
            for(int i=0;i<2;i++)c.jdbc.update("INSERT INTO payment_entry(id,booking_id,kind,amount_minor,currency,method,reference,verified_at) VALUES(?,?,'RECEIPT',1,'EUR','synthetic-bank','synthetic-duplicate',clock_timestamp())",UUID.randomUUID(),id);
            return new Receipt(Code.OK,id,1);
        })));
        assertEquals(Failure.Kind.STORAGE_FAILURE,failure.kind());assertEquals(0,count("payment_entry"));
    }

    @Test void twentyRefundsRacingForOneReceiptNeverExceedItAcrossTwoInstances() throws Exception {
        UUID id=pending("a",3,9);UUID receipt=record(id,"r",money(1500,"EUR","synthetic-r")).entityId();var other=new AdministratorFinance(new PostgresFinance(c.f.source,HOSTS),HOSTS);
        var results=race(i->(i%2==0?finance:other).recordRefund(admin.binding(),id,"key-"+i,refund(receipt,100,"EUR","synthetic-refund-"+i)));
        assertEquals(15,results.stream().filter(Receipt::success).count());assertEquals(5,results.stream().filter(r->r.code()==Code.REFUND_EXCEEDS_RECEIPT).count());
        assertEquals("1500",c.f.value("SELECT sum(amount_minor) FROM payment_entry WHERE kind='REFUND'"));assertEquals(15,count("audit_event WHERE type='REFUND_RECORDED'"));
    }

    @Test void twentyRefundsRacingForTheLastMinorUnitsCommitExactlyTheRemainder() throws Exception {
        UUID id=pending("a",3,9);UUID receipt=record(id,"r",money(7,"EUR","synthetic-r")).entityId();
        var results=race(i->finance.recordRefund(admin.binding(),id,"key-"+i,refund(receipt,1,"EUR","synthetic-refund-"+i)));
        assertEquals(7,results.stream().filter(Receipt::success).count());assertEquals("7",c.f.value("SELECT sum(amount_minor) FROM payment_entry WHERE kind='REFUND'"));
    }

    @Test void twentySameKeyRefundsAndTwentySameReferenceRefundsCommitOneFact() throws Exception {
        UUID id=pending("a",3,9);UUID receipt=record(id,"r",money(1500,"EUR","synthetic-r")).entityId();var other=new AdministratorFinance(new PostgresFinance(c.f.source,HOSTS),HOSTS);
        var same=race(i->(i%2==0?finance:other).recordRefund(admin.binding(),id,"same",refund(receipt,100,"EUR","synthetic-same")));
        assertEquals(1,new HashSet<>(same).size());assertTrue(same.getFirst().success());assertEquals(1,count("payment_entry WHERE kind='REFUND'"));
        var shared=race(i->(i%2==0?finance:other).recordRefund(admin.binding(),id,"key-"+i,refund(receipt,100,"EUR","synthetic-shared")));
        assertEquals(1,shared.stream().filter(Receipt::success).count());assertEquals(19,shared.stream().filter(r->r.code()==Code.DUPLICATE_PAYMENT).count());
        assertEquals(2,count("payment_entry WHERE kind='REFUND'"));
    }

    @Test void twentyRequirementsAndRefundsRacingKeepOneObligationAndTheLedgerBound() throws Exception {
        UUID id=pending("a",3,9);UUID receipt=record(id,"r",money(1000,"EUR","synthetic-r")).entityId();var other=new AdministratorFinance(new PostgresFinance(c.f.source,HOSTS),HOSTS);
        var requirement=new AdministratorFinance.RefundRequirement(receipt,"Provider cancelled the session");
        var results=race(i->i%2==0?(i%4==0?finance:other).requireRefund(admin.binding(),id,"req-"+i,requirement):finance.recordRefund(admin.binding(),id,"ref-"+i,refund(receipt,100,"EUR","synthetic-race-"+i)));
        assertTrue(results.stream().allMatch(r->r.success() || r.code()==Code.REFUND_EXCEEDS_RECEIPT));
        assertEquals(1,count("refund_obligation"));assertEquals(1,count("audit_event WHERE type='REFUND_REQUIRED'"));
        assertEquals("1000",c.f.value("SELECT sum(amount_minor) FROM payment_entry WHERE kind='REFUND'"));
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
