package com.purpuragenda.adapters;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.adapters.in.rest.*;
import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.adapters.out.security.AdminCrypto;
import com.purpuragenda.application.*;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.*;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.customer.Intake;
import java.nio.file.Path;
import java.time.*;
import java.sql.*;
import javax.sql.DataSource;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real MFA, guarded finance HTTP, actual independent money and PostgreSQL transaction/concurrency invariants. */
@Timeout(240)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class AdministratorFinanceHttpPostgresTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String MEETING="https://zoom.us/j/123456789";
    @TempDir Path directory;
    AdministratorBookingHttpPostgresTest reads;AdministratorLoginPostgresTest login;AdminMfaDatabaseTest f;
    MockMvc mvc;FinanceUnitOfWork unit;PostgresScheduling scheduling;int booked;
    @BeforeAll static void start() { AdminMfaDatabaseTest.start(); }
    @AfterAll static void stop() { AdminMfaDatabaseTest.stop(); }
    @BeforeEach void setup() throws Exception {
        reads=new AdministratorBookingHttpPostgresTest();reads.directory=directory;reads.setup();login=reads.login;f=reads.f;
        scheduling=new PostgresScheduling(f.source);unit=new PostgresFinance(f.source,Set.of("zoom.us"));build(unit);
    }
    @AfterEach void cleanup() { reads.cleanup(); }
    void build(FinanceUnitOfWork store) {
        mvc=MockMvcBuilders.standaloneSetup(new SessionController(login.access,login.cookies),new AdministratorLoginController(
                new AdministratorLogin(new RequestThrottle(login.limits),f.mfa,login.sessions,new AdminCrypto()),login.cookies,Duration.ZERO),
                new AdministratorFinanceController(new AdministratorFinance(store,Set.of("zoom.us"))),
                new AdministratorBookingController(new AdministratorBookingView(new PostgresAdministratorBookingQuery(f.source,Set.of("zoom.us"))),login.access,login.cookies))
                .addFilters(new ApiGuardFilter(AdministratorLoginPostgresTest.ORIGIN,login.access,login.cookies,new ClientAddress(List.of()))).build();login.mvc=mvc;reads.mvc=mvc;
    }
    AdministratorLoginPostgresTest.Browser admin() throws Exception { return reads.administrator(); }
    UUID booking(boolean expired,boolean cancelled) {
        UUID customer=UUID.randomUUID(),id=UUID.randomUUID();f.jdbc.update("INSERT INTO customer_identity VALUES(?,?,clock_timestamp())",customer,customer+"@example.test");
        int hour=9+2*booked++;
        var key=new CommandKey(new Scope(UUID.randomUUID(),"SYNTHETIC_FINANCE_SETUP",id),"setup", "a".repeat(64));
        var setup=expired?new PostgresScheduling(clock(value->value.minus(Duration.ofHours(25)))):scheduling;
        setup.execute(key,tx->{
            var created=tx.now();
            var start=tx.now().atZone(ZoneId.of("Europe/Brussels")).toLocalDate().plusDays(3).atTime(hour,0).atZone(ZoneId.of("Europe/Brussels")).toInstant();
            var snapshot=new Booking.Snapshot(tx.services().getFirst(),tx.policy(),new Intake("Synthetic","Customer","+32400123456",LocalDate.of(1990,1,1),LocalTime.NOON,"Synthetic city",""),"synthetic@example.test","Europe/Brussels","local-v1");
            var pending=Booking.pending(id,"B-"+id,customer,snapshot,Booking.Origin.CUSTOMER,created,start);tx.save(pending);
            if(cancelled)tx.save(pending.cancel(tx.now(),false));return new Receipt(Code.OK,id,cancelled?2:1);
        });return id;
    }
    String path(UUID id,String action) { return "/api/v1/admin/bookings/"+id+action; }
    ObjectNode fact(long amount,String currency,String reference) { return JSON.createObjectNode().put("amountMinor",amount).put("currency",currency).put("method","SYNTHETIC").put("reference",reference).put("verifiedAt",f.now().minusSeconds(1).toString()); }
    MockHttpServletResponse send(AdministratorLoginPostgresTest.Browser admin,UUID id,String action,String key,ObjectNode body,String version) throws Exception {
        var request=login.json(admin,path(id,action),body.toString()).header("Idempotency-Key",key);if(version!=null)request.header("If-Match",version);return mvc.perform(request).andReturn().getResponse();
    }
    MockHttpServletResponse receipt(AdministratorLoginPostgresTest.Browser admin,UUID id,String key,long amount) throws Exception { return send(admin,id,"/receipts",key,fact(amount,"EUR",key),null); }
    ObjectNode confirmation(String meeting) { return JSON.createObjectNode().put("meetingUrl",meeting); }
    String state(UUID id) { return f.jdbc.queryForObject("SELECT state FROM booking WHERE id=?",String.class,id); }
    long version(UUID id) { return f.jdbc.queryForObject("SELECT version FROM booking WHERE id=?",Long.class,id); }
    int count(String table) { return f.count(table); }
    UUID entry(MockHttpServletResponse response) throws Exception { assertEquals(201,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(response.getHeader("ETag"));var body=JSON.readTree(response.getContentAsString());assertEquals(3,body.size());assertTrue(body.has("bookingId") && body.has("bookingVersion"));return UUID.fromString(body.get("paymentEntryId").textValue()); }

    @Test void manualApprovalDoesNotAssertPaymentAndLaterReceiptDoesNotChangeConfirmation() throws Exception {
        UUID id=booking(false,false);var admin=admin();var approved=send(admin,id,"/approve","approve",confirmation(MEETING),"\"1\"");assertEquals(200,approved.getStatus());assertEquals("\"2\"",approved.getHeader("ETag"));
        var before=JSON.readTree(reads.send(reads.privateGet(admin,path(id,""))).getContentAsString());assertEquals("UNPAID",before.at("/payment/status").asText());
        entry(receipt(admin,id,"partial",100));assertEquals("CONFIRMED",state(id));assertEquals(2,version(id));assertEquals(1,count("payment_entry"));
        assertEquals(200,send(admin,id,"/approve","approve",confirmation(MEETING),"\"1\"").getStatus());assertEquals(1,count("outbox_event WHERE type='APPROVED'"));
    }
    @Test void validReceiptsStayCommittedWhenExternalConfirmationIsInsufficientOrMeetingInvalid() throws Exception {
        UUID id=booking(false,false);var admin=admin();String expires=f.jdbc.queryForObject("SELECT expires_at::text FROM booking WHERE id=?",String.class,id);
        entry(receipt(admin,id,"first",100));login.problem(send(admin,id,"/confirm-external-payment","insufficient",confirmation(MEETING),"\"1\""),409,"PAYMENT_INSUFFICIENT");
        assertEquals(1,count("payment_entry"));assertEquals("PENDING_CONFIRMATION",state(id));
        long price=f.jdbc.queryForObject("SELECT price_minor FROM service WHERE id=?",Long.class,UUID.fromString(MigrationTest.SERVICE));entry(receipt(admin,id,"rest",price-100));
        login.problem(send(admin,id,"/confirm-external-payment","invalid-meeting",confirmation("https://example.test/j/123456789"),"\"1\""),400,"INVALID_MEETING");assertEquals(2,count("payment_entry"));
        assertEquals(expires,f.jdbc.queryForObject("SELECT expires_at::text FROM booking WHERE id=?",String.class,id));
        assertEquals(200,send(admin,id,"/confirm-external-payment","confirm",confirmation(MEETING),"\"1\"").getStatus());assertEquals("CONFIRMED",state(id));assertEquals(2,count("payment_entry"));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void lateActualMoneyRemainsRecordedWithoutRevivalAndRequiresReconciliation(boolean cancelled) throws Exception {
        UUID id=booking(!cancelled,cancelled);var admin=admin();entry(send(admin,id,"/receipts","late",fact(100_000_001,"USD","late"),null));
        assertEquals(cancelled?"CANCELLED":"EXPIRED",state(id));assertEquals(1,count("payment_entry WHERE currency='USD' AND amount_minor=100000001"));assertEquals(1,count("refund_obligation"));
        assertEquals(1,count("outbox_event WHERE type='PAYMENT_RECONCILIATION_REQUIRED'"));assertEquals(0,count("booking_occupancy WHERE released_at IS NULL"));assertEquals(0,count("request_charge"));
    }
    @Test void twentySameKeyRequestsRecordExactlyOneEntryAndCurrentRevocationPrecedesReplay() throws Exception {
        UUID id=booking(false,false);var admin=admin();ObjectNode body=fact(1500,"EUR","same-key");var start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<Future<UUID>>();for(int i=0;i<20;i++)futures.add(pool.submit(()->{start.await();return entry(send(admin,id,"/receipts","same",body,null));}));start.countDown();var ids=new HashSet<UUID>();for(var result:futures)ids.add(result.get(45,TimeUnit.SECONDS));assertEquals(1,ids.size());
        }
        assertEquals(1,count("payment_entry"));assertEquals(1,version(id));
        login.problem(send(admin,id,"/receipts","different-key",body,null),409,"DUPLICATE_PAYMENT");login.problem(send(admin,id,"/receipts","same",fact(1600,"EUR","same-key"),null),409,"IDEMPOTENCY_KEY_REUSED");
        login.sessions.revoke(admin.reference());login.problem(send(admin,id,"/receipts","same",body,null),401,"SESSION_REQUIRED");assertEquals(1,count("payment_entry"));
    }
    @Test void twentyRefundRequestsNeverExceedTheActualLinkedReceipt() throws Exception {
        UUID id=booking(false,false);var admin=admin();UUID paid=entry(receipt(admin,id,"paid",1000));var start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<Future<Integer>>();for(int i=0;i<20;i++){int index=i;futures.add(pool.submit(()->{var body=fact(100,"EUR","refund-"+index).put("receiptId",paid.toString());start.await();return send(admin,id,"/refunds","refund-"+index,body,null).getStatus();}));}
            start.countDown();int created=0,conflicts=0;for(var result:futures){int status=result.get(45,TimeUnit.SECONDS);if(status==201)created++;else if(status==409)conflicts++;else fail("Unexpected financial race status: "+status);}assertEquals(10,created);assertEquals(10,conflicts);
        }
        assertEquals(10,count("payment_entry WHERE kind='REFUND'"));assertEquals(1000,f.jdbc.queryForObject("SELECT sum(amount_minor) FROM payment_entry WHERE kind='REFUND'",Long.class));assertEquals(1,version(id));
    }
    @Test void refundCurrencyReceiptOwnershipAndExplicitRequirementAreCheckedWithoutChangingBooking() throws Exception {
        UUID id=booking(false,false);var admin=admin();UUID paid=entry(receipt(admin,id,"paid",1500));
        login.problem(send(admin,id,"/refunds","wrong-currency",fact(100,"USD","wrong-currency").put("receiptId",paid.toString()),null),400,"INVALID_REFUND");
        login.problem(send(admin,id,"/refunds","wrong-receipt",fact(100,"EUR","wrong-receipt").put("receiptId",UUID.randomUUID().toString()),null),400,"INVALID_REFUND");
        UUID other=booking(false,false),foreign=entry(receipt(admin,other,"foreign-receipt",100));
        login.problem(send(admin,id,"/refunds","foreign-receipt",fact(100,"EUR","foreign-refund").put("receiptId",foreign.toString()),null),400,"INVALID_REFUND");
        var reason=JSON.createObjectNode().put("receiptId",paid.toString()).put("reason","Synthetic provider cancellation");
        for(String key:List.of("required","required","another-key")) { var result=send(admin,id,"/refund-requirements",key,reason,null);assertEquals(200,result.getStatus());assertEquals(paid.toString(),JSON.readTree(result.getContentAsString()).get("receiptId").asText()); }
        assertEquals(1,count("refund_obligation"));assertEquals(1,count("outbox_event WHERE type='REFUND_REQUIRED'"));assertEquals(2,count("payment_entry"));assertEquals(1,version(id));
    }
    @ParameterizedTest @ValueSource(strings={"disabled","unenrolled","epoch","session"})
    void stalePersistedAuthorityCannotRecordMoneyAfterRealMfa(String change) throws Exception {
        UUID id=booking(false,false);var admin=admin();
        switch(change) { case "disabled"->f.jdbc.update("UPDATE admin_user SET enabled=false");case "unenrolled"->f.jdbc.update("UPDATE admin_user SET enrolled=false");case "epoch"->f.jdbc.update("UPDATE admin_user SET security_version=security_version+1");case "session"->login.sessions.revoke(admin.reference()); }
        login.problem(receipt(admin,id,"denied",100),401,"SESSION_REQUIRED");assertEquals(0,count("payment_entry"));
    }
    @Test void freshMfaIsRequiredAndAuthorityIsRecheckedAfterTheHttpGuard() throws Exception {
        UUID id=booking(false,false);var admin=admin();f.jdbc.update("UPDATE app_session SET issued_at=issued_at-interval '602 seconds',absolute_expires_at=absolute_expires_at-interval '602 seconds',authenticated_at=issued_at-interval '601 seconds' WHERE id=?",admin.reference().id());
        login.problem(receipt(admin,id,"not-fresh",100),403,"AUTHENTICATION_REQUIRED");assertEquals(0,count("payment_entry"));
        f.jdbc.update("UPDATE app_session SET authenticated_at=last_seen_at WHERE id=?",admin.reference().id());var changed=new AtomicBoolean();var actual=unit;
        build((command,work)->{if(changed.compareAndSet(false,true))f.jdbc.update("UPDATE admin_user SET security_version=security_version+1");return actual.execute(command,work);});
        login.problem(receipt(admin,id,"race",100),401,"SESSION_REQUIRED");assertTrue(changed.get());assertEquals(0,count("payment_entry"));
    }
    @Test void strictHttpGuardsAndVersionPreconditionsNeverProduceMoney() throws Exception {
        UUID id=booking(false,false);var admin=admin();String path=path(id,"/receipts");
        assertEquals(403,mvc.perform(post(path).contentType("application/json").content(fact(100,"EUR","origin").toString())).andReturn().getResponse().getStatus());
        var anonymous=login.boot();login.problem(send(anonymous,id,"/receipts","anonymous",fact(100,"EUR","anonymous"),null),403,"ADMIN_ACCESS_REQUIRED");
        login.problem(send(admin,id,"/approve","missing",confirmation(MEETING),null),428,"VERSION_REQUIRED");login.problem(send(admin,id,"/approve","stale",confirmation(MEETING),"\"2\""),412,"STALE_VERSION");
        login.problem(send(admin,id,"/receipts","unsupported-version",fact(100,"EUR","unsupported"),"\"1\""),400,"INVALID_REQUEST");
        login.problem(send(admin,id,"/receipts","future",fact(100,"EUR","future").put("verifiedAt",f.now().plusSeconds(3600).toString()),null),400,"INVALID_PAYMENT");assertEquals(0,count("payment_entry"));
    }
    @Test void unexpectedOutboxFailureRollsBackActualMoneyAndAuditBeforeSafeRetry() throws Exception {
        UUID id=booking(false,false);var admin=admin();int audits=count("audit_event");
        try(var connection=f.fixture.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(connection,"CREATE FUNCTION fail_finance_http() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.type='PAYMENT_RECORDED' THEN RAISE EXCEPTION 'SYNTHETIC_PRIVATE'; END IF; RETURN NEW; END $$");
            MigrationTest.execute(connection,"CREATE TRIGGER fail_finance_http BEFORE INSERT ON outbox_event FOR EACH ROW EXECUTE FUNCTION fail_finance_http()");
        }
        login.problem(receipt(admin,id,"retry",100),503,"TEMPORARILY_UNAVAILABLE");assertEquals(0,count("payment_entry"));assertEquals(audits,count("audit_event"));
        try(var connection=f.fixture.connection(MigrationTest.MIGRATOR)) { MigrationTest.execute(connection,"DROP TRIGGER fail_finance_http ON outbox_event"); }
        entry(receipt(admin,id,"retry",100));assertEquals(1,count("payment_entry"));
    }
    @Test void futureFactorTimeCannotBecomeAuthorizedWhenOnlyOrdinaryConcurrentActivityIsAllowed() throws Exception {
        UUID id=booking(false,false);var admin=admin();var actual=unit;
        build((command,work)->{
            f.jdbc.update("UPDATE app_session SET authenticated_at=t,last_seen_at=t FROM (SELECT clock_timestamp()+interval '1 hour' t) captured WHERE id=?",admin.reference().id());
            return actual.execute(command,work);
        });
        login.problem(receipt(admin,id,"future-factor",100),401,"SESSION_REQUIRED");assertEquals(0,count("payment_entry"));
    }
    @Test void aConcurrentRealSessionTouchAfterResourceTimeCaptureDoesNotInvalidateTheCommand() throws Exception {
        UUID id=booking(false,false);var admin=admin();var captured=new CountDownLatch(1);var touched=new CountDownLatch(1);var time=new AtomicReference<Instant>();var captures=new AtomicInteger();
        build(new PostgresFinance(clock(now->{
            captures.incrementAndGet();time.set(now);captured.countDown();
            try { if(!touched.await(10,TimeUnit.SECONDS))throw new AssertionError("Concurrent session touch did not finish"); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new AssertionError("Interrupted session interleaving"); }
            return now;
        }),Set.of("zoom.us")));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var result=pool.submit(()->receipt(admin,id,"concurrent-touch",100));assertTrue(captured.await(10,TimeUnit.SECONDS));
            try {
                assertTrue(login.sessions.resolve(admin.cookie().toCharArray()).isPresent());
                var activity=f.jdbc.queryForObject("SELECT last_seen_at FROM app_session WHERE id=?",(row,n)->row.getTimestamp(1).toInstant(),admin.reference().id());
                assertTrue(activity.isAfter(time.get()),"Concurrent activity must follow the command's captured resource time");
            } finally { touched.countDown(); }
            entry(result.get(20,TimeUnit.SECONDS));
        }
        assertEquals(1,captures.get());assertEquals(1,count("payment_entry"));assertEquals(1,version(id));
    }
    DataSource clock(UnaryOperator<Instant> capture) {
        return new DelegatingDataSource(f.source) {
            @Override public Connection getConnection() throws SQLException {
                var connection=super.getConnection();return GuestAccessDatabaseTest.proxy(Connection.class,(p,m,a)->{
                    Object statement=GuestAccessDatabaseTest.invoke(connection,m,a);if(!m.getName().equals("createStatement"))return statement;
                    return GuestAccessDatabaseTest.proxy(Statement.class,(sp,sm,sa)->{
                        Object rows=GuestAccessDatabaseTest.invoke(statement,sm,sa);
                        if(!sm.getName().equals("executeQuery") || !"SELECT clock_timestamp()".equals(sa[0]))return rows;
                        return GuestAccessDatabaseTest.proxy(ResultSet.class,(rp,rm,ra)->{
                            Object value=GuestAccessDatabaseTest.invoke(rows,rm,ra);
                            return rm.getName().equals("getTimestamp") && Integer.valueOf(1).equals(ra[0])?Timestamp.from(capture.apply(((Timestamp)value).toInstant())):value;
                        });
                    });
                });
            }
        };
    }
    @Test void unknownBookingNeverProducesAFinancialFact() throws Exception { var admin=admin();login.problem(receipt(admin,UUID.randomUUID(),"missing",100),404,"BOOKING_NOT_FOUND");assertEquals(0,count("payment_entry")); }
}
