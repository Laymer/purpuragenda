package com.purpuragenda.adapters;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.adapters.in.rest.*;
import com.purpuragenda.adapters.out.security.*;
import com.purpuragenda.application.*;
import com.purpuragenda.application.port.out.SessionStore;
import jakarta.servlet.http.Cookie;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Actual typed email enrollment, password proofs, session epochs and protected credential HTTP; no bypass. */
@Timeout(240)
@ResourceLock("postgres-migration-fixture")
class AccountHttpPostgresTest {
    static final String ORIGIN="https://bookings.example.test",LOGIN="/api/v1/account/login",PASSWORD="/api/v1/account/password";
    final AccountAuthenticationDatabaseTest a=new AccountAuthenticationDatabaseTest();
    @TempDir Path directory;
    MockMvc mvc;AccountLogin login;AccountPasswordChange passwords;SessionCookies cookies;SessionAccess access;
    @BeforeAll static void start() { AccountAuthenticationDatabaseTest.start(); }
    @AfterAll static void stop() { AccountAuthenticationDatabaseTest.stop(); }
    @BeforeEach void setup() throws Exception {
        a.directory=directory;a.setup();cookies=SessionCookies.operational();access=new SessionAccess(a.sessions);
        login=new AccountLogin(a.command,a.sessions,new AdminCrypto());
        passwords=new AccountPasswordChange(new PostgresAccountPassword(a.e.c.f.source),new Argon2PasswordHash(),new RequestThrottle(
                new PostgresRateLimits(a.e.c.f.source,new RateKey(directory.resolve("account-rate.key"),"account-test-v1"))));
        mvc=build(login,passwords);
    }
    @AfterEach void cleanup() { a.cleanup(); }
    MockMvc build(AccountLogin helper,AccountPasswordChange passwordCommand) {
        return MockMvcBuilders.standaloneSetup(new SessionController(access,cookies),new AccountController(helper,passwordCommand,cookies,Duration.ZERO))
                .addFilters(new ApiGuardFilter(ORIGIN,access,cookies,new ClientAddress(List.of()))).build();
    }
    record Browser(String cookie,String csrf) { @Override public String toString() { return "SyntheticBrowser[REDACTED]"; } }
    Browser browser(MockHttpServletResponse response) throws Exception {
        String header=response.getHeader("Set-Cookie"),cookie=header.substring(header.indexOf('=')+1,header.indexOf(';'));
        return new Browser(cookie,JsonMapper.builder().build().readTree(response.getContentAsString()).get("csrfToken").asText());
    }
    Browser boot() throws Exception {
        var response=mvc.perform(post("/api/v1/session/bootstrap").header("Origin",ORIGIN)).andReturn().getResponse();assertEquals(200,response.getStatus());return browser(response);
    }
    static String loginBody(String email,String password) { return JsonMapper.builder().build().createObjectNode().put("email",email).put("password",password).toString(); }
    static String loginBody() { return loginBody(aEmail(),new String(AccountEnrollmentDatabaseTest.password())); }
    static String aEmail() { return AccountAuthenticationDatabaseTest.EMAIL; }
    static String passwordBody(String current,String next) { return JsonMapper.builder().build().createObjectNode().put("email",aEmail()).put("currentPassword",current).put("newPassword",next).toString(); }
    static String newPassword() { return "synthetic-http-new-account-password"; }
    static String passwordBody() { return passwordBody(new String(AccountEnrollmentDatabaseTest.password()),newPassword()); }
    MockHttpServletRequestBuilder json(Browser browser,String path,String body) {
        return post(path).header("Origin",ORIGIN).header("X-CSRF-Token",browser.csrf()).cookie(new Cookie(cookies.name(),browser.cookie()))
                .contentType(MediaType.APPLICATION_JSON).content(body).with(request->{request.setRemoteAddr(AccountAuthenticationDatabaseTest.IP);return request;});
    }
    MockHttpServletResponse send(Browser browser,String path,String body) throws Exception { return mvc.perform(json(browser,path,body)).andReturn().getResponse(); }
    Browser login() throws Exception { var response=send(boot(),LOGIN,loginBody());assertEquals(200,response.getStatus());return browser(response); }
    void problem(MockHttpServletResponse response,int status,String code) throws Exception {
        assertEquals(status,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(response.getHeader("Set-Cookie"));
        String body=response.getContentAsString();assertTrue(body.contains("\"code\":\""+code+"\""),"EXPECTED_SAFE_PROBLEM_CODE");
        assertFalse(body.contains(aEmail()) || body.contains(newPassword()) || body.contains("SYNTHETIC_PRIVATE_PROVIDER"));
    }
    int count(String table) { return a.count(table); }
    @Test void correctPasswordConsumesOnlyAnInternalBoundProofAndRotatesCookieCsrfAndAccountSession() throws Exception {
        var before=boot();var response=send(before,LOGIN,loginBody());assertEquals(200,response.getStatus());var after=browser(response);
        assertEquals("no-store",response.getHeader("Cache-Control"));assertTrue(response.getHeader("Set-Cookie").contains("Secure; HttpOnly; SameSite=Lax"));
        assertFalse(before.cookie().equals(after.cookie()),"EXPECTED_BEARER_ROTATION");assertFalse(before.csrf().equals(after.csrf()),"EXPECTED_CSRF_ROTATION");
        assertTrue(response.getContentAsString().contains("\"role\":\"ACCOUNT\""));assertFalse(response.getContentAsString().contains("accountId"));
        assertTrue(a.sessions.inspect(before.cookie().toCharArray()).isEmpty());assertTrue(a.sessions.inspect(after.cookie().toCharArray()).isPresent());
        assertEquals(1,count("account_authentication_proof WHERE consumed_at IS NOT NULL"));assertEquals(1,count("audit_event WHERE type='ACCOUNT_SESSION_CREATED'"));
        var rotated=send(after,LOGIN,loginBody());assertEquals(200,rotated.getStatus());assertTrue(a.sessions.inspect(after.cookie().toCharArray()).isEmpty());
    }
    @Test void wrongAndUnknownPasswordsHaveTheSameGenericNoncacheableFailureWithoutCookieChanges() throws Exception {
        var browser=boot();problem(send(browser,LOGIN,loginBody(aEmail(),"synthetic-wrong-password")),401,"AUTHENTICATION_FAILED");
        problem(send(browser,LOGIN,loginBody("unknown@example.test","synthetic-wrong-password")),401,"AUTHENTICATION_FAILED");
        assertEquals(0,count("account_authentication_proof"));assertTrue(a.sessions.inspect(browser.cookie().toCharArray()).isPresent());
    }
    @ParameterizedTest @ValueSource(strings={"origin","csrf","cookie","absent","cross-site"})
    void commonOriginCookieAndCsrfGuardsRejectBeforeAnyAccountPasswordWork(String invalid) throws Exception {
        var browser=boot();var request=post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(loginBody())
                .header("Origin",invalid.equals("origin")?"https://attacker.example.test":ORIGIN)
                .header("X-CSRF-Token",invalid.equals("csrf")?"q".repeat(43):browser.csrf());
        if(!invalid.equals("absent"))request.cookie(new Cookie(cookies.name(),invalid.equals("cookie")?"z".repeat(43):browser.cookie()));
        if(invalid.equals("cross-site"))request.header("Sec-Fetch-Site","cross-site");
        int status=invalid.equals("cookie") || invalid.equals("absent")?401:403;
        String code=status==401?"SESSION_REQUIRED":invalid.equals("csrf")?"CSRF_REJECTED":invalid.equals("cross-site")?"CROSS_SITE_REJECTED":"ORIGIN_REJECTED";
        problem(mvc.perform(request).andReturn().getResponse(),status,code);assertEquals(0,count("account_authentication_proof"));assertEquals(0,count("rate_limit_bucket WHERE kind='ACCOUNT_ATTEMPT'"));
    }
    @Test void anonymousSessionsCannotChangePasswordsEvenWithTheKnownCredential() throws Exception {
        problem(send(boot(),PASSWORD,passwordBody()),401,"SESSION_REQUIRED");assertEquals("1",a.value("SELECT credential_version FROM customer_account"));assertEquals(0,count("rate_limit_bucket WHERE kind='ACCOUNT_ATTEMPT'"));
    }
    @Test void passwordChangeRevokesEveryAccountSessionProofAndCookieThenRequiresNormalLogin() throws Exception {
        var current=login();var other=login();
        try(var proof=a.proof()) {
            var response=send(current,PASSWORD,passwordBody());assertEquals(204,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));
            assertTrue(response.getHeader("Set-Cookie").contains("Max-Age=0"));assertTrue(response.getContentAsString().isEmpty());
            assertTrue(a.sessions.inspect(current.cookie().toCharArray()).isEmpty());assertTrue(a.sessions.inspect(other.cookie().toCharArray()).isEmpty());assertTrue(a.exchange(a.sessions,proof).isEmpty());
            assertEquals("2",a.value("SELECT credential_version FROM customer_account"));assertEquals(1,count("audit_event WHERE type='ACCOUNT_PASSWORD_CHANGED'"));
            var fresh=boot();problem(send(fresh,LOGIN,loginBody()),401,"AUTHENTICATION_FAILED");assertEquals(200,send(fresh,LOGIN,loginBody(aEmail(),newPassword())).getStatus());
        }
    }
    @Test void aKnownOtherPasswordCannotChangeAnotherAccountThroughTheCurrentSession() throws Exception {
        var current=login();String other="other-credential@example.test";a.e.enroll(a.e.proof(other)).orElseThrow();
        String body=JsonMapper.builder().build().createObjectNode().put("email",other).put("currentPassword",new String(AccountEnrollmentDatabaseTest.password())).put("newPassword",newPassword()).toString();
        problem(send(current,PASSWORD,body),401,"AUTHENTICATION_FAILED");
        assertTrue(a.sessions.inspect(current.cookie().toCharArray()).isPresent());assertEquals(2,count("customer_account WHERE credential_version=1"));
        assertEquals(0,count("audit_event WHERE type='ACCOUNT_PASSWORD_CHANGED'"));
    }
    @Test void wrongOldPasswordAndInvalidReplacementPreserveTheCurrentSessionAndCredential() throws Exception {
        var current=login();problem(send(current,PASSWORD,passwordBody("synthetic-wrong-password",newPassword())),401,"AUTHENTICATION_FAILED");
        problem(send(current,PASSWORD,passwordBody(new String(AccountEnrollmentDatabaseTest.password()),"short")),400,"INVALID_REQUEST");
        assertTrue(a.sessions.inspect(current.cookie().toCharArray()).isPresent());assertEquals("1",a.value("SELECT credential_version FROM customer_account"));
        assertEquals(0,count("audit_event WHERE type='ACCOUNT_PASSWORD_CHANGED'"));
    }
    @Test void distributedLoginFailureBudgetHasAPositiveRetryAfterWithoutCreatingProofs() throws Exception {
        var browser=boot();for(int i=0;i<5;i++)problem(send(browser,LOGIN,loginBody(aEmail(),"synthetic-wrong-password")),401,"AUTHENTICATION_FAILED");
        var denied=send(browser,LOGIN,loginBody());problem(denied,429,"RATE_LIMITED");assertTrue(Long.parseLong(denied.getHeader("Retry-After"))>0);assertEquals(0,count("account_authentication_proof"));
    }
    @Test void distributedPasswordChangeBudgetDoesNotRevokeAuthorityWhenDenied() throws Exception {
        var browser=login();for(int i=0;i<5;i++)problem(send(browser,PASSWORD,passwordBody("synthetic-wrong-password",newPassword())),401,"AUTHENTICATION_FAILED");
        var denied=send(browser,PASSWORD,passwordBody());problem(denied,429,"RATE_LIMITED");assertTrue(Long.parseLong(denied.getHeader("Retry-After"))>0);assertTrue(a.sessions.inspect(browser.cookie().toCharArray()).isPresent());
    }
    @Test void strictBodiesRejectDuplicatesTrailingFieldsAndNonJsonBeforeCredentialEffects() throws Exception {
        var current=login();
        for(String body:List.of("{}","{\"email\":\"x\",\"email\":\"y\",\"password\":\"z\"}",loginBody()+"{}",loginBody().replace("}",",\"extra\":\"field\"}"),"[]","x".repeat(2049))) {
            problem(send(current,LOGIN,body),400,"INVALID_REQUEST");problem(send(current,PASSWORD,body),400,"INVALID_REQUEST");
        }
        problem(mvc.perform(json(current,LOGIN,loginBody()).contentType(MediaType.TEXT_PLAIN)).andReturn().getResponse(),400,"INVALID_REQUEST");
        assertEquals(1,count("account_authentication_proof"));assertEquals("1",a.value("SELECT credential_version FROM customer_account"));
    }
    @Test void sessionInsertFailureCannotConsumeProofOrRevokeThePriorBrowser() throws Exception {
        var browser=boot();a.reject("app_session","ACCOUNT");problem(send(browser,LOGIN,loginBody()),503,"TEMPORARILY_UNAVAILABLE");
        assertTrue(a.sessions.inspect(browser.cookie().toCharArray()).isPresent());assertEquals(1,count("account_authentication_proof WHERE consumed_at IS NULL"));assertEquals(0,count("app_session WHERE kind='ACCOUNT'"));
    }
    @Test void passwordAuditFailureRollsBackCredentialAndAllRevocationsWithoutDisclosingProviderDetails() throws Exception {
        var current=login();a.reject("audit_event","ACCOUNT_PASSWORD_CHANGED");problem(send(current,PASSWORD,passwordBody()),503,"TEMPORARILY_UNAVAILABLE");
        assertTrue(a.sessions.inspect(current.cookie().toCharArray()).isPresent());assertEquals("1",a.value("SELECT credential_version FROM customer_account"));assertEquals(0,count("audit_event WHERE type='ACCOUNT_PASSWORD_CHANGED'"));
    }
    @Test void twentyPasswordRequestsAcrossTwoHttpCompositionsHaveOneWinnerAndNoUnexpectedFailure() throws Exception {
        var current=login();var second=build(new AccountLogin(a.command,new PostgresSessions(a.e.c.f.source),new AdminCrypto()),passwords);
        try(var pool=Executors.newFixedThreadPool(20)) {
            var start=new CountDownLatch(1);var results=new ArrayList<Future<Integer>>();
            for(int i=0;i<20;i++) { var backend=i%2==0?mvc:second;results.add(pool.submit(()->{start.await();return backend.perform(json(current,PASSWORD,passwordBody())).andReturn().getResponse().getStatus();})); }
            start.countDown();int winners=0;for(var result:results) { int status=result.get();if(status==204)winners++;else assertTrue(status==401 || status==429,"EXPECTED_AUTHORITY_OR_RATE_DENIAL"); }
            assertEquals(1,winners);assertEquals("2",a.value("SELECT credential_version FROM customer_account"));assertEquals(1,count("audit_event WHERE type='ACCOUNT_PASSWORD_CHANGED'"));
        }
    }
    @Test void oldBearerAndOldCsrfCannotReplayAfterSuccessfulLoginRotation() throws Exception {
        var original=boot();var signed=send(original,LOGIN,loginBody());assertEquals(200,signed.getStatus());var current=browser(signed);
        problem(send(original,LOGIN,loginBody()),401,"SESSION_REQUIRED");
        problem(mvc.perform(json(new Browser(current.cookie(),original.csrf()),PASSWORD,passwordBody())).andReturn().getResponse(),403,"CSRF_REJECTED");
        assertEquals("1",a.value("SELECT credential_version FROM customer_account"));
    }
    @Test void standaloneMissingSessionAndConstructorBoundsRemainFailClosed() throws Exception {
        var standalone=MockMvcBuilders.standaloneSetup(new AccountController(login,passwords,cookies,Duration.ZERO)).build();
        problem(standalone.perform(post(LOGIN)).andReturn().getResponse(),401,"SESSION_REQUIRED");problem(standalone.perform(post(PASSWORD)).andReturn().getResponse(),401,"SESSION_REQUIRED");
        assertThrows(NullPointerException.class,()->new AccountController(null,passwords,cookies,Duration.ZERO));assertThrows(NullPointerException.class,()->new AccountController(login,null,cookies,Duration.ZERO));
        assertThrows(NullPointerException.class,()->new AccountController(login,passwords,null,Duration.ZERO));assertThrows(IllegalArgumentException.class,()->new AccountController(login,passwords,cookies,Duration.ofNanos(-1)));
        assertThrows(IllegalArgumentException.class,()->new AccountController(login,passwords,cookies,Duration.ofSeconds(6)));
    }
}
