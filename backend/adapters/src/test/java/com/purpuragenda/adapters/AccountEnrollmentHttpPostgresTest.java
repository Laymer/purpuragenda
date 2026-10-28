package com.purpuragenda.adapters;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.adapters.in.rest.*;
import com.purpuragenda.adapters.out.security.*;
import com.purpuragenda.application.*;
import com.purpuragenda.application.port.out.AuthDeliveryStore;
import com.purpuragenda.application.port.out.SessionStore.Kind;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real ACCOUNT email delivery, enrollment, Argon2 login and guarded HTTP; no authentication bypass. */
@Timeout(240)
@ResourceLock("postgres-migration-fixture")
class AccountEnrollmentHttpPostgresTest {
    static final String PROOFS="/api/v1/account/enrollment-proofs",ENROLL="/api/v1/account/enrollments",EMAIL="new-account@example.test";
    final AccountHttpPostgresTest h=new AccountHttpPostgresTest();@TempDir Path directory;
    @BeforeAll static void start() { AccountHttpPostgresTest.start(); }
    @AfterAll static void stop() { AccountHttpPostgresTest.stop(); }
    @BeforeEach void setup() throws Exception {
        h.directory=directory;h.setup();var throttle=new RequestThrottle(new PostgresRateLimits(h.a.e.c.f.source,new RateKey(directory.resolve("account-rate.key"),"account-test-v1")));
        var proofs=new AccountEnrollmentProofs(throttle,h.a.e.c.f.delivery);var enrollment=new AccountEnrollmentRequest(new AccountEnrollment(new PostgresAccountEnrollment(h.a.e.c.f.source),new Argon2PasswordHash()),throttle);
        h.mvc=MockMvcBuilders.standaloneSetup(new SessionController(h.access,h.cookies),new AccountController(h.login,h.passwords,h.cookies,Duration.ZERO),new AccountEnrollmentController(proofs,enrollment,Duration.ZERO))
                .addFilters(new ApiGuardFilter(AccountHttpPostgresTest.ORIGIN,h.access,h.cookies,new ClientAddress(List.of()))).build();
    }
    @AfterEach void cleanup() { h.cleanup(); }
    MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception { return h.mvc.perform(request).andReturn().getResponse(); }
    String email(String value) { return JsonMapper.builder().build().createObjectNode().put("email",value).toString(); }
    String body(String token) { return JsonMapper.builder().build().createObjectNode().put("token",token).put("password",new String(AccountEnrollmentDatabaseTest.password())).toString(); }
    String deliveredToken() {
        var f=h.a.e.c.f;var lease=f.queue.claimAuth().orElseThrow();var message=f.delivery.prepare(lease).orElseThrow();assertTrue(f.queue.sent(lease));return message.text().split("#token=",2)[1].split("&",2)[0];
    }
    String proof(AccountHttpPostgresTest.Browser browser,String address) throws Exception { var response=h.send(browser,PROOFS,email(address));assertEquals(202,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(response.getHeader("Set-Cookie"));return deliveredToken(); }
    @Test void enrollmentIsExplicitSingleUseAndRequiresSeparateRealPasswordLogin() throws Exception {
        var browser=h.boot();int accounts=h.count("customer_account");String token=proof(browser,EMAIL);assertEquals(accounts,h.count("customer_account"));
        assertEquals(405,send(get(ENROLL).cookie(new Cookie(h.cookies.name(),browser.cookie()))).getStatus());assertEquals(accounts,h.count("customer_account"));
        var response=h.send(browser,ENROLL,body(token));assertEquals(204,response.getStatus());assertEquals("",response.getContentAsString());assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(response.getHeader("Set-Cookie"));assertEquals(accounts+1,h.count("customer_account"));
        assertEquals(Kind.ANONYMOUS,h.a.sessions.inspect(browser.cookie().toCharArray()).orElseThrow().kind());
        h.problem(h.send(browser,ENROLL,body(token)),400,"PROOF_INVALID");assertEquals(accounts+1,h.count("customer_account"));
        var login=h.send(browser,AccountHttpPostgresTest.LOGIN,AccountHttpPostgresTest.loginBody(EMAIL,new String(AccountEnrollmentDatabaseTest.password())));assertEquals(200,login.getStatus());var account=h.browser(login);assertEquals(Kind.ACCOUNT,h.a.sessions.inspect(account.cookie().toCharArray()).orElseThrow().kind());assertTrue(h.a.sessions.inspect(browser.cookie().toCharArray()).isEmpty());
    }
    @Test void wrongBrowserWrongPurposeAndExpiryNeverConsumeEnrollmentAuthority() throws Exception {
        var browser=h.boot();var other=h.boot();String token=proof(browser,EMAIL);h.problem(h.send(other,ENROLL,body(token)),400,"PROOF_INVALID");
        assertEquals(204,h.send(browser,ENROLL,body(token)).getStatus());
        var reference=h.a.sessions.inspect(browser.cookie().toCharArray()).orElseThrow().reference();
        h.a.e.c.f.delivery.issue(new AuthDeliveryStore.Issue("guest-purpose@example.test",AuthDeliveryStore.Purpose.CREATE,null,reference.browserHash()));String guest=deliveredToken();
        h.problem(h.send(browser,ENROLL,body(guest)),400,"PROOF_INVALID");assertEquals(0,h.count("customer_account WHERE login_email='guest-purpose@example.test'"));
        String expired=proof(browser,"expired@example.test");h.a.e.c.jdbc.update("UPDATE email_challenge SET expires_at=expires_at-interval '16 minutes' WHERE email_key='expired@example.test'");
        h.problem(h.send(browser,ENROLL,body(expired)),400,"PROOF_INVALID");assertEquals(0,h.count("customer_account WHERE login_email='expired@example.test'"));
    }
    @Test void proofIssuanceIsGenericAndEmailCooldownPrecedesNewDelivery() throws Exception {
        var browser=h.boot();var known=h.send(browser,PROOFS,email(AccountHttpPostgresTest.aEmail()));assertEquals(202,known.getStatus());String token=deliveredToken();
        var unknown=h.send(browser,PROOFS,email(EMAIL));assertEquals(known.getStatus(),unknown.getStatus());assertEquals(known.getContentAsString(),unknown.getContentAsString());
        int challenges=h.count("email_challenge");var denied=h.send(browser,PROOFS,email(EMAIL));h.problem(denied,429,"RATE_LIMITED");assertTrue(Long.parseLong(denied.getHeader("Retry-After"))>0);assertEquals(challenges,h.count("email_challenge"));
        h.problem(h.send(browser,ENROLL,body(token)),400,"PROOF_INVALID");assertEquals(1,h.count("customer_account"));
    }
    @Test void networkBudgetBoundsEnrollmentFailuresAndKeepsSessionAnonymous() throws Exception {
        var browser=h.boot();for(int i=0;i<5;i++)h.problem(h.send(browser,ENROLL,body("a".repeat(43))),400,"PROOF_INVALID");
        var denied=h.send(browser,ENROLL,body("a".repeat(43)));h.problem(denied,429,"RATE_LIMITED");assertTrue(Long.parseLong(denied.getHeader("Retry-After"))>0);assertEquals(1,h.count("customer_account"));assertEquals(Kind.ANONYMOUS,h.a.sessions.inspect(browser.cookie().toCharArray()).orElseThrow().kind());
    }
    @Test void guardsAndMalformedBodiesCreateNeitherProofNorAccount() throws Exception {
        var browser=h.boot();int proofs=h.count("email_challenge"),accounts=h.count("customer_account");
        for(String path:List.of(PROOFS,ENROLL)) {
            h.problem(send(h.json(browser,path,"{}").with(r->{r.removeHeader("Origin");return r;})),403,"ORIGIN_REJECTED");
            h.problem(send(h.json(browser,path,"{}").with(r->{r.removeHeader("X-CSRF-Token");return r;})),403,"CSRF_REJECTED");h.problem(h.send(browser,path,"{}{}"),400,"INVALID_REQUEST");
        }
        h.problem(h.send(browser,PROOFS,email("invalid")),400,"INVALID_REQUEST");h.problem(h.send(browser,ENROLL,body("short")),400,"PROOF_INVALID");assertEquals(proofs,h.count("email_challenge"));assertEquals(accounts,h.count("customer_account"));
    }
    @Test void enrollingAnExistingGuestIdentityPreservesItsBookingsAndQuotaFacts() throws Exception {
        var c=h.a.e.c;var guest=c.guest(EMAIL);UUID booking=c.create(guest,"guest-booking",c.request(3,9)).entityId();var original=c.load(booking);int charges=h.count("request_charge"),events=h.count("outbox_event");
        var browser=h.boot();String token=proof(browser,EMAIL);assertEquals(204,h.send(browser,ENROLL,body(token)).getStatus());assertEquals(original,c.load(booking));assertEquals(charges,h.count("request_charge"));assertEquals(events,h.count("outbox_event"));
        assertEquals(original.customerId().toString(),h.a.value("SELECT customer_id FROM customer_account WHERE login_email=?",EMAIL));
        var login=h.send(browser,AccountHttpPostgresTest.LOGIN,AccountHttpPostgresTest.loginBody(EMAIL,new String(AccountEnrollmentDatabaseTest.password())));assertEquals(200,login.getStatus());var signed=h.browser(login);assertEquals(original.customerId(),h.a.sessions.inspect(signed.cookie().toCharArray()).orElseThrow().customerId());
    }
}
