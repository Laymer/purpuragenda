package com.purpuragenda.adapters;

import com.purpuragenda.adapters.in.rest.*;
import com.purpuragenda.adapters.out.postgres.PostgresScheduling;
import com.purpuragenda.adapters.out.security.PostgresRateLimits;
import com.purpuragenda.adapters.out.security.RateKey;
import com.purpuragenda.application.RequestThrottle;
import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.port.out.SessionStore;
import com.purpuragenda.application.scheduling.PublicScheduling;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Timeout(180)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class PublicHttpPostgresTest {
    static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");
    static final Duration MICRO = Duration.ofNanos(1_000);
    @TempDir Path directory;
    ThrottleFixture fixture;
    JdbcTemplate jdbc;
    AtomicReference<Instant> clock;
    MockMvc mvc;
    String serviceId, path;

    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void prepare() throws Exception {
        fixture = new ThrottleFixture();
        jdbc = new JdbcTemplate(fixture.source);
        clock = new AtomicReference<>(T0);
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) 5);
        var key = new RateKey(Files.write(directory.resolve("rate.key"), bytes), "rate-http-v1");
        var limits = new PostgresRateLimits(ThrottleFixture.at(fixture.source, clock), key);
        var sessions = (SessionStore) Proxy.newProxyInstance(SessionStore.class.getClassLoader(), new Class<?>[]{SessionStore.class},
                (p, m, a) -> { throw new AssertionError("public GET never touches sessions"); });
        var controller = new PublicController(new PublicScheduling(new PostgresScheduling(fixture.source)));
        mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(new ApiGuardFilter("https://bookings.example.com",
                new SessionAccess(sessions), SessionCookies.operational(), new ClientAddress(List.of("10.0.0.2"))),
                        new AvailabilityThrottleFilter(new RequestThrottle(limits))).build();
        serviceId = jdbc.queryForObject("SELECT id::text FROM service WHERE duration_minutes=60 AND active", String.class);
        LocalDate day = LocalDate.now().plusDays(10);
        path = "/api/v1/availability?serviceId=" + serviceId + "&from=" + day + "&to=" + day + "&displayZone=Europe/Brussels";
    }
    @AfterEach void cleanup() { fixture.close(); }

    int code(String proxyClient, Instant now) throws Exception {
        clock.set(now);
        var request = get(path).with(r -> { r.setRemoteAddr("10.0.0.2"); return r; });
        if (proxyClient != null) request = request.header(ClientAddress.HEADER, proxyClient);
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test void seededCatalogAndAdvisoryAvailabilityComeFromPostgresWithTheContractShape() throws Exception {
        var services = mvc.perform(get("/api/v1/services")).andExpect(status().isOk()).andReturn().getResponse();
        assertEquals(2, services.getContentAsString().split("\"id\":", -1).length - 1);
        assertTrue(services.getContentAsString().contains("\"id\":\"" + serviceId + "\""));
        assertTrue(services.getHeader("ETag").matches("\"[0-9a-f]{32}\""));
        var response = mvc.perform(get(path).with(r -> { r.setRemoteAddr("10.0.0.2"); return r; }).header(ClientAddress.HEADER, "203.0.113.9"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String body = response.getContentAsString();
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertTrue(body.contains("\"businessZone\":\"Europe/Brussels\"") && body.contains("\"slots\":[{\"startInstant\":"), body);
        assertTrue(body.contains("\"reservedEndInstant\":") && body.contains("\"offset\":\"+0"), body);
        assertFalse(body.contains("\"start\":") || body.contains("displayLocal\""));
    }

    @Test void windowEdgesAreExactAgainstTheDatabaseClockAndEachClientHasItsOwnBucket() throws Exception {
        for (int i = 0; i < 120; i++) assertEquals(200, code("203.0.113.9", T0));
        var denied = mvc.perform(get(path).with(r -> { r.setRemoteAddr("10.0.0.2"); return r; }).header(ClientAddress.HEADER, "203.0.113.9")).andReturn().getResponse();
        assertEquals(429, denied.getStatus());
        assertEquals("60", denied.getHeader("Retry-After"));
        // One microsecond before the window ends the client still waits one second; equality opens a fresh window.
        assertEquals(429, code("203.0.113.9", T0.plusSeconds(60).minus(MICRO)));
        clock.set(T0.plusSeconds(60).minus(MICRO));
        assertEquals("1", mvc.perform(get(path).with(r -> { r.setRemoteAddr("10.0.0.2"); return r; }).header(ClientAddress.HEADER, "203.0.113.9"))
                .andReturn().getResponse().getHeader("Retry-After"));
        assertEquals(200, code("203.0.113.10", T0.plusSeconds(1)), "another address has its own bucket");
        assertEquals(200, code("203.0.113.9", T0.plusSeconds(60)));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM rate_limit_bucket", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count FROM rate_limit_bucket WHERE count<120 AND window_started_at=?::timestamptz AND reset_at=?::timestamptz+interval '60 seconds' ORDER BY count LIMIT 1", Integer.class, T0.plusSeconds(60).toString(), T0.plusSeconds(60).toString()));
    }

    @Test void rotatingSpoofedHeadersFromAnUntrustedPeerNeverEscapesAndNoRawAddressIsStored() throws Exception {
        clock.set(T0);
        int last = 0;
        for (int i = 0; i < 121; i++)
            last = mvc.perform(get(path).with(r -> { r.setRemoteAddr("198.51.100.4"); return r; }).header(ClientAddress.HEADER, "203.0.113." + (i % 200)))
                    .andReturn().getResponse().getStatus();
        assertEquals(429, last);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM rate_limit_bucket", Integer.class));
        for (String row : jdbc.queryForList("SELECT key||kind||key_id FROM rate_limit_bucket", String.class))
            assertFalse(row.contains("198.51.100") || row.contains("203.0.113"));
    }

    @Test void validationFailuresAreChargedToTheDatabaseBucketToo() throws Exception {
        clock.set(T0);
        for (int i = 0; i < 120; i++) mvc.perform(get("/api/v1/availability")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/availability")).andExpect(status().isTooManyRequests());
        assertEquals(120, jdbc.queryForObject("SELECT count FROM rate_limit_bucket", Integer.class));
    }
}
