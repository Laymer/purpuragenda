package com.purpuragenda.adapters;

import com.purpuragenda.adapters.in.rest.*;
import com.purpuragenda.adapters.out.security.*;
import com.purpuragenda.application.*;
import com.purpuragenda.application.port.out.SessionStore;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real passwords, TOTP, distributed admission, proof transactions and cookie/session rotation through HTTP. */
@Timeout(180)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class AdministratorLoginPostgresTest {
    static final String ORIGIN="https://bookings.example.test",PATH="/api/v1/admin/login";
    static final Instant RATE_TIME=Instant.parse("2026-11-01T10:00:00Z");
    @TempDir Path directory;
    AdminMfaDatabaseTest f;PostgresSessions sessions;MockMvc mvc;char[] recovery;
    final AtomicReference<Instant> rateClock=new AtomicReference<>(RATE_TIME);
    PostgresRateLimits limits;SessionAccess access;SessionCookies cookies;
    @BeforeAll static void start() { AdminMfaDatabaseTest.start(); }
    @AfterAll static void stop() { AdminMfaDatabaseTest.stop(); }
    @BeforeEach void setup() throws Exception {
        f=new AdminMfaDatabaseTest();f.temporary=directory;f.prepare();
        try(var codes=f.mfa.enroll("owner@example.test",password(),f.code(f.now().getEpochSecond()/30)).orElseThrow()) { recovery=codes.codes().getFirst().value(); }
        byte[] key=new byte[32];Arrays.fill(key,(byte)9);
        limits=new PostgresRateLimits(ThrottleFixture.at(f.source,rateClock),new RateKey(Files.write(directory.resolve("rate.key"),key),"rate-admin-v1"));
        sessions=new PostgresSessions(f.source);access=new SessionAccess(sessions);cookies=SessionCookies.operational();
        mvc=build(sessions,limits,Duration.ZERO);
    }
    @AfterEach void cleanup() { Arrays.fill(recovery,'\0');f.cleanup(); }
    static char[] password() { return AdminMfaDatabaseTest.password(); }
    MockMvc build(SessionStore store,com.purpuragenda.application.port.out.RateLimitStore rate,Duration latency) {
        var login=new AdministratorLogin(new RequestThrottle(rate),f.mfa,store,new AdminCrypto());
        return MockMvcBuilders.standaloneSetup(new SessionController(access,cookies),new AdministratorLoginController(login,cookies,latency))
                .addFilters(new ApiGuardFilter(ORIGIN,access,cookies,new ClientAddress(List.of("10.0.0.2")))).build();
    }
    record Browser(String cookie,String csrf,SessionStore.SessionReference reference) { }
    Browser boot() throws Exception {
        var response=mvc.perform(post("/api/v1/session/bootstrap").header("Origin",ORIGIN)).andReturn().getResponse();assertEquals(200,response.getStatus());
        String cookie=value(response.getHeader("Set-Cookie")),csrf=csrf(response);
        return new Browser(cookie,csrf,sessions.inspect(cookie.toCharArray()).orElseThrow().reference());
    }
    static String value(String cookie) { return cookie.substring(cookie.indexOf('=')+1,cookie.indexOf(';')); }
    static String csrf(MockHttpServletResponse response) throws Exception { return response.getContentAsString().replaceAll(".*\"csrfToken\":\"([^\"]+)\".*","$1"); }
    static String body(String email,String password,String code) { return "{\"email\":\""+email+"\",\"password\":\""+password+"\",\"totp\":\""+code+"\"}"; }
    static String body(String code) { return body("owner@example.test",new String(password()),code); }
    MockHttpServletRequestBuilder json(Browser browser,String path,String body) {
        return post(URI.create(path)).header("Origin",ORIGIN).header("X-CSRF-Token",browser.csrf()).cookie(new Cookie("__Host-purpuragenda",browser.cookie()))
                .contentType(MediaType.APPLICATION_JSON).content(body).with(request->{request.setRemoteAddr("198.51.100.4");return request;});
    }
    MockHttpServletResponse send(Browser browser,String body) throws Exception { return mvc.perform(json(browser,PATH,body)).andReturn().getResponse(); }
    int count(String table) { return f.jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
    void problem(MockHttpServletResponse response,int status,String code) throws Exception {
        assertEquals(status,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(response.getHeader("Set-Cookie"));
        String payload=response.getContentAsString();assertTrue(payload.contains("\"code\":\""+code+"\""),"Expected safe problem code");
        assertFalse(payload.contains("owner@example") || payload.contains("synthetic") || payload.contains("totp_secret") || payload.contains("password"));
    }

    @Test void acceptedRealMfaConsumesInternalProofAndRotatesCookieCsrfAndBrowserBoundSession() throws Exception {
        var borrowed=new AtomicReference<char[]>();var hashing=new Argon2PasswordHash();
        f.mfa=new PrivateAdminMfa(f.store,new com.purpuragenda.application.port.out.PasswordHash() {
            public String hash(char[] value) { throw new AssertionError(); }
            public boolean verify(char[] value,String encoded) { borrowed.set(value);return hashing.verify(value,encoded); }
        },new AdminCrypto());
        mvc=build(sessions,limits,Duration.ZERO);
        var browser=boot();var response=send(browser,body(f.freshCode()));assertEquals(200,response.getStatus());
        assertTrue(Arrays.equals(new char[borrowed.get().length],borrowed.get()),"Credential port must not retain the borrowed password");
        assertEquals("no-store",response.getHeader("Cache-Control"));
        String cookie=value(response.getHeader("Set-Cookie"));assertFalse(cookie.equals(browser.cookie()),"Login must rotate bearer cookie");
        assertFalse(csrf(response).equals(browser.csrf()),"Login must rotate CSRF proof");
        var admin=sessions.inspect(cookie.toCharArray()).orElseThrow();assertEquals(SessionStore.Kind.ADMIN,admin.kind());
        assertEquals(browser.reference().browserHash(),admin.reference().browserHash());
        assertTrue(sessions.inspect(browser.cookie().toCharArray()).isEmpty());assertTrue(sessions.csrf(admin.reference(),csrf(response).toCharArray()));
        assertEquals(Duration.ofHours(8),Duration.between(admin.issuedAt(),admin.absoluteExpiresAt()));
        assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM admin_authentication_proof WHERE purpose='ADMIN_AUTHENTICATION' AND consumed_at IS NOT NULL",Integer.class));
        assertEquals(0,f.jdbc.queryForObject("SELECT count FROM rate_limit_bucket WHERE kind='ADMIN_ATTEMPT'",Integer.class));
        String payload=response.getContentAsString();
        assertTrue(payload.startsWith("{\"role\":\"ADMIN\""));
        assertFalse(payload.contains("authenticationProof") || payload.contains("requestHash") || payload.contains("adminId") || payload.contains("password"));
    }
    @Test void acceptedTotpCannotBeReplayedAndPreviousCookieCannotAuthenticateAgain() throws Exception {
        var browser=boot();String code=f.freshCode();var accepted=send(browser,body(code));assertEquals(200,accepted.getStatus());
        var admin=new Browser(value(accepted.getHeader("Set-Cookie")),csrf(accepted),browser.reference());
        problem(send(admin,body(code)),401,"AUTHENTICATION_FAILED");
        problem(send(browser,body(code)),401,"SESSION_REQUIRED");
        assertEquals(1,count("admin_authentication_proof"));assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM app_session WHERE kind='ADMIN'",Integer.class));
    }
    @Test void normalizedIdnaCredentialsShareFiveAttemptBudgetAndAuthoritativeRetryBoundary() throws Exception {
        f.jdbc.update("UPDATE admin_user SET email_key='owner@xn--bcher-kva.example'");var browser=boot();
        for(int attempt=0;attempt<5;attempt++) {
            String email=attempt%2==0?"Owner@BÜCHER.example":"owner@xn--bcher-kva.example";
            problem(send(browser,body(email,"incorrect synthetic phrase","123456")),401,"AUTHENTICATION_FAILED");
        }
        var limited=send(browser,body("owner@xn--bcher-kva.example",new String(password()),"123456"));problem(limited,429,"RATE_LIMITED");assertEquals("900",limited.getHeader("Retry-After"));
        rateClock.set(RATE_TIME.plusSeconds(900).minusNanos(1000));
        assertEquals("1",send(browser,body("Owner@BÜCHER.example",new String(password()),"123456")).getHeader("Retry-After"));
        rateClock.set(RATE_TIME.plusSeconds(900));
        problem(send(browser,body("owner@xn--bcher-kva.example","incorrect synthetic phrase","123456")),401,"AUTHENTICATION_FAILED");
        assertEquals(1,count("rate_limit_bucket"));assertEquals(0,count("admin_authentication_proof"));
    }
    @Test void missingDisabledUnenrolledAndWrongFactorsShareOneGenericFailure() throws Exception {
        var browser=boot();var responses=new ArrayList<MockHttpServletResponse>();
        responses.add(send(browser,body("missing@example.test",new String(password()),"123456")));
        responses.add(send(browser,body("owner@example.test","incorrect synthetic phrase","123456")));
        f.jdbc.update("UPDATE admin_user SET enabled=false");responses.add(send(browser,body("123456")));
        f.jdbc.update("UPDATE admin_user SET enabled=true,enrolled=false");responses.add(send(browser,body("123456")));
        var shapes=new HashSet<String>();
        for(var response:responses) { problem(response,401,"AUTHENTICATION_FAILED");shapes.add(response.getContentAsString().replaceAll("\"correlationId\":\"[^\"]+\"","")); }
        assertEquals(1,shapes.size());assertEquals(0,count("admin_authentication_proof"));
    }
    @Test void untrustedAddressHeadersCannotEscapeAccountNetworkCapacityButTrustedPeerCanSupplyItsClient() throws Exception {
        var browser=boot();String wrong=body("owner@example.test","incorrect synthetic phrase","123456");
        for(int i=0;i<5;i++)problem(send(browser,wrong),401,"AUTHENTICATION_FAILED");
        problem(mvc.perform(json(browser,PATH,wrong).header(ClientAddress.HEADER,"203.0.113.8")
                .header("X-Forwarded-For","203.0.113.9")).andReturn().getResponse(),429,"RATE_LIMITED");
        problem(mvc.perform(json(browser,PATH,wrong).with(request->{request.setRemoteAddr("10.0.0.2");return request;})
                .header(ClientAddress.HEADER,"203.0.113.8")).andReturn().getResponse(),401,"AUTHENTICATION_FAILED");
        assertEquals(2,count("rate_limit_bucket"));
    }
    @Test void malformedBodiesAndGuardFailuresNeverReachTheFactorsOrPublicRegistration() throws Exception {
        var browser=boot();long before=f.previous();
        for(String invalid:List.of("{}","[]","null","{\"email\":\"owner@example.test\"}",
                body("123456").replace("\"totp\":", "\"unknown\":"),body("123456")+" trailing",
                body("123456").replace("\"totp\":\"123456\"", "\"totp\":\"123456\",\"totp\":\"654321\""),"x".repeat(3000))) {
            problem(send(browser,invalid),400,"INVALID_REQUEST");
        }
        problem(mvc.perform(json(browser,PATH,body("123456")).contentType(MediaType.TEXT_PLAIN)).andReturn().getResponse(),400,"INVALID_REQUEST");
        for(String path:List.of(PATH,"/api/v1/admin/%6cogin","/api/v1/admin/login;x=1","/api/v1//admin/login")) {
            problem(mvc.perform(post(URI.create(path)).contentType(MediaType.APPLICATION_JSON).content(body("123456"))).andReturn().getResponse(),403,"ORIGIN_REJECTED");
            problem(mvc.perform(post(URI.create(path)).header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON).content(body("123456"))).andReturn().getResponse(),401,"SESSION_REQUIRED");
            problem(mvc.perform(json(browser,path,body("123456")).header("Sec-Fetch-Site","cross-site")).andReturn().getResponse(),403,"CROSS_SITE_REJECTED");
        }
        assertEquals(404,mvc.perform(json(browser,"/api/v1/admin/register",body("123456"))).andReturn().getResponse().getStatus());
        assertEquals(before,f.previous());assertEquals(0,count("rate_limit_bucket"));assertEquals(0,count("admin_authentication_proof"));
    }
    @Test void twentyConcurrentRealFactorRequestsCannotReplayOneStepOrExceedFailedAttemptCapacity() throws Exception {
        var browsers=new ArrayList<Browser>();for(int i=0;i<20;i++)browsers.add(boot());String body=body(f.freshCode());
        var barrier=new CyclicBarrier(20);var responses=new ArrayList<MockHttpServletResponse>();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=new ArrayList<Future<MockHttpServletResponse>>();
            for(var browser:browsers)tasks.add(pool.submit(()->{barrier.await();return send(browser,body);}));
            for(var task:tasks)responses.add(task.get(30,TimeUnit.SECONDS));
        }
        assertEquals(1,responses.stream().filter(response->response.getStatus()==200).count());
        assertTrue(responses.stream().allMatch(response->Set.of(200,401,429).contains(response.getStatus())));
        assertTrue(responses.stream().filter(response->response.getStatus()==401).count()<=5);
        assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM app_session WHERE kind='ADMIN'",Integer.class));
        assertEquals(1,count("admin_authentication_proof"));
        assertTrue(f.jdbc.queryForObject("SELECT count FROM rate_limit_bucket WHERE kind='ADMIN_ATTEMPT'",Integer.class)<=5);
    }
    @ParameterizedTest @ValueSource(strings={"administrator","session"})
    void revocationBetweenAcceptedFactorsAndHandoffNeverCreatesOrdinaryAuthority(String target) throws Exception {
        var browser=boot();String body=body(f.freshCode());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var once=new AtomicBoolean();var gated=new DelegatingDataSource(f.source) {
            @Override public Connection getConnection() throws SQLException {
                if(once.compareAndSet(false,true)) {
                    entered.countDown();
                    try { if(!release.await(10,TimeUnit.SECONDS))throw new SQLException("synthetic handoff timeout"); }
                    catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new SQLException("synthetic interrupted handoff"); }
                }
                return super.getConnection();
            }
        };
        mvc=build(new PostgresSessions(gated),limits,Duration.ZERO);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var response=pool.submit(()->send(browser,body));
            try {
                assertTrue(entered.await(10,TimeUnit.SECONDS));
                assertEquals(1,count("admin_authentication_proof"));
                if(target.equals("administrator"))f.jdbc.update("UPDATE admin_user SET enabled=false");
                else assertTrue(sessions.revoke(browser.reference()));
            } finally { release.countDown(); }
            problem(response.get(15,TimeUnit.SECONDS),401,"AUTHENTICATION_FAILED");
        }
        assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM app_session WHERE kind='ADMIN'",Integer.class));
        assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM admin_authentication_proof WHERE consumed_at IS NOT NULL",Integer.class));
    }
    @Test void sessionInsertFailureRollsBackHandoffAndDoesNotExposeAcceptedFactorProof() throws Exception {
        var browser=boot();String body=body(f.freshCode());
        try(var connection=f.fixture.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(connection,"CREATE FUNCTION reject_login_session() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic-private-marker'; END $$");
            MigrationTest.execute(connection,"CREATE TRIGGER reject_login_session BEFORE INSERT ON app_session FOR EACH ROW EXECUTE FUNCTION reject_login_session()");
        }
        problem(send(browser,body),503,"TEMPORARILY_UNAVAILABLE");
        assertEquals(1,count("admin_authentication_proof"));assertEquals(1,count("app_session"));
        assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM admin_authentication_proof WHERE consumed_at IS NOT NULL",Integer.class));
        assertTrue(sessions.inspect(browser.cookie().toCharArray()).isPresent());
    }
    @Test void recoveryProofAndUnenrolledFreshSecretNeverYieldNormalAdministratorSession() throws Exception {
        var browser=boot();var recoveryFlow=new PrivateAdminRecovery(new PostgresAdminRecovery(f.source,f.secrets),new Argon2PasswordHash(),new AdminCrypto());
        var binding=new ReenrollmentGrantPolicy.Binding(HexFormat.of().parseHex(browser.reference().browserHash()),new byte[32]);
        try(var restricted=recoveryFlow.begin("owner@example.test",password(),recovery.clone(),binding).orElseThrow()) {
            String token=new String(restricted.token().value());
            problem(send(browser,body(token)),400,"INVALID_REQUEST");
            problem(send(browser,body(f.code(f.now().getEpochSecond()/30))),401,"AUTHENTICATION_FAILED");
            assertFalse(f.enrolled());assertEquals(0,count("admin_authentication_proof"));
            assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM app_session WHERE kind='ADMIN'",Integer.class));
        }
    }
    @Test void controllerRejectsMissingBoundaryAndInvalidConfigurationAndPadsCredentialFailures() throws Exception {
        var login=new AdministratorLogin(new RequestThrottle(limits),f.mfa,sessions,new AdminCrypto());
        assertThrows(IllegalArgumentException.class,()->new AdministratorLoginController(login,cookies,Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class,()->new AdministratorLoginController(login,cookies,Duration.ofSeconds(6)));
        assertThrows(NullPointerException.class,()->new AdministratorLoginController(null,cookies,Duration.ZERO));
        assertThrows(NullPointerException.class,()->new AdministratorLoginController(login,null,Duration.ZERO));
        var controller=new AdministratorLoginController(login,cookies,Duration.ZERO);var rejected=new MockHttpServletResponse();
        assertNull(controller.authenticate(new MockHttpServletRequest(),rejected));problem(rejected,401,"SESSION_REQUIRED");
        var browser=boot();mvc=build(sessions,limits,Duration.ofMillis(50));long start=System.nanoTime();
        problem(send(browser,body("missing@example.test",new String(password()),"123456")),401,"AUTHENTICATION_FAILED");
        assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()>=45,"Credential failure must retain minimum latency");
        var direct=new MockHttpServletRequest();direct.setContentType("application/json");
        direct.setContent(body("missing@example.test",new String(password()),"123456").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        direct.setAttribute(ApiGuardFilter.SESSION_ATTRIBUTE,sessions.inspect(browser.cookie().toCharArray()).orElseThrow());
        var fallback=new MockHttpServletResponse();assertNull(controller.authenticate(direct,fallback));problem(fallback,401,"AUTHENTICATION_FAILED");
        assertEquals(2,count("rate_limit_bucket")); // Direct boundary fallback is one conservative shared client bucket.
        direct.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));var interrupted=new MockHttpServletResponse();
        Thread.currentThread().interrupt();
        try {
            assertNull(new AdministratorLoginController(login,cookies,Duration.ofMillis(50)).authenticate(direct,interrupted));
            assertTrue(Thread.currentThread().isInterrupted());problem(interrupted,400,"INVALID_REQUEST");
        } finally { Thread.interrupted(); }
    }
}
