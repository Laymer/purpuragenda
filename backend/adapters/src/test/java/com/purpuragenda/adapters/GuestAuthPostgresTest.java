package com.purpuragenda.adapters;

import com.purpuragenda.adapters.in.rest.*;
import com.purpuragenda.adapters.out.security.*;
import com.purpuragenda.application.*;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real HTTP routing, guard, throttles, delivery, challenge consumption and atomic session rotation on PostgreSQL. */
@Timeout(180)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class GuestAuthPostgresTest {
    static final String ORIGIN="https://bookings.example.com";
    static final Instant T0=Instant.parse("2026-11-01T10:00:00Z");
    @TempDir Path directory;
    GuestAccessDatabaseTest f=new GuestAccessDatabaseTest();
    JdbcTemplate jdbc;MockMvc mvc;
    final AtomicReference<Instant> rateClock=new AtomicReference<>(T0),sessionClock=new AtomicReference<>();

    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void setup() throws Exception {
        f.directory=directory;f.setup();jdbc=new JdbcTemplate(f.source);
        byte[] bytes=new byte[32];Arrays.fill(bytes,(byte)6);
        var limits=new PostgresRateLimits(ThrottleFixture.at(f.source,rateClock),new RateKey(Files.write(directory.resolve("rate.key"),bytes),"rate-auth-v1"));
        var sessions=new PostgresSessions(ThrottleFixture.at(f.source,sessionClock));
        var access=new SessionAccess(sessions);var cookies=SessionCookies.operational();
        var proofs=new GuestProofs(new RequestThrottle(limits),f.delivery,new GuestRecovery(new PostgresGuestRecoveryLookup(f.source),f.delivery));
        mvc=MockMvcBuilders.standaloneSetup(new SessionController(access,cookies),new GuestAuthController(proofs,access,cookies,Duration.ZERO))
                .addFilters(new ApiGuardFilter(ORIGIN,access,cookies,new ClientAddress(List.of("10.0.0.2")))).build();
    }
    @AfterEach void cleanup() { f.cleanup(); }

    record Browser(String cookie,String csrf,String browserHash) { }
    Browser boot() throws Exception {
        var response=mvc.perform(post("/api/v1/session/bootstrap").header("Origin",ORIGIN)).andReturn().getResponse();
        assertEquals(200,response.getStatus());
        String header=response.getHeader("Set-Cookie");
        String cookie=header.substring(header.indexOf('=')+1,header.indexOf(';'));
        String csrf=response.getContentAsString().replaceAll(".*\"csrfToken\":\"([^\"]+)\".*","$1");
        return new Browser(cookie,csrf,jdbc.queryForObject("SELECT browser_hash FROM app_session ORDER BY issued_at DESC LIMIT 1",String.class));
    }
    MockHttpServletRequestBuilder json(Browser b,String path,String body) {
        return post(URI.create(path)).header("Origin",ORIGIN).header("X-CSRF-Token",b.csrf()).cookie(new Cookie("__Host-purpuragenda",b.cookie()))
                .contentType(MediaType.APPLICATION_JSON).content(body).with(r -> { r.setRemoteAddr("198.51.100.4"); return r; });
    }
    MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception { return mvc.perform(request).andReturn().getResponse(); }
    String proofToken() {
        var lease=f.queue.claimAuth().orElseThrow();var message=f.delivery.prepare(lease).orElseThrow();
        assertTrue(f.queue.sent(lease));return message.text().split("#token=",2)[1].split("&",2)[0];
    }
    String redeemBody(String token,String purpose) { return "{\"token\":\""+token+"\",\"purpose\":\""+purpose+"\"}"; }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }

    @Test void createProofRedeemsOnceRotatesCookieAndCsrfAndKeepsTheBrowserBinding() throws Exception {
        var browser=boot();
        var accepted=send(json(browser,"/api/v1/guest/create-proofs","{\"email\":\"Guest@Example.TEST\"}"));
        assertEquals(202,accepted.getStatus());assertEquals(1,count("email_challenge"));
        assertEquals(browser.browserHash(),jdbc.queryForObject("SELECT browser_hash FROM email_challenge",String.class));
        String token=proofToken();
        var redeemed=send(json(browser,"/api/v1/guest/redemptions",redeemBody(token,"CREATE")));
        assertEquals(200,redeemed.getStatus());
        String header=redeemed.getHeader("Set-Cookie");String rotated=header.substring(header.indexOf('=')+1,header.indexOf(';'));
        assertNotEquals(browser.cookie(),rotated);
        String body=redeemed.getContentAsString();
        assertTrue(body.startsWith("{\"role\":\"GUEST\""),body);
        String newCsrf=body.replaceAll(".*\"csrfToken\":\"([^\"]+)\".*","$1");assertNotEquals(browser.csrf(),newCsrf);
        assertEquals(browser.browserHash(),jdbc.queryForObject("SELECT browser_hash FROM app_session WHERE kind='GUEST'",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app_session WHERE revoked_at IS NOT NULL",Integer.class));
        // The old bearer is dead; the replayed proof is indistinguishable from any other invalid proof.
        assertEquals(401,send(json(browser,"/api/v1/guest/redemptions",redeemBody(token,"CREATE"))).getStatus());
        var guest=new Browser(rotated,newCsrf,browser.browserHash());
        var replay=send(json(guest,"/api/v1/guest/redemptions",redeemBody(token,"CREATE")));
        assertEquals(400,replay.getStatus());assertTrue(replay.getContentAsString().contains("PROOF_INVALID"));
        assertEquals(1,count("access_grant"));
    }
    @Test void aProofIsBoundToTheBrowserThatRequestedItAndAFailedAttemptConsumesNothing() throws Exception {
        var first=boot();
        send(json(first,"/api/v1/guest/create-proofs","{\"email\":\"guest@example.test\"}"));String token=proofToken();
        var second=boot();
        assertNotEquals(first.browserHash(),second.browserHash());
        var denied=send(json(second,"/api/v1/guest/redemptions",redeemBody(token,"CREATE")));
        assertEquals(400,denied.getStatus());assertTrue(denied.getContentAsString().contains("PROOF_INVALID"));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM email_challenge",Timestamp.class));assertEquals(0,count("access_grant"));
        assertEquals(400,send(json(first,"/api/v1/guest/redemptions",redeemBody(token,"MANAGE"))).getStatus(),"wrong purpose");
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM email_challenge",Timestamp.class));
        assertEquals(200,send(json(first,"/api/v1/guest/redemptions",redeemBody(token,"CREATE"))).getStatus());
    }
    @Test void expiryIsExclusiveAtTheExactDatabaseInstant() throws Exception {
        var early=boot();
        send(json(early,"/api/v1/guest/create-proofs","{\"email\":\"early@example.test\"}"));String earlyToken=proofToken();
        Instant earlyExpiry=jdbc.queryForObject("SELECT expires_at FROM email_challenge WHERE email_key='early@example.test'",Timestamp.class).toInstant();
        var late=boot();
        rateClock.set(T0.plusSeconds(3600));
        send(json(late,"/api/v1/guest/create-proofs","{\"email\":\"late@example.test\"}"));String lateToken=proofToken();
        Instant lateExpiry=jdbc.queryForObject("SELECT expires_at FROM email_challenge WHERE email_key='late@example.test'",Timestamp.class).toInstant();
        sessionClock.set(earlyExpiry);
        assertEquals(400,send(json(early,"/api/v1/guest/redemptions",redeemBody(earlyToken,"CREATE"))).getStatus(),"equality is expired");
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM email_challenge WHERE email_key='early@example.test'",Timestamp.class));
        sessionClock.set(lateExpiry.minus(Duration.ofNanos(1_000)));
        assertEquals(200,send(json(late,"/api/v1/guest/redemptions",redeemBody(lateToken,"CREATE"))).getStatus(),"one microsecond earlier is live");
    }
    @Test void emailLimitsAreExactAcrossCooldownHourlyAddressAndNetworkWindows() throws Exception {
        var b=boot();String create="/api/v1/guest/create-proofs";
        assertEquals(202,send(json(b,create,"{\"email\":\"one@example.test\"}")).getStatus());
        var cooldown=send(json(b,create,"{\"email\":\"one@example.test\"}"));
        assertEquals(429,cooldown.getStatus());assertEquals("60",cooldown.getHeader("Retry-After"));
        rateClock.set(T0.plusSeconds(60).minus(Duration.ofNanos(1_000)));
        assertEquals("1",send(json(b,create,"{\"email\":\"one@example.test\"}")).getHeader("Retry-After"));
        assertEquals(1,count("email_challenge"));
        for(int i=1;i<5;i++) { rateClock.set(T0.plusSeconds(60L*i));assertEquals(202,send(json(b,create,"{\"email\":\"one@example.test\"}")).getStatus(),"attempt "+i); }
        rateClock.set(T0.plusSeconds(60L*5));
        var hourly=send(json(b,create,"{\"email\":\"one@example.test\"}"));
        assertEquals(429,hourly.getStatus());assertEquals(String.valueOf(3600-300),hourly.getHeader("Retry-After"));
        assertEquals(5,count("email_challenge"));
        rateClock.set(T0.plusSeconds(3600));
        assertEquals(202,send(json(b,create,"{\"email\":\"one@example.test\"}")).getStatus(),"hour window equality reopens");
        // Thirty per hour per network across addresses.
        rateClock.set(T0.plusSeconds(7200));int accepted=0;
        for(int i=0;i<31;i++) if(send(json(b,create,"{\"email\":\"net"+i+"@example.test\"}")).getStatus()==202) accepted++;
        assertEquals(30,accepted);
        // Another network is unaffected, and rotating spoofed headers from an untrusted peer never opens one.
        assertEquals(202,send(json(b,create,"{\"email\":\"other@example.test\"}").with(r -> { r.setRemoteAddr("10.0.0.2"); return r; }).header(ClientAddress.HEADER,"203.0.113.9")).getStatus());
        assertEquals(429,send(json(b,create,"{\"email\":\"spoof@example.test\"}").header(ClientAddress.HEADER,"203.0.113.10")).getStatus());
        for(String row:jdbc.queryForList("SELECT key||kind||key_id FROM rate_limit_bucket",String.class)) assertFalse(row.contains("example.test") || row.contains("198.51.100"));
    }
    @Test void managementRequestsAreIndistinguishableAndOnlyAnExactCurrentContactQueuesAProof() throws Exception {
        UUID booking=f.database.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        String reference=f.value("SELECT reference FROM booking WHERE id=?",booking);
        var b=boot();String path="/api/v1/guest/management-proofs";
        var unknown=send(json(b,path,"{\"reference\":\"UNKNOWN0001\",\"email\":\"synthetic@example.test\"}"));
        rateClock.set(T0.plusSeconds(120));
        var wrong=send(json(b,path,"{\"reference\":\""+reference+"\",\"email\":\"wrong@example.test\"}"));
        rateClock.set(T0.plusSeconds(240));
        var known=send(json(b,path,"{\"reference\":\""+reference+"\",\"email\":\"synthetic@example.test\"}"));
        for(var response:List.of(unknown,wrong,known)) { assertEquals(202,response.getStatus());assertEquals("{\"accepted\":true}",response.getContentAsString()); }
        assertEquals(1,count("email_challenge"));assertEquals("MANAGE",f.value("SELECT purpose FROM email_challenge"));
        // Redeeming the management proof yields a guest session without a browser-bound creation challenge.
        String token=proofToken();
        assertEquals(200,send(json(b,"/api/v1/guest/redemptions",redeemBody(token,"MANAGE"))).getStatus());
        assertEquals(1,count("access_grant"));
    }
    @Test void everyUrlSpellingReachesTheSameGuardAndTheSameBucket() throws Exception {
        var b=boot();
        for(String path:new String[]{"/api/v1/guest/%63reate-proofs","/api/v1/guest/create-proofs;x=1"}) {
            assertEquals(403,send(post(URI.create(path)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x@example.test\"}")).getStatus());
            assertEquals(403,send(post(URI.create(path)).header("Origin",ORIGIN).cookie(new Cookie("__Host-purpuragenda",b.cookie())).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x@example.test\"}")).getStatus());
        }
        assertEquals(202,send(json(b,"/api/v1/guest/%63reate-proofs","{\"email\":\"spell@example.test\"}")).getStatus());
        assertEquals(429,send(json(b,"/api/v1/guest/create-proofs;x=1","{\"email\":\"spell@example.test\"}")).getStatus(),"same address bucket via another spelling");
    }
}
