package com.purpuragenda.adapters;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.adapters.in.rest.*;
import com.purpuragenda.adapters.out.security.*;
import com.purpuragenda.application.*;
import com.purpuragenda.application.port.out.SessionStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.bouncycastle.util.encoders.Base32;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real private recovery factors and transactions through guarded HTTP; secrets never enter assertion diagnostics. */
@Timeout(180)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class AdministratorRecoveryPostgresTest {
    static final String PATH="/api/v1/admin/recovery";
    static final JsonMapper JSON=JsonMapper.builder().build();
    @TempDir Path directory;
    AdministratorLoginPostgresTest fixture;AdminMfaDatabaseTest f;MockMvc mvc;AdministratorRecovery recovery;
    @BeforeAll static void start() { AdminMfaDatabaseTest.start(); }
    @AfterAll static void stop() { AdminMfaDatabaseTest.stop(); }
    @BeforeEach void setup() throws Exception {
        fixture=new AdministratorLoginPostgresTest();fixture.directory=directory;fixture.setup();f=fixture.f;
        recovery=new AdministratorRecovery(new PrivateAdminRecovery(new PostgresAdminRecovery(f.source,f.secrets),new Argon2PasswordHash(),new AdminCrypto()),
                new RequestThrottle(fixture.limits),new SessionRecoveryBinding());
        build(Duration.ZERO);
    }
    void build(Duration latency) {
        mvc=MockMvcBuilders.standaloneSetup(new SessionController(fixture.access,fixture.cookies),new AdministratorRecoveryController(recovery,latency),
                new AdministratorLoginController(new AdministratorLogin(new RequestThrottle(fixture.limits),f.mfa,fixture.sessions,new AdminCrypto()),fixture.cookies,Duration.ZERO))
                .addFilters(new ApiGuardFilter(AdministratorLoginPostgresTest.ORIGIN,fixture.access,fixture.cookies,new ClientAddress(List.of()))).build();
        fixture.mvc=mvc;
    }
    @AfterEach void cleanup() { fixture.cleanup(); }
    String beginBody() { return JSON.createObjectNode().put("email","owner@example.test").put("password",new String(AdminMfaDatabaseTest.password())).put("recoveryCode",new String(fixture.recovery)).toString(); }
    String finishBody(JsonNode setup,String code) { return JSON.createObjectNode().put("email","owner@example.test").put("password",new String(AdminMfaDatabaseTest.password())).put("token",setup.get("token").textValue()).put("totp",code).toString(); }
    MockHttpServletResponse send(AdministratorLoginPostgresTest.Browser browser,String path,String body) throws Exception { return mvc.perform(fixture.json(browser,path,body)).andReturn().getResponse(); }
    JsonNode begin(AdministratorLoginPostgresTest.Browser browser) throws Exception {
        var response=send(browser,PATH,beginBody());assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(response.getHeader("Set-Cookie"));return JSON.readTree(response.getContentAsString());
    }
    String code(JsonNode setup) throws Exception { byte[] secret=Base32.decode(setup.get("totpSecret").textValue()),stored=f.secret();try { assertTrue(Arrays.equals(secret,stored),"Setup must expose the current restricted secret");return f.code(f.now().getEpochSecond()/30); } finally { Arrays.fill(secret,(byte)0);Arrays.fill(stored,(byte)0); } }
    int count(String table) { return fixture.count(table); }
    void anonymous(AdministratorLoginPostgresTest.Browser browser) { assertEquals(SessionStore.Kind.ANONYMOUS,fixture.sessions.inspect(browser.cookie().toCharArray()).orElseThrow().kind());assertEquals(0,count("app_session WHERE kind='ADMIN'")); }

    @Test void realRecoveryReenrollsButNeverAuthorizesAndFreshMfaIsStillRequired() throws Exception {
        var browser=fixture.boot();JsonNode setup=begin(browser);anonymous(browser);
        assertEquals(Set.of("token","expiresAt","totpSecret","provisioningUri"),fields(setup));
        assertEquals(f.jdbc.queryForObject("SELECT expires_at FROM admin_reenrollment_grant",java.sql.Timestamp.class).toInstant(),Instant.parse(setup.get("expiresAt").textValue()));
        assertTrue(setup.get("provisioningUri").textValue().startsWith("otpauth://totp/Purpuragenda:Administrator?secret="));
        String accepted=code(setup);
        fixture.problem(send(browser,AdministratorLoginPostgresTest.PATH,AdministratorLoginPostgresTest.body(accepted)),401,"AUTHENTICATION_FAILED");
        var finished=send(browser,PATH+"/reenrollment",finishBody(setup,accepted));assertEquals(200,finished.getStatus());assertNull(finished.getHeader("Set-Cookie"));
        JsonNode result=JSON.readTree(finished.getContentAsString());assertEquals(10,result.get("recoveryCodes").size());assertTrue(result.get("requiresFreshMfa").booleanValue());anonymous(browser);
        fixture.problem(send(browser,PATH+"/reenrollment",finishBody(setup,accepted)),401,"AUTHENTICATION_FAILED");
        fixture.problem(send(browser,AdministratorLoginPostgresTest.PATH,AdministratorLoginPostgresTest.body(accepted)),401,"AUTHENTICATION_FAILED");
        assertEquals(200,send(browser,AdministratorLoginPostgresTest.PATH,AdministratorLoginPostgresTest.body(f.freshCode())).getStatus());
    }
    static Set<String> fields(JsonNode node) { var fields=new HashSet<String>();node.fieldNames().forEachRemaining(fields::add);return fields; }
    @Test void exactRetryRotatesBearerPreservesDeadlineAndAnotherBrowserCannotResumeOrFinish() throws Exception {
        var a=fixture.boot();var b=fixture.boot();var first=begin(a);var second=begin(a);
        assertTrue(first.get("expiresAt").equals(second.get("expiresAt")),"Retry must retain original deadline");
        assertFalse(first.get("token").equals(second.get("token")),"Retry must rotate restricted bearer");
        fixture.problem(send(b,PATH,beginBody()),401,"AUTHENTICATION_FAILED");
        fixture.problem(send(b,PATH+"/reenrollment",finishBody(second,code(second))),401,"AUTHENTICATION_FAILED");
        fixture.problem(send(a,PATH+"/reenrollment",finishBody(first,code(first))),401,"AUTHENTICATION_FAILED");
        assertEquals(200,send(a,PATH+"/reenrollment",finishBody(second,code(second))).getStatus());anonymous(a);anonymous(b);
    }
    @Test void canonicalIdentitySharesDistributedFailureCapacityAcrossBothRecoveryEndpoints() throws Exception {
        f.jdbc.update("UPDATE admin_user SET email_key='owner@xn--bcher-kva.test'");var browser=fixture.boot();
        for(int i=0;i<5;i++) {
            String email=i%2==0?"Owner@bücher.test":"owner@xn--bcher-kva.test";
            var body=JSON.createObjectNode().put("email",email).put("password","synthetic incorrect password").put(i%2==0?"recoveryCode":"token","A".repeat(43));
            if(i%2!=0)body.put("totp","123456");
            fixture.problem(send(browser,i%2==0?PATH:PATH+"/reenrollment",body.toString()),401,"AUTHENTICATION_FAILED");
        }
        var limited=send(browser,PATH,beginBody().replace("owner@example.test","owner@xn--bcher-kva.test"));fixture.problem(limited,429,"RATE_LIMITED");assertNotNull(limited.getHeader("Retry-After"));
        assertEquals(0,count("admin_reenrollment_grant"));assertEquals(0,count("admin_recovery_code WHERE consumed_at IS NOT NULL"));
    }
    @Test void strictBoundaryRejectsExtraFieldsOriginCsrfMissingSessionAndMalformedBodiesBeforeEffects() throws Exception {
        var browser=fixture.boot();
        for(String body:List.of("{}",beginBody().replace("\"email\":","\"requestHash\":\"a\",\"email\":"),beginBody()+"{}",beginBody().replace("\"email\":","\"email\":\"duplicate\",\"email\":")))
            fixture.problem(send(browser,PATH,body),400,"INVALID_REQUEST");
        fixture.problem(send(browser,PATH+"/reenrollment","{}"),400,"INVALID_REQUEST");
        assertEquals(403,mvc.perform(post(PATH).contentType("application/json").content(beginBody())).andReturn().getResponse().getStatus());
        assertEquals(401,mvc.perform(post(PATH).header("Origin",AdministratorLoginPostgresTest.ORIGIN).contentType("application/json").content(beginBody())).andReturn().getResponse().getStatus());
        assertEquals(0,count("admin_reenrollment_grant"));assertEquals(0,count("rate_limit_bucket WHERE kind='ADMIN_ATTEMPT'"));
    }
    @Test void disabledExpiredRevokedAndWrongPasswordRecoveryAreGenericAndNeverBecomeSessions() throws Exception {
        var browser=fixture.boot();
        fixture.problem(send(browser,PATH,beginBody().replace(new String(AdminMfaDatabaseTest.password()),"synthetic incorrect password")),401,"AUTHENTICATION_FAILED");
        var setup=begin(browser);
        f.jdbc.update("WITH captured AS (SELECT clock_timestamp() AS now) UPDATE admin_reenrollment_grant SET issued_at=captured.now-interval '601 seconds',expires_at=captured.now-interval '1 second' FROM captured");
        fixture.problem(send(browser,PATH+"/reenrollment",finishBody(setup,code(setup))),401,"AUTHENTICATION_FAILED");
        fixture.problem(send(browser,PATH,beginBody()),401,"AUTHENTICATION_FAILED");
        f.jdbc.update("UPDATE admin_user SET enabled=false");fixture.problem(send(browser,PATH,beginBody()),401,"AUTHENTICATION_FAILED");anonymous(browser);
    }
    @Test void revokedAnonymousSessionCannotResumeRestrictedGrant() throws Exception {
        var browser=fixture.boot();begin(browser);fixture.sessions.revoke(browser.reference());
        fixture.problem(send(browser,PATH,beginBody()),401,"SESSION_REQUIRED");assertEquals(1,count("admin_reenrollment_grant"));
    }
    @Test void authenticatedAdministratorCannotStartRecoveryUntilLoggingOut() throws Exception {
        var browser=fixture.boot();var logged=send(browser,AdministratorLoginPostgresTest.PATH,AdministratorLoginPostgresTest.body(f.freshCode()));assertEquals(200,logged.getStatus());
        String cookie=AdministratorLoginPostgresTest.value(logged.getHeader("Set-Cookie"));
        var admin=new AdministratorLoginPostgresTest.Browser(cookie,AdministratorLoginPostgresTest.csrf(logged),fixture.sessions.inspect(cookie.toCharArray()).orElseThrow().reference());
        fixture.problem(send(admin,PATH,beginBody()),401,"AUTHENTICATION_FAILED");assertEquals(0,count("admin_reenrollment_grant"));assertEquals(1,count("app_session WHERE kind='ADMIN'"));
    }
    @Test void providerFailureIsSafeAndNeverIssuesAnyCredentialOrCookie() throws Exception {
        var failing=new com.purpuragenda.application.port.out.AdminRecoveryUnitOfWork() {
            public <T>T locked(String email,java.util.function.Function<Admin,T> work) { throw new IllegalStateException("SYNTHETIC_PRIVATE_PROVIDER_DETAIL"); }
        };
        recovery=new AdministratorRecovery(new PrivateAdminRecovery(failing,new Argon2PasswordHash(),new AdminCrypto()),new RequestThrottle(fixture.limits),new SessionRecoveryBinding());
        build(Duration.ZERO);var browser=fixture.boot();fixture.problem(send(browser,PATH,beginBody()),503,"TEMPORARILY_UNAVAILABLE");
        String body=JSON.createObjectNode().put("email","owner@example.test").put("password",new String(AdminMfaDatabaseTest.password())).put("token","A".repeat(43)).put("totp","123456").toString();
        fixture.problem(send(browser,PATH+"/reenrollment",body),503,"TEMPORARILY_UNAVAILABLE");assertEquals(0,count("admin_reenrollment_grant"));anonymous(browser);
    }
    @Test void bindingChangesWithSessionIdentityEvenWhenBrowserHashIsTheSame() {
        var derive=new SessionRecoveryBinding();var a=new SessionStore.SessionReference(UUID.randomUUID(),"a".repeat(64));
        var first=derive.derive(a);var retry=derive.derive(a);var other=derive.derive(new SessionStore.SessionReference(UUID.randomUUID(),a.browserHash()));
        assertTrue(Arrays.equals(first.requestHash(),retry.requestHash()),"Exact persisted session retry must retain request binding");
        assertFalse(Arrays.equals(first.requestHash(),other.requestHash()),"A replacement session must not inherit restricted recovery");
        assertTrue(Arrays.equals(first.browserHash(),other.browserHash()));
    }
    @Test void missingClientAttributeFallsBackConservativelyAndLatencyPreservesInterruption() throws Exception {
        var browser=fixture.boot();var controller=new AdministratorRecoveryController(recovery,Duration.ofMillis(500));
        var request=new MockHttpServletRequest();request.setContentType("application/json");request.setContent(beginBody().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.setAttribute(ApiGuardFilter.SESSION_ATTRIBUTE,fixture.sessions.inspect(browser.cookie().toCharArray()).orElseThrow());
        var response=new MockHttpServletResponse();
        Thread.currentThread().interrupt();
        try { assertNotNull(controller.begin(request,response));assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
        anonymous(browser);
    }
    @Test void minimumLatencyAndMissingBoundaryAndConstructorGuardsRemainSafe() throws Exception {
        build(Duration.ofMillis(30));var browser=fixture.boot();long start=System.nanoTime();
        fixture.problem(send(browser,PATH,"{}"),400,"INVALID_REQUEST");assertTrue(System.nanoTime()-start>=Duration.ofMillis(25).toNanos());
        var controller=new AdministratorRecoveryController(recovery,Duration.ZERO);var request=new MockHttpServletRequest();var response=new MockHttpServletResponse();
        assertNull(controller.begin(request,response));assertEquals(401,response.getStatus());
        assertThrows(IllegalArgumentException.class,()->new AdministratorRecoveryController(recovery,Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class,()->new AdministratorRecoveryController(recovery,Duration.ofSeconds(6)));
        assertThrows(NullPointerException.class,()->new AdministratorRecoveryController(null,Duration.ZERO));
        assertThrows(NullPointerException.class,()->new SessionRecoveryBinding().derive(null));
    }
}
