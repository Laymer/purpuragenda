package com.purpuragenda.adapters;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.adapters.in.rest.*;
import com.purpuragenda.adapters.out.postgres.PostgresAdministratorBookingQuery;
import com.purpuragenda.application.AdministratorLogin;
import com.purpuragenda.application.RequestThrottle;
import com.purpuragenda.adapters.out.security.AdminCrypto;
import com.purpuragenda.application.port.out.AdministratorBookingQuery;
import com.purpuragenda.application.port.out.AdministratorBookingQuery.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.scheduling.AdministratorBookingView;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Real password/TOTP login precedes private HTTP reads; no fixture grants ordinary administrator HTTP access. */
@Timeout(180)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class AdministratorBookingHttpPostgresTest {
    static final String PATH="/api/v1/admin/bookings";
    static final JsonMapper JSON=JsonMapper.builder().build();
    @TempDir Path directory;
    AdministratorLoginPostgresTest login;AdminMfaDatabaseTest f;MockMvc mvc;AdministratorBookingQuery query;
    @BeforeAll static void start() { AdminMfaDatabaseTest.start(); }
    @AfterAll static void stop() { AdminMfaDatabaseTest.stop(); }
    @BeforeEach void setup() throws Exception {
        login=new AdministratorLoginPostgresTest();login.directory=directory;login.setup();f=login.f;
        query=new PostgresAdministratorBookingQuery(f.source,Set.of("zoom.us"));build(query);
    }
    @AfterEach void cleanup() { login.cleanup(); }
    void build(AdministratorBookingQuery reads) {
        mvc=MockMvcBuilders.standaloneSetup(new SessionController(login.access,login.cookies),new AdministratorLoginController(
                new AdministratorLogin(new RequestThrottle(login.limits),f.mfa,login.sessions,new AdminCrypto()),login.cookies,Duration.ZERO),
                new AdministratorBookingController(new AdministratorBookingView(reads),login.access,login.cookies))
                .addFilters(new ApiGuardFilter(AdministratorLoginPostgresTest.ORIGIN,login.access,login.cookies,new ClientAddress(List.of()))).build();login.mvc=mvc;
    }
    AdministratorLoginPostgresTest.Browser administrator() throws Exception {
        var anonymous=login.boot();var response=login.send(anonymous,AdministratorLoginPostgresTest.body(f.freshCode()));assertEquals(200,response.getStatus());
        String cookie=AdministratorLoginPostgresTest.value(response.getHeader("Set-Cookie"));
        return new AdministratorLoginPostgresTest.Browser(cookie,AdministratorLoginPostgresTest.csrf(response),login.sessions.inspect(cookie.toCharArray()).orElseThrow().reference());
    }
    MockHttpServletRequestBuilder privateGet(AdministratorLoginPostgresTest.Browser browser,String path) { return get(path).cookie(new Cookie("__Host-purpuragenda",browser.cookie())); }
    MockHttpServletRequestBuilder agenda(AdministratorLoginPostgresTest.Browser browser) { return privateGet(browser,PATH).param("from","2026-11-01").param("through","2026-11-30"); }
    MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception { return mvc.perform(request).andReturn().getResponse(); }
    UUID book(String state) { return f.fixture.booking(MigrationTest.START,state,MigrationTest.snapshot()); }

    @Test void actualMfaOwnerReadsPrivateIntakeOriginalSnapshotsCurrentContactAndIndependentMoney() throws Exception {
        UUID id=book("CONFIRMED");var admin=administrator();
        f.jdbc.update("UPDATE booking SET delivery_email='current@example.test',version=version+1 WHERE id=?",id);
        f.jdbc.update("UPDATE service SET name='Changed synthetic name',version=version+1 WHERE id=?",UUID.fromString(MigrationTest.SERVICE));
        UUID receipt=UUID.randomUUID();f.jdbc.update("INSERT INTO payment_entry(id,booking_id,kind,amount_minor,currency,method,reference,verified_at) VALUES(?,?,'RECEIPT',1500,'EUR','SYNTHETIC','synthetic-payment',clock_timestamp())",receipt,id);
        f.jdbc.update("INSERT INTO refund_obligation(receipt_id,due_minor,reason) VALUES(?,1500,'SYNTHETIC')",receipt);
        String seen=f.jdbc.queryForObject("SELECT last_seen_at::text FROM app_session WHERE id=?",String.class,admin.reference().id());
        var response=send(privateGet(admin,PATH+"/"+id));assertEquals(200,response.getStatus());assertEquals("\"2\"",response.getHeader("ETag"));assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(response.getHeader("Set-Cookie"));
        var body=JSON.readTree(response.getContentAsString());assertFalse(body.has("customerId") || body.has("adminId"));assertTrue("current@example.test".equals(body.get("deliveryEmail").textValue()));
        assertFalse("Changed synthetic name".equals(body.get("snapshot").get("service").get("name").textValue()));assertTrue(body.get("snapshot").get("intake").has("birthplace"));
        assertEquals("REFUND_DUE",body.get("payment").get("status").textValue());assertEquals(1,body.get("payment").get("entries").size());assertEquals(1,body.get("payment").get("refundObligations").size());
        var list=JSON.readTree(send(agenda(admin)).getContentAsString());var row=list.get("entries").get(0);assertFalse(row.has("intake") || row.has("deliveryEmail") || row.has("payment") || row.has("meetingUrl"));
        assertEquals(seen,f.jdbc.queryForObject("SELECT last_seen_at::text FROM app_session WHERE id=?",String.class,admin.reference().id()));
    }
    @ParameterizedTest @ValueSource(strings={"disabled","unenrolled","epoch","session"})
    void committedRevocationAfterRealLoginDeniesEveryPrivateRead(String type) throws Exception {
        UUID id=book("CONFIRMED");var admin=administrator();
        switch(type) {
            case "disabled" -> f.jdbc.update("UPDATE admin_user SET enabled=false");
            case "unenrolled" -> f.jdbc.update("UPDATE admin_user SET enrolled=false");
            case "epoch" -> f.jdbc.update("UPDATE admin_user SET security_version=security_version+1");
            case "session" -> login.sessions.revoke(admin.reference());
        }
        login.problem(send(privateGet(admin,PATH+"/"+id)),401,"SESSION_REQUIRED");login.problem(send(agenda(admin)),401,"SESSION_REQUIRED");
    }
    @Test void persistedAuthorityIsRecheckedWhenItChangesAfterCookieInspection() throws Exception {
        UUID id=book("CONFIRMED");var admin=administrator();var changed=new AtomicBoolean();
        build(new AdministratorBookingQuery() {
            public Page agenda(SessionBinding session,Window window,Cursor after,int limit) { return query.agenda(session,window,after,limit); }
            public Optional<Detail> detail(SessionBinding session,UUID bookingId) { if(changed.compareAndSet(false,true))f.jdbc.update("UPDATE admin_user SET security_version=security_version+1");return query.detail(session,bookingId); }
        });
        login.problem(send(privateGet(admin,PATH+"/"+id)),401,"SESSION_REQUIRED");assertTrue(changed.get());
    }
    @Test void noCookieAndAnonymousSessionCannotLearnIntakeOrUnknownBookingExistence() throws Exception {
        UUID id=book("CONFIRMED");var anonymous=login.boot();
        login.problem(send(get(PATH+"/"+id)),401,"SESSION_REQUIRED");
        for(UUID target:List.of(id,UUID.randomUUID()))login.problem(send(privateGet(anonymous,PATH+"/"+target)),403,"ADMIN_ACCESS_REQUIRED");
        login.problem(send(agenda(anonymous)),403,"ADMIN_ACCESS_REQUIRED");
    }
    @Test void cancelledHistoryUsesStableOpaqueCursorAndStrictCivilRange() throws Exception {
        var ids=new ArrayList<UUID>();for(int i=0;i<4;i++)ids.add(book("CANCELLED"));ids.sort(Comparator.comparing(UUID::toString));var admin=administrator();
        var first=JSON.readTree(send(agenda(admin).param("limit","2")).getContentAsString());assertEquals(2,first.get("entries").size());assertEquals(ids.get(0).toString(),first.get("entries").get(0).get("id").textValue());
        var second=JSON.readTree(send(agenda(admin).param("limit","2").param("cursor",first.get("nextCursor").textValue())).getContentAsString());assertEquals(ids.get(2).toString(),second.get("entries").get(0).get("id").textValue());assertTrue(second.get("nextCursor").isNull());
        login.problem(send(agenda(admin).param("cursor",first.get("nextCursor").textValue()+"=")),400,"INVALID_REQUEST");
        login.problem(send(privateGet(admin,PATH).param("from","2026-11-01").param("through","2026-12-02")),400,"INVALID_REQUEST");
    }
    @Test void unknownBookingAndMalformedCanonicalQueriesAreSafeAndNeverTouchSessionsOrEffects() throws Exception {
        var admin=administrator();String seen=f.jdbc.queryForObject("SELECT last_seen_at::text FROM app_session WHERE id=?",String.class,admin.reference().id());int audits=f.count("audit_event");
        login.problem(send(privateGet(admin,PATH+"/"+UUID.randomUUID())),404,"BOOKING_NOT_FOUND");
        for(String limit:List.of("01","0","101","1000",""))login.problem(send(agenda(admin).param("limit",limit)),400,"INVALID_REQUEST");
        login.problem(send(agenda(admin).param("limit","1","2")),400,"INVALID_REQUEST");login.problem(send(agenda(admin).param("customerId",UUID.randomUUID().toString())),400,"INVALID_REQUEST");
        login.problem(send(privateGet(admin,PATH+"/bad-id")),400,"INVALID_REQUEST");
        assertEquals(seen,f.jdbc.queryForObject("SELECT last_seen_at::text FROM app_session WHERE id=?",String.class,admin.reference().id()));assertEquals(audits,f.count("audit_event"));
        var empty=JSON.readTree(send(agenda(admin)).getContentAsString());assertEquals(0,empty.get("entries").size());assertTrue(empty.get("nextCursor").isNull());
    }
    @Test void queryStorageFailureAfterRealLoginIsPrivateRetryableAndNeverContainsProviderText() throws Exception {
        var admin=administrator();var unavailable=new DelegatingDataSource(f.source) { @Override public Connection getConnection() throws SQLException { throw new SQLException("SYNTHETIC_PRIVATE_PROVIDER_DETAIL"); } };
        build(new PostgresAdministratorBookingQuery(unavailable,Set.of("zoom.us")));var response=send(agenda(admin));login.problem(response,503,"TEMPORARILY_UNAVAILABLE");assertEquals("1",response.getHeader("Retry-After"));
    }
    @Test void elapsedPendingHoldIsProjectedWithoutReclamationAndConditionalHeadersNeverCachePrivateReads() throws Exception {
        UUID id=book("PENDING_CONFIRMATION");var admin=administrator();var response=send(privateGet(admin,PATH+"/"+id).header("If-None-Match","\"1\""));assertEquals(200,response.getStatus());
        var body=JSON.readTree(response.getContentAsString());assertTrue(body.get("pendingExpired").booleanValue());assertEquals("PENDING_CONFIRMATION",body.get("state").textValue());assertTrue(body.get("meetingUrl").isNull());
        assertEquals("PENDING_CONFIRMATION",f.jdbc.queryForObject("SELECT state FROM booking WHERE id=?",String.class,id));assertEquals(1,f.jdbc.queryForObject("SELECT version FROM booking WHERE id=?",Long.class,id));
    }
}
