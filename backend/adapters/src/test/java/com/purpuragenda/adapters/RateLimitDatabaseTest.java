package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.security.PostgresRateLimits;
import com.purpuragenda.adapters.out.security.RateKey;
import com.purpuragenda.application.RequestThrottle;
import com.purpuragenda.application.port.out.RateLimitStore;
import com.purpuragenda.application.port.out.RateLimitStore.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class RateLimitDatabaseTest {
    static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");
    @TempDir Path directory;
    ThrottleFixture fixture;
    JdbcTemplate jdbc;
    RateKey key;
    AtomicReference<Instant> clock;
    PostgresRateLimits limits;

    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void prepare() throws Exception {
        fixture = new ThrottleFixture();
        jdbc = new JdbcTemplate(fixture.source);
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) 9);
        key = new RateKey(Files.write(directory.resolve("rate.key"), bytes), "rate-test-v1");
        clock = new AtomicReference<>(T0);
        limits = new PostgresRateLimits(ThrottleFixture.at(fixture.source, clock), key);
    }
    @AfterEach void cleanup() { fixture.close(); }

    Decision admit(Instant now, Charge... charges) { clock.set(now); return limits.admit(List.of(charges)); }
    Charge charge(Kind kind) { return new Charge(kind, "synthetic-" + kind.name()); }
    int rows() { return jdbc.queryForObject("SELECT count(*) FROM rate_limit_bucket", Integer.class); }
    int count(Kind kind) { return jdbc.queryForObject("SELECT count FROM rate_limit_bucket WHERE kind=?", Integer.class, kind.name()); }
    Instant started(Kind kind) { return jdbc.queryForObject("SELECT window_started_at FROM rate_limit_bucket WHERE kind=?", java.sql.Timestamp.class, kind.name()).toInstant(); }

    @ParameterizedTest @EnumSource(Kind.class)
    void windowEdgesAreExactAndEqualityStartsAFreshWindow(Kind kind) {
        for (int i = 0; i < kind.limit(); i++) assertTrue(admit(T0, charge(kind)).allowed());
        var denied = admit(T0, charge(kind));
        assertFalse(denied.allowed());
        assertEquals(kind.window().toSeconds(), denied.retryAfterSeconds());
        assertEquals(kind.limit(), count(kind));
        // One microsecond before the window ends is still inside it; the remainder rounds up to one second.
        Instant last = T0.plus(kind.window()).minusNanos(1_000);
        var almost = admit(last, charge(kind));
        assertFalse(almost.allowed());
        assertEquals(1, almost.retryAfterSeconds());
        assertEquals(kind.limit(), count(kind));
        assertEquals(T0, started(kind));
        // Equality is expired: the same bucket now starts a new window owned by the new instant.
        var fresh = admit(T0.plus(kind.window()), charge(kind));
        assertTrue(fresh.allowed());
        assertEquals(1, count(kind));
        assertEquals(T0.plus(kind.window()), started(kind));
        assertEquals(T0.plus(kind.window()), fresh.reservations().getFirst().windowStartedAt());
        assertEquals(1, rows());
    }

    @Test void retryAfterIsTheCeilingOfTheRemainingWindowAndTheLongestDenyingBucket() {
        assertTrue(admit(T0, charge(Kind.EMAIL_COOLDOWN), charge(Kind.EMAIL_ADDRESS)).allowed());
        var cooldown = admit(T0.plusMillis(15_250), charge(Kind.EMAIL_COOLDOWN), charge(Kind.EMAIL_ADDRESS));
        assertFalse(cooldown.allowed());
        assertEquals(45, cooldown.retryAfterSeconds()); // 44.75 seconds remain
        // Fill the address bucket, then both buckets deny and the hourly window determines Retry-After.
        for (int i = 1; i < Kind.EMAIL_ADDRESS.limit(); i++) assertTrue(admit(T0.plusSeconds(60L * i), charge(Kind.EMAIL_ADDRESS)).allowed());
        var both = admit(T0.plusSeconds(60L * 5 - 30), charge(Kind.EMAIL_COOLDOWN), charge(Kind.EMAIL_ADDRESS));
        assertFalse(both.allowed());
        assertEquals(3600 - (60 * 5 - 30), both.retryAfterSeconds());
        assertEquals(1, count(Kind.EMAIL_COOLDOWN));
        assertEquals(5, count(Kind.EMAIL_ADDRESS));
    }

    @Test void multiBucketDecisionsAreAtomicAndDeniedRequestsConsumeNothing() {
        var cooldown = charge(Kind.EMAIL_COOLDOWN);
        var address = charge(Kind.EMAIL_ADDRESS);
        var network = charge(Kind.EMAIL_IP);
        var first = admit(T0, cooldown, address, network);
        assertTrue(first.allowed());
        assertEquals(List.of(Kind.EMAIL_COOLDOWN, Kind.EMAIL_ADDRESS, Kind.EMAIL_IP), first.reservations().stream().map(Reservation::kind).toList());
        assertEquals(3, rows());
        for (int i = 0; i < 5; i++) assertFalse(admit(T0.plusSeconds(i), cooldown, address, network).allowed());
        assertEquals(1, count(Kind.EMAIL_COOLDOWN));
        assertEquals(1, count(Kind.EMAIL_ADDRESS));
        assertEquals(1, count(Kind.EMAIL_IP));
        // A different address from the same network must not be charged when the network bucket is already full.
        jdbc.update("UPDATE rate_limit_bucket SET count=30 WHERE kind='EMAIL_IP'");
        var other = new Charge(Kind.EMAIL_ADDRESS, "synthetic-other");
        var denied = admit(T0.plusSeconds(120), new Charge(Kind.EMAIL_COOLDOWN, "synthetic-other"), other, network);
        assertFalse(denied.allowed());
        assertEquals(3, rows(), "a denied decision leaves no new bucket behind");
    }

    @Test void storedIdentifiersAreScopedDigestsAndNeverRawEmailOrNetwork() {
        var throttle = new RequestThrottle(limits);
        assertTrue(throttle.emailIssuance("Visitor.One@Example.test", "203.0.113.77").allowed());
        assertTrue(throttle.adminAttempt("owner@example.test", "2001:db8:1:2::9").allowed());
        List<String> everything = jdbc.queryForList("SELECT key||kind||key_id||count||window_started_at||reset_at FROM rate_limit_bucket", String.class);
        assertEquals(4, everything.size());
        for (String row : everything)
            for (String forbidden : new String[]{"visitor", "example", "203.0.113", "2001:db8", "owner"}) assertFalse(row.toLowerCase(Locale.ROOT).contains(forbidden));
        assertEquals(4, jdbc.queryForObject("SELECT count(DISTINCT key) FROM rate_limit_bucket WHERE key ~ '^[a-f0-9]{64}$' AND key_id='rate-test-v1'", Integer.class));
        assertEquals("ThrottleCharge[REDACTED]", charge(Kind.EMAIL_IP).toString());
        assertEquals("RateLimitStore[configuration=REDACTED]", limits.toString());
    }

    @Test void adminAttemptsReserveFiveAndSuccessReleasesOnlyTheSameWindow() {
        Charge account = new Charge(Kind.ADMIN_ATTEMPT, "synthetic-account");
        var held = new ArrayList<Reservation>();
        for (int i = 0; i < 5; i++) held.add(admit(T0.plusSeconds(i), account).reservations().getFirst());
        var denied = admit(T0.plusSeconds(10), account);
        assertFalse(denied.allowed());
        assertEquals(890, denied.retryAfterSeconds());
        assertTrue(limits.release(held.get(2)));
        assertEquals(4, count(Kind.ADMIN_ATTEMPT));
        assertTrue(admit(T0.plusSeconds(11), account).allowed());
        assertEquals(5, count(Kind.ADMIN_ATTEMPT));
        // Reset window: the old reservation can no longer reduce the new window's count.
        Instant reset = T0.plus(Kind.ADMIN_ATTEMPT.window());
        var fresh = admit(reset, account);
        assertTrue(fresh.allowed());
        assertFalse(limits.release(held.getFirst()));
        assertEquals(1, count(Kind.ADMIN_ATTEMPT));
        assertTrue(limits.release(fresh.reservations().getFirst()));
        assertEquals(0, count(Kind.ADMIN_ATTEMPT));
        assertFalse(limits.release(fresh.reservations().getFirst()), "an empty bucket never goes negative");
        assertEquals(0, count(Kind.ADMIN_ATTEMPT));
        var unknown = new Reservation(Kind.ADMIN_ATTEMPT, "f".repeat(64), reset);
        assertFalse(limits.release(unknown));
    }

    @Test void failureBetweenBucketUpdatesRollsBackEveryCountAndHidesProviderText() {
        var calls = new AtomicInteger();
        var failing = new PostgresRateLimits(ThrottleFixture.failing(ThrottleFixture.at(fixture.source, clock), (sql, call) -> {
            if (sql.startsWith("UPDATE rate_limit_bucket SET count=?") && calls.incrementAndGet() == 2) throw new SQLException("synthetic-private-diagnostic", "XX000");
        }), key);
        assertTrue(limits.admit(List.of(charge(Kind.EMAIL_ADDRESS))).allowed());
        var failure = assertThrows(IllegalStateException.class, () -> failing.admit(List.of(charge(Kind.EMAIL_COOLDOWN), charge(Kind.EMAIL_ADDRESS), charge(Kind.EMAIL_IP))));
        assertEquals("RATE_LIMIT_STORAGE_FAILED", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(1, rows(), "placeholder rows and the first increment rolled back");
        assertEquals(1, count(Kind.EMAIL_ADDRESS));
        assertTrue(failing.admit(List.of(charge(Kind.EMAIL_COOLDOWN), charge(Kind.EMAIL_ADDRESS), charge(Kind.EMAIL_IP))).allowed());
        assertEquals(2, count(Kind.EMAIL_ADDRESS));
        var release = new PostgresRateLimits(ThrottleFixture.failing(fixture.source, (sql, call) -> { throw new SQLException("synthetic-private-diagnostic"); }), key);
        var released = assertThrows(IllegalStateException.class, () -> release.release(new Reservation(Kind.EMAIL_ADDRESS, "a".repeat(64), T0)));
        assertEquals("RATE_LIMIT_STORAGE_FAILED", released.getMessage());
        assertNull(released.getCause());
    }

    @Test void invalidChargeSetsFailBeforeStorageAndUnavailableStorageFailsClosed() {
        var invalidSets = new ArrayList<List<Charge>>();
        invalidSets.add(null);
        invalidSets.add(List.of());
        invalidSets.add(java.util.stream.IntStream.range(0, 9).mapToObj(i -> new Charge(Kind.EMAIL_IP, "synthetic-" + i)).toList());
        for (List<Charge> invalid : invalidSets)
            assertEquals("INVALID_THROTTLE_CHARGES", assertThrows(IllegalArgumentException.class, () -> limits.admit(invalid)).getMessage());
        var duplicate = List.of(charge(Kind.EMAIL_IP), charge(Kind.EMAIL_IP));
        assertEquals("DUPLICATE_THROTTLE_CHARGE", assertThrows(IllegalArgumentException.class, () -> limits.admit(duplicate)).getMessage());
        assertEquals(0, rows());
        assertThrows(NullPointerException.class, () -> new PostgresRateLimits(null, key));
        assertThrows(NullPointerException.class, () -> new PostgresRateLimits(fixture.source, null));
        assertThrows(NullPointerException.class, () -> limits.release(null));
        var unavailable = new PostgresRateLimits(new org.springframework.jdbc.datasource.AbstractDataSource() {
            public java.sql.Connection getConnection() throws SQLException { throw new SQLException("synthetic-private-diagnostic"); }
            public java.sql.Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        }, key);
        var failure = assertThrows(IllegalStateException.class, () -> unavailable.admit(List.of(charge(Kind.EMAIL_IP))));
        assertEquals("RATE_LIMIT_STORAGE_FAILED", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test void databaseRejectsCountsWindowsDigestsAndKindsOutsidePolicy() {
        String good = "a".repeat(64);
        String insert = "INSERT INTO rate_limit_bucket(key,kind,key_id,count,window_started_at,reset_at) VALUES (?,?,?,?,?::timestamptz,?::timestamptz)";
        jdbc.update(insert, good, "EMAIL_ADDRESS", "k1", 5, "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z");
        Object[][] invalid = {
            {"b".repeat(64), "EMAIL_ADDRESS", "k1", 6, "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z"},
            {"c".repeat(64), "EMAIL_COOLDOWN", "k1", 2, "2026-11-01T10:00:00Z", "2026-11-01T10:01:00Z"},
            {"d".repeat(64), "EMAIL_COOLDOWN", "k1", 1, "2026-11-01T10:00:00Z", "2026-11-01T10:02:00Z"},
            {"e".repeat(64), "EMAIL_IP", "k1", -1, "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z"},
            {"f".repeat(64), "ADMIN_ATTEMPT", "k1", 1, "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z"},
            {"1".repeat(64), "AVAILABILITY_IP", "k1", 121, "2026-11-01T10:00:00Z", "2026-11-01T10:01:00Z"},
            {"2".repeat(64), "UNKNOWN", "k1", 0, "2026-11-01T10:00:00Z", "2026-11-01T10:01:00Z"},
            {"short", "EMAIL_IP", "k1", 0, "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z"},
            {"3".repeat(64), "EMAIL_IP", "bad id", 0, "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z"},
            {"4".repeat(64), "EMAIL_IP", "k1", 0, "infinity", "infinity"},
            {good, "EMAIL_ADDRESS", "k1", 1, "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z"},
        };
        for (Object[] row : invalid) assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(insert, row));
        assertEquals(1, rows());
    }

    @Test void twentyIndependentConnectionsNeverDoubleCountOrExceedTheLimit() throws Exception {
        var real = new PostgresRateLimits(fixture.source("p17_race"), key);
        var throttle = new RequestThrottle(real);
        int workers = 20, perWorker = 7; // 140 attempts against a limit of 120 per minute
        var allowed = new AtomicInteger();
        var denied = new AtomicInteger();
        var barrier = new CyclicBarrier(workers);
        try (var pool = Executors.newFixedThreadPool(workers)) {
            var futures = new ArrayList<Future<?>>();
            for (int w = 0; w < workers; w++) futures.add(pool.submit(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                for (int i = 0; i < perWorker; i++) {
                    var admission = throttle.availability("203.0.113.5");
                    if (admission.allowed()) allowed.incrementAndGet();
                    else { denied.incrementAndGet(); assertTrue(admission.retryAfterSeconds() >= 1 && admission.retryAfterSeconds() <= 60); }
                }
                return null;
            }));
            for (var future : futures) future.get(60, TimeUnit.SECONDS);
        }
        assertEquals(120, allowed.get());
        assertEquals(20, denied.get());
        assertEquals(120, count(Kind.AVAILABILITY_IP));
        assertEquals(1, rows());
    }

    @Test void concurrentAdminAttemptsAreReservedBeforeWorkSoOnlyFiveProceed() throws Exception {
        var throttle = new RequestThrottle(new PostgresRateLimits(fixture.source("p17_admin_race"), key));
        var barrier = new CyclicBarrier(20);
        var attempts = new ConcurrentLinkedQueue<RequestThrottle.AdminAttempt>();
        var retries = new ConcurrentLinkedQueue<Long>();
        try (var pool = Executors.newFixedThreadPool(20)) {
            var futures = new ArrayList<Future<?>>();
            for (int w = 0; w < 20; w++) futures.add(pool.submit(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                var admission = throttle.adminAttempt("owner@example.test", "203.0.113.5");
                if (admission.allowed()) attempts.add(admission.attempt()); else retries.add(admission.retryAfterSeconds());
                return null;
            }));
            for (var future : futures) future.get(60, TimeUnit.SECONDS);
        }
        assertEquals(5, attempts.size());
        assertEquals(15, retries.size());
        assertTrue(retries.stream().allMatch(seconds -> seconds >= 899 && seconds <= 900));
        assertEquals(5, count(Kind.ADMIN_ATTEMPT));
        // Concurrent successes each return exactly one slot.
        try (var pool = Executors.newFixedThreadPool(5)) {
            var futures = attempts.stream().map(attempt -> pool.submit(attempt::succeeded)).toList();
            for (var future : futures) assertTrue(future.get(30, TimeUnit.SECONDS));
        }
        assertEquals(0, count(Kind.ADMIN_ATTEMPT));
    }

    @Test void additiveEnrollmentBudgetUpgradePreservesExistingWindowsAndFailsReadinessUntilApplied() throws Exception {
        fixture.migration.newSchema();fixture.migration.migrate(16); // Through017; no014 reminder migration yet.
        Properties settings=new Properties();settings.setProperty("currentSchema",fixture.migration.schema+",public");
        fixture.source.setConnectionProperties(settings);
        var readiness=new com.purpuragenda.adapters.out.postgres.DatabaseReadiness(fixture.source,
                new com.purpuragenda.adapters.out.postgres.PostgresScheduling(fixture.source));
        assertEquals(org.springframework.boot.health.contributor.Status.DOWN,readiness.health().getStatus());
        assertTrue(admit(T0,charge(Kind.ACCOUNT_ATTEMPT)).allowed());
        fixture.migration.migrate(0);fixture.migration.migrate(0);
        assertEquals(org.springframework.boot.health.contributor.Status.UP,readiness.health().getStatus());
        assertEquals(1,count(Kind.ACCOUNT_ATTEMPT));
        assertEquals(19,fixture.migration.scalar(MigrationTest.MIGRATOR,"SELECT count(*) FROM databasechangelog"));
        var admission=new RequestThrottle(limits).accountEnrollment("203.0.113.7");
        assertTrue(admission.allowed());assertEquals(1,count(Kind.ACCOUNT_ENROLLMENT_IP));
        clock.set(T0.plusSeconds(900));
        var fresh=new RequestThrottle(limits).accountEnrollment("203.0.113.7");
        assertFalse(admission.attempt().succeeded());assertEquals(1,count(Kind.ACCOUNT_ENROLLMENT_IP));
        assertTrue(fresh.attempt().succeeded());assertEquals(0,count(Kind.ACCOUNT_ENROLLMENT_IP));
    }

    @Test void concurrentEnrollmentNetworksReserveOnlyFiveBeforeCredentialWork() throws Exception {
        var throttle = new RequestThrottle(new PostgresRateLimits(fixture.source("p18_enrollment_race"), key));
        var barrier = new CyclicBarrier(20);
        var attempts = new ConcurrentLinkedQueue<RequestThrottle.AdminAttempt>();
        var retries = new ConcurrentLinkedQueue<Long>();
        try (var pool = Executors.newFixedThreadPool(20)) {
            var futures = new ArrayList<Future<?>>();
            for (int w = 0; w < 20; w++) futures.add(pool.submit(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                var admission = throttle.accountEnrollment("2001:db8:1234:5678::" + Integer.toHexString(ThreadLocalRandom.current().nextInt(1, 60000)));
                if (admission.allowed()) attempts.add(admission.attempt()); else retries.add(admission.retryAfterSeconds());
                return null;
            }));
            for (var future : futures) future.get(60, TimeUnit.SECONDS);
        }
        assertEquals(5, attempts.size());
        assertEquals(15, retries.size());
        assertTrue(retries.stream().allMatch(seconds -> seconds >= 899 && seconds <= 900));
        assertEquals(5, count(Kind.ACCOUNT_ENROLLMENT_IP));
        // Concurrent successes each return exactly one slot.
        try (var pool = Executors.newFixedThreadPool(5)) {
            var futures = attempts.stream().map(attempt -> pool.submit(attempt::succeeded)).toList();
            for (var future : futures) assertTrue(future.get(30, TimeUnit.SECONDS));
        }
        assertEquals(0, count(Kind.ACCOUNT_ENROLLMENT_IP));
    }

    @Test void sharedBucketPairsSubmittedInOppositeOrdersNeverDeadlockAndCountExactly() throws Exception {
        var real = new PostgresRateLimits(fixture.source("p17_pairs"), key);
        int rounds = 10, workers = 20;
        for (int round = 0; round < rounds; round++) {
            // Two buckets shared by every worker; half submit [A,B], half [B,A], so unsorted locking would form cycles.
            var a = new Charge(Kind.EMAIL_IP, "synthetic-shared-a-" + round);
            var b = new Charge(Kind.AVAILABILITY_IP, "synthetic-shared-b-" + round);
            var barrier = new CyclicBarrier(workers);
            try (var pool = Executors.newFixedThreadPool(workers)) {
                var futures = new ArrayList<Future<Decision>>();
                for (int w = 0; w < workers; w++) {
                    var pair = w % 2 == 0 ? List.of(a, b) : List.of(b, a);
                    futures.add(pool.submit(() -> { barrier.await(30, TimeUnit.SECONDS); return real.admit(pair); }));
                }
                for (var future : futures) {
                    var decision = future.get(60, TimeUnit.SECONDS);
                    assertTrue(decision.allowed());
                    assertEquals(pair(decision), 2);
                }
            }
        }
        assertEquals(2 * rounds, rows());
        assertEquals(rounds, jdbc.queryForObject("SELECT count(*) FROM rate_limit_bucket WHERE kind='EMAIL_IP' AND count=20", Integer.class));
        assertEquals(rounds, jdbc.queryForObject("SELECT count(*) FROM rate_limit_bucket WHERE kind='AVAILABILITY_IP' AND count=20", Integer.class));
        // Reservations follow input order even though locks follow digest order.
        var forward = real.admit(List.of(new Charge(Kind.EMAIL_IP, "synthetic-order-x"), new Charge(Kind.AVAILABILITY_IP, "synthetic-order-y")));
        assertEquals(List.of(Kind.EMAIL_IP, Kind.AVAILABILITY_IP), forward.reservations().stream().map(Reservation::kind).toList());
    }
    int pair(Decision decision) { return decision.reservations().size(); }

    @Test void oppositeChargeOrdersOverSharedBucketsNeitherDeadlockNorLoseCounts() throws Exception {
        var real = new PostgresRateLimits(fixture.source("p17_order"), key);
        var shared = new Charge(Kind.EMAIL_IP, "synthetic-shared-network");
        var barrier = new CyclicBarrier(20);
        try (var pool = Executors.newFixedThreadPool(20)) {
            var futures = new ArrayList<Future<Decision>>();
            for (int w = 0; w < 20; w++) {
                int worker = w;
                futures.add(pool.submit(() -> {
                    var unique = new Charge(Kind.EMAIL_ADDRESS, "synthetic-address-" + worker);
                    var pair = worker % 2 == 0 ? List.of(shared, unique) : List.of(unique, shared);
                    barrier.await(30, TimeUnit.SECONDS);
                    return real.admit(pair);
                }));
            }
            for (var future : futures) assertTrue(future.get(60, TimeUnit.SECONDS).allowed());
        }
        assertEquals(20, count(Kind.EMAIL_IP));
        assertEquals(21, rows());
        assertEquals(20, jdbc.queryForObject("SELECT count(*) FROM rate_limit_bucket WHERE kind='EMAIL_ADDRESS' AND count=1", Integer.class));
    }

    @Test void expiredBucketsAreResetOnceUnderConcurrentWindowRollover() throws Exception {
        var real = new PostgresRateLimits(fixture.source("p17_rollover"), key);
        var charge = charge(Kind.EMAIL_ADDRESS);
        jdbc.update("INSERT INTO rate_limit_bucket(key,kind,key_id,count,window_started_at,reset_at) SELECT ?,?,?,5,?::timestamptz,?::timestamptz",
                digest(charge), "EMAIL_ADDRESS", key.keyId(), "2020-01-01T10:00:00Z", "2020-01-01T11:00:00Z");
        var barrier = new CyclicBarrier(10);
        var allowed = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(10)) {
            var futures = new ArrayList<Future<?>>();
            for (int w = 0; w < 10; w++) futures.add(pool.submit(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                if (real.admit(List.of(charge)).allowed()) allowed.incrementAndGet();
                return null;
            }));
            for (var future : futures) future.get(60, TimeUnit.SECONDS);
        }
        assertEquals(5, allowed.get(), "the expired window reopens exactly once with its five-request limit");
        assertEquals(5, count(Kind.EMAIL_ADDRESS));
    }

    String digest(Charge charge) {
        try { return RateKeyTest.digest(key, charge.kind(), charge.subject()); } catch (Exception failure) { throw new AssertionError(failure); }
    }
}
