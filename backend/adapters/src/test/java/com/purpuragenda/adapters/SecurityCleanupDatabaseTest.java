package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.security.PostgresSecurityCleanup;
import com.purpuragenda.adapters.out.security.Secrets;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(180)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class SecurityCleanupDatabaseTest {
    static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    static final Duration HOUR = Duration.ofHours(1), DAY = Duration.ofHours(24);
    static final Duration MICRO = Duration.ofNanos(1_000);
    ThrottleFixture fixture;
    JdbcTemplate jdbc;
    AtomicReference<Instant> clock;
    PostgresSecurityCleanup cleanup;
    UUID customer;

    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void prepare() {
        fixture = new ThrottleFixture();
        jdbc = new JdbcTemplate(fixture.source);
        clock = new AtomicReference<>(NOW);
        cleanup = new PostgresSecurityCleanup(ThrottleFixture.at(fixture.source, clock));
        customer = UUID.randomUUID();
        jdbc.update("INSERT INTO customer_identity(id,email_key,verified_at) VALUES (?,?,?)", customer, "synthetic@example.test", ts(NOW.minus(Duration.ofDays(400))));
    }
    @AfterEach void close() { fixture.close(); }

    static Timestamp ts(Instant value) { return Timestamp.from(value); }
    static String hex(UUID id) { return Secrets.hash(id.toString()); }
    static UUID id(int number) { return new UUID(0, number); }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    boolean present(String table, UUID id) { return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id=?", Integer.class, id) == 1; }

    // ---- builders -------------------------------------------------------------------------------------------
    UUID session(String kind, Instant issued, Instant lastSeen, Instant revoked) { return session(UUID.randomUUID(), kind, issued, lastSeen, revoked, null, null); }
    UUID session(UUID id, String kind, Instant issued, Instant lastSeen, Instant revoked, UUID grant, UUID admin) {
        Duration life = kind.equals("ADMIN") ? Duration.ofHours(8) : Duration.ofHours(2);
        jdbc.update("""
            INSERT INTO app_session(id,token_hash,browser_hash,csrf_hash,kind,grant_id,customer_id,admin_id,admin_security_version,
              issued_at,last_seen_at,absolute_expires_at,authenticated_at,revoked_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, id, hex(id), hex(id), hex(id), kind, grant, grant == null ? null : customer, admin, admin == null ? null : 0L,
                ts(issued), ts(lastSeen), ts(issued.plus(life)), kind.equals("ANONYMOUS") ? null : ts(issued), revoked == null ? null : ts(revoked));
        return id;
    }
    UUID admin() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO admin_user(id,email_key,password_hash,totp_secret,enrolled) VALUES (?,?,?,?,true)", id, "owner@example.test", "synthetic-hash", "synthetic-secret");
        return id;
    }
    UUID challenge(UUID id, Instant expires) {
        jdbc.update("INSERT INTO email_challenge(id,token_hash,email_key,purpose,expires_at) VALUES (?,?,?,'CREATE',?)", id, hex(id), "synthetic@example.test", ts(expires));
        return id;
    }
    /** Revoked grants skip the live-shape invariant, which is exactly the state retention cleanup removes. */
    UUID grant(UUID id, String kind, UUID challenge, UUID parent, Instant expires, Instant revoked) {
        jdbc.update("INSERT INTO access_grant(id,customer_id,kind,expires_at,revoked_at,issued_at,browser_hash,challenge_id,parent_grant_id) VALUES (?,?,?,?,?,?,?,?,?)",
                id, customer, kind, ts(expires), ts(revoked), ts(expires.minus(Duration.ofMinutes(30))), hex(id), challenge, parent);
        return id;
    }
    UUID job(String state, Instant lease, UUID challenge, String envelope) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO notification_job(id,audience,template,due_at,state,lease_until,lease_token,encrypted_envelope,challenge_id,safe_error) VALUES (?,'CUSTOMER','AUTH',?,?,?,?,?,?,?)",
                id, ts(NOW.minus(Duration.ofDays(3))), state, lease == null ? null : ts(lease), lease == null ? null : UUID.randomUUID(), envelope, challenge, state.equals("FAILED") ? "SMTP_REJECTED" : null);
        return id;
    }
    String state(UUID job) { return jdbc.queryForObject("SELECT state FROM notification_job WHERE id=?", String.class, job); }
    String envelope(UUID job) { return jdbc.queryForObject("SELECT encrypted_envelope FROM notification_job WHERE id=?", String.class, job); }
    UUID reference(UUID job) { return jdbc.queryForObject("SELECT challenge_id FROM notification_job WHERE id=?", UUID.class, job); }

    // ---- sessions -------------------------------------------------------------------------------------------
    @Test void sessionDeathBoundariesAreInclusiveAtTheGraceEdgeForAbsoluteIdleAndRevocation() {
        Instant edge = NOW.minus(HOUR);
        // Absolute expiry exactly one grace period ago, with recent activity so only the absolute rule applies.
        UUID absoluteEdge = session("ANONYMOUS", edge.minus(Duration.ofHours(2)), edge.minus(MICRO), null);
        UUID absoluteShort = session("ANONYMOUS", edge.minus(Duration.ofHours(2)).plus(MICRO), edge, null);
        // Idle expiry: anonymous/guest 30 minutes, administrator 15 minutes.
        UUID idleEdge = session("ANONYMOUS", edge.minus(Duration.ofHours(1)), edge.minus(Duration.ofMinutes(30)), null);
        UUID idleShort = session("ANONYMOUS", edge.minus(Duration.ofHours(1)), edge.minus(Duration.ofMinutes(30)).plus(MICRO), null);
        UUID adminId = admin();
        UUID adminIdleEdge = session(UUID.randomUUID(), "ADMIN", edge.minus(Duration.ofHours(1)), edge.minus(Duration.ofMinutes(15)), null, null, adminId);
        UUID adminIdleShort = session(UUID.randomUUID(), "ADMIN", edge.minus(Duration.ofHours(1)), edge.minus(Duration.ofMinutes(15)).plus(MICRO), null, null, adminId);
        UUID revokedEdge = session("ANONYMOUS", NOW.minus(Duration.ofMinutes(90)), NOW.minus(Duration.ofMinutes(80)), edge);
        UUID revokedShort = session("ANONYMOUS", NOW.minus(Duration.ofMinutes(90)), NOW.minus(Duration.ofMinutes(80)), edge.plus(MICRO));
        UUID live = session("ANONYMOUS", NOW.minus(Duration.ofMinutes(5)), NOW, null);
        assertEquals(4, cleanup.sessions(50, HOUR));
        for (UUID removed : List.of(absoluteEdge, idleEdge, adminIdleEdge, revokedEdge)) assertFalse(present("app_session", removed));
        for (UUID kept : List.of(absoluteShort, idleShort, adminIdleShort, revokedShort, live)) assertTrue(present("app_session", kept));
        assertEquals(0, cleanup.sessions(50, HOUR), "a repeated sweep is a no-op");
    }

    @Test void sessionBatchesAreBoundedAndLockedRowsAreSkippedNotAwaited() throws Exception {
        var dead = new ArrayList<UUID>();
        for (int i = 0; i < 5; i++) dead.add(session("ANONYMOUS", NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(2)), null));
        assertEquals(2, cleanup.sessions(2, HOUR));
        assertEquals(3, count("app_session"));
        UUID locked = jdbc.queryForObject("SELECT id FROM app_session ORDER BY id LIMIT 1", UUID.class);
        try (var other = fixture.source("p17_cleanup_holder").getConnection()) {
            other.setAutoCommit(false);
            try (var s = other.prepareStatement("SELECT id FROM app_session WHERE id=? FOR UPDATE")) { s.setObject(1, locked); s.executeQuery(); }
            long begin = System.nanoTime();
            assertEquals(2, cleanup.sessions(10, HOUR));
            assertTrue(Duration.ofNanos(System.nanoTime() - begin).compareTo(Duration.ofSeconds(1)) < 0, "no lock wait");
            assertTrue(present("app_session", locked));
            other.rollback();
        }
        assertEquals(1, cleanup.sessions(10, HOUR));
        assertEquals(0, count("app_session"));
    }

    @Test void twentyConcurrentSessionSweepsRemoveEachRowExactlyOnce() throws Exception {
        for (int i = 0; i < 60; i++) session("ANONYMOUS", NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(2)), null);
        UUID live = session("ANONYMOUS", NOW.minus(Duration.ofMinutes(1)), NOW, null);
        int total = concurrently(() -> new PostgresSecurityCleanup(ThrottleFixture.at(fixture.source("p17_sweep"), clock)).sessions(7, HOUR), 20);
        assertEquals(60, total);
        assertEquals(1, count("app_session"));
        assertTrue(present("app_session", live));
    }

    // ---- proofs and reenrollment grants ---------------------------------------------------------------------
    UUID proof(Instant issued, Instant consumed, Instant revoked) {
        UUID id = UUID.randomUUID(), adminId = jdbc.queryForObject("SELECT id FROM admin_user LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO admin_authentication_proof(id,admin_id,token_hash,purpose,browser_hash,request_hash,security_version,issued_at,expires_at,consumed_at,revoked_at) VALUES (?,?,?,'ADMIN_AUTHENTICATION',?,?,0,?,?,?,?)",
                id, adminId, hex(id), hex(id), hex(id), ts(issued), ts(issued.plusSeconds(60)), consumed == null ? null : ts(consumed), revoked == null ? null : ts(revoked));
        return id;
    }
    UUID reenrollment(Instant issued, Instant consumed, boolean codeUsed) {
        UUID id = UUID.randomUUID(), code = UUID.randomUUID(), adminId = jdbc.queryForObject("SELECT id FROM admin_user LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO admin_recovery_code(id,admin_id,code_hash,consumed_at) VALUES (?,?,?,?)", code, adminId, hex(code), codeUsed ? ts(issued) : null);
        jdbc.update("INSERT INTO admin_reenrollment_grant(id,admin_id,recovery_code_id,purpose,token_hash,browser_hash,request_hash,security_version,issued_at,expires_at,consumed_at) VALUES (?,?,?,'ADMIN_REENROLLMENT',?,?,?,1,?,?,?)",
                id, adminId, code, hex(id), hex(id), hex(id), ts(issued), ts(issued.plus(Duration.ofMinutes(10))), consumed == null ? null : ts(consumed));
        return id;
    }

    @Test void proofAndReenrollmentCleanupHonoursGraceBoundariesAndKeepsUsableRecoveryBindings() {
        admin();
        Instant edge = NOW.minus(HOUR);
        UUID expiredEdge = proof(edge.minusSeconds(60), null, null);
        UUID expiredShort = proof(edge.minusSeconds(60).plus(MICRO), null, null);
        UUID consumedEdge = proof(edge.minusSeconds(30), edge, null);
        UUID revokedShort = proof(edge.minusSeconds(30), null, edge.plus(MICRO));
        UUID livingProof = proof(NOW.minusSeconds(10), null, null);
        UUID grantEdge = reenrollment(edge.minus(Duration.ofMinutes(10)), null, true);
        UUID grantShort = reenrollment(edge.minus(Duration.ofMinutes(10)).plus(MICRO), null, true);
        UUID grantUnusableCode = reenrollment(NOW.minus(Duration.ofDays(2)), null, false);
        assertEquals(3, cleanup.proofs(50, HOUR), "two proofs and one reenrollment grant");
        assertFalse(present("admin_authentication_proof", expiredEdge));
        assertFalse(present("admin_authentication_proof", consumedEdge));
        for (UUID kept : List.of(expiredShort, revokedShort, livingProof)) assertTrue(present("admin_authentication_proof", kept));
        assertTrue(present("admin_reenrollment_grant", grantShort));
        assertTrue(present("admin_reenrollment_grant", grantUnusableCode), "an unconsumed recovery code keeps its unique binding");
        assertFalse(present("admin_reenrollment_grant", grantEdge));
    }

    @Test void proofBatchLimitIsSharedAcrossBothTables() {
        admin();
        for (int i = 0; i < 3; i++) proof(NOW.minus(Duration.ofDays(1)), null, null);
        for (int i = 0; i < 3; i++) reenrollment(NOW.minus(Duration.ofDays(1)), null, true);
        assertEquals(4, cleanup.proofs(4, HOUR));
        assertEquals(0, count("admin_authentication_proof"));
        assertEquals(2, count("admin_reenrollment_grant"));
        assertEquals(2, cleanup.proofs(4, HOUR));
        assertEquals(0, count("admin_reenrollment_grant"));
    }

    // ---- rate buckets ---------------------------------------------------------------------------------------
    @Test void rateBucketsAreRemovedOnlyAfterTheirWindowAndGraceEnd() {
        String insert = "INSERT INTO rate_limit_bucket(key,kind,key_id,count,window_started_at,reset_at) VALUES (?,'EMAIL_IP','k1',1,?,?)";
        Instant edge = NOW.minus(HOUR);
        jdbc.update(insert, "a".repeat(64), ts(edge.minus(HOUR)), ts(edge));
        jdbc.update(insert, "b".repeat(64), ts(edge.minus(HOUR).plus(MICRO)), ts(edge.plus(MICRO)));
        jdbc.update(insert, "c".repeat(64), ts(NOW), ts(NOW.plus(HOUR)));
        for (int i = 0; i < 3; i++) jdbc.update(insert, ("d" + i).repeat(32).substring(0, 64), ts(NOW.minus(Duration.ofDays(2))), ts(NOW.minus(Duration.ofDays(2)).plus(HOUR)));
        assertEquals(2, cleanup.rateBuckets(2, HOUR));
        assertEquals(4, count("rate_limit_bucket"));
        assertEquals(2, cleanup.rateBuckets(10, HOUR));
        assertEquals(2, count("rate_limit_bucket"));
        assertEquals(0, cleanup.rateBuckets(10, HOUR));
        assertEquals(List.of("b".repeat(64), "c".repeat(64)), jdbc.queryForList("SELECT key FROM rate_limit_bucket ORDER BY key", String.class));
    }

    // ---- guest authority: grants, sessions, jobs, challenges -----------------------------------------------
    /** Challenge C (expired two days ago), parent CREATE grant P, child MANAGE grant M, sessions on both, delivery job J. */
    record Chain(UUID challenge, UUID parent, UUID child, UUID parentSession, UUID childSession, UUID job) { }
    Chain chain(int seed, String jobState, Instant lease) {
        Instant old = NOW.minus(Duration.ofDays(2));
        UUID c = challenge(id(1000 + seed), old);
        UUID p = grant(id(2000 + seed * 2), "CREATE", c, null, old, old);
        UUID m = grant(id(2001 + seed * 2), "MANAGE", null, p, old, old);
        UUID ps = session(UUID.randomUUID(), "GUEST", old, old, null, p, null);
        UUID ms = session(UUID.randomUUID(), "GUEST", old, old, old.plusSeconds(5), m, null);
        UUID j = job(jobState, lease, c, "synthetic-k:not-decryptable-envelope");
        return new Chain(c, p, m, ps, ms, j);
    }

    @Test void deadChainIsRemovedChildrenFirstAndEnvelopeReferencesAreClearedBeforeChallengeDeletion() {
        var chain = chain(1, "QUEUED", null);
        assertEquals(3, cleanup.guestAuthority(10, DAY), "two grants and one challenge");
        for (String table : List.of("app_session", "access_grant", "email_challenge")) assertEquals(0, count(table), table);
        assertTrue(present("notification_job", chain.job));
        assertNull(envelope(chain.job));
        assertNull(reference(chain.job));
        assertEquals("SUPPRESSED", state(chain.job));
        assertEquals("AUTH_DELIVERY_EXPIRED", jdbc.queryForObject("SELECT safe_error FROM notification_job WHERE id=?", String.class, chain.job));
        assertEquals(0, cleanup.guestAuthority(10, DAY));
    }

    @Test void terminalJobOutcomesSurviveWhileTheirEnvelopeAndChallengeAreErased() {
        UUID c = challenge(id(1), NOW.minus(Duration.ofDays(3)));
        UUID sent = job("SENT", null, c, "synthetic-k:envelope"), failed = job("FAILED", null, c, "synthetic-k:envelope");
        assertEquals(1, cleanup.guestAuthority(10, DAY));
        assertEquals("SENT", state(sent));
        assertEquals("FAILED", state(failed));
        assertEquals("SMTP_REJECTED", jdbc.queryForObject("SELECT safe_error FROM notification_job WHERE id=?", String.class, failed));
        assertNull(envelope(sent));
        assertNull(reference(failed));
    }

    @Test void retentionBoundariesAreInclusiveForGrantsAndChallenges() {
        Instant edge = NOW.minus(DAY);
        UUID grantEdge = grant(id(1), "CREATE", null, null, edge, edge.plus(Duration.ofHours(5)));
        UUID grantRevokedEarly = grant(id(2), "CREATE", null, null, NOW.plus(Duration.ofDays(1)), edge);
        UUID grantShort = grant(id(3), "CREATE", null, null, edge.plus(MICRO), edge.plus(MICRO));
        UUID challengeEdge = challenge(id(4), edge), challengeShort = challenge(id(5), edge.plus(MICRO));
        assertEquals(3, cleanup.guestAuthority(10, DAY));
        assertFalse(present("access_grant", grantEdge));
        assertFalse(present("access_grant", grantRevokedEarly));
        assertTrue(present("access_grant", grantShort));
        assertFalse(present("email_challenge", challengeEdge));
        assertTrue(present("email_challenge", challengeShort));
    }

    @Test void aParentWithASurvivingChildIsNeverRemovedAndKeepsItsChallenge() {
        Instant old = NOW.minus(Duration.ofDays(2));
        UUID c = challenge(id(1), old);
        UUID p = grant(id(10), "CREATE", c, null, old, old);
        UUID m = grant(id(11), "MANAGE", null, p, NOW.plus(Duration.ofHours(1)), NOW.minus(Duration.ofMinutes(1)));
        UUID ps = session(UUID.randomUUID(), "GUEST", old, old, null, p, null);
        assertEquals(0, cleanup.guestAuthority(10, DAY));
        assertTrue(present("access_grant", p));
        assertTrue(present("access_grant", m));
        assertTrue(present("email_challenge", c));
        assertTrue(present("app_session", ps), "sessions of untouched grants are not removed");
    }

    @Test void childrenAreChosenBeforeParentsSoALimitedBatchNeverStallsOnTheParent() {
        // Parent has the lowest id: id order alone would select it first and be blocked forever by its unselected child.
        Instant old = NOW.minus(Duration.ofDays(2));
        UUID p = grant(id(1), "CREATE", null, null, old, old);
        UUID m = grant(id(9), "MANAGE", null, p, old, old);
        assertEquals(1, cleanup.guestAuthority(1, DAY));
        assertFalse(present("access_grant", m));
        assertTrue(present("access_grant", p));
        assertEquals(1, cleanup.guestAuthority(1, DAY));
        assertEquals(0, count("access_grant"));
    }

    @Test void liveFencedLeaseKeepsItsChallengeEnvelopeAndStateUntouched() {
        UUID c = challenge(id(1), NOW.minus(Duration.ofDays(3)));
        UUID live = job("LEASED", NOW.plus(Duration.ofMinutes(5)), c, "synthetic-k:live-envelope");
        Instant lease = jdbc.queryForObject("SELECT lease_until FROM notification_job WHERE id=?", Timestamp.class, live).toInstant();
        UUID token = jdbc.queryForObject("SELECT lease_token FROM notification_job WHERE id=?", UUID.class, live);
        assertEquals(0, cleanup.guestAuthority(10, DAY));
        assertTrue(present("email_challenge", c));
        assertEquals("LEASED", state(live));
        assertEquals("synthetic-k:live-envelope", envelope(live));
        assertEquals(c, reference(live));
        assertEquals(token, jdbc.queryForObject("SELECT lease_token FROM notification_job WHERE id=?", UUID.class, live));
        assertEquals(lease, jdbc.queryForObject("SELECT lease_until FROM notification_job WHERE id=?", Timestamp.class, live).toInstant());
        // The lease ends exactly now: equality is expired, so the next sweep clears it.
        clock.set(lease);
        assertEquals(1, cleanup.guestAuthority(10, DAY));
        assertEquals("SUPPRESSED", state(live));
        assertNull(envelope(live));
        assertNull(reference(live));
        assertNull(jdbc.queryForObject("SELECT lease_token FROM notification_job WHERE id=?", UUID.class, live));
    }

    @Test void oneLiveJobProtectsTheChallengeEvenWhenSiblingsAreExpired() {
        UUID c = challenge(id(1), NOW.minus(Duration.ofDays(3)));
        UUID live = job("LEASED", NOW.plusSeconds(1), c, "synthetic-k:live");
        UUID expired = job("LEASED", NOW.minusSeconds(1), c, "synthetic-k:expired");
        assertEquals(0, cleanup.guestAuthority(10, DAY));
        assertEquals("LEASED", state(expired));
        assertEquals("synthetic-k:expired", envelope(expired));
        assertEquals(c, reference(live));
    }

    @Test void jobsLockedByTheDeliveryWorkerAreSkippedWithoutWaitingAndNeverInvertTheJobThenChallengeOrder() throws Exception {
        UUID c = challenge(id(1), NOW.minus(Duration.ofDays(3)));
        UUID j = job("QUEUED", null, c, "synthetic-k:envelope");
        try (var worker = fixture.source("p17_worker").getConnection()) {
            worker.setAutoCommit(false);
            try (var s = worker.prepareStatement("SELECT id FROM notification_job WHERE id=? FOR UPDATE")) { s.setObject(1, j); s.executeQuery(); }
            long begin = System.nanoTime();
            assertEquals(0, cleanup.guestAuthority(10, DAY));
            assertTrue(Duration.ofNanos(System.nanoTime() - begin).compareTo(Duration.ofSeconds(1)) < 0, "locked job is skipped, not awaited");
            // The worker continues in its own order, job then challenge, and commits without any deadlock.
            try (var s = worker.prepareStatement("SELECT id FROM email_challenge WHERE id=? FOR UPDATE")) { s.setObject(1, c); s.executeQuery(); }
            worker.commit();
        }
        assertTrue(present("email_challenge", c));
        assertEquals("synthetic-k:envelope", envelope(j));
        assertEquals(1, cleanup.guestAuthority(10, DAY));
        assertNull(envelope(j));
    }

    @Test void resourceLockContentionFailsSafelyAndAFreeCleanPassNeverTouchesTheResourceLock() throws Exception {
        var chain = chain(1, "QUEUED", null);
        try (var scheduler = fixture.source("p17_scheduler").getConnection()) {
            scheduler.setAutoCommit(false);
            try (var s = scheduler.createStatement()) { s.executeQuery("SELECT id FROM scheduling_resource WHERE id=1 FOR UPDATE"); }
            var failure = assertThrows(IllegalStateException.class, () -> cleanup.guestAuthority(10, DAY));
            assertEquals("SECURITY_CLEANUP_FAILED", failure.getMessage());
            assertNull(failure.getCause());
            assertEquals(2, count("access_grant"), "the failed mixed transaction rolled back completely");
            assertEquals(1, count("email_challenge"));
            assertEquals(2, count("app_session"));
            scheduler.rollback();
        }
        assertEquals(3, cleanup.guestAuthority(10, DAY));
        assertNull(envelope(chain.job));
        // Nothing eligible: the peek returns before any resource lock is requested, even while a scheduler holds it.
        try (var scheduler = fixture.source("p17_scheduler").getConnection()) {
            scheduler.setAutoCommit(false);
            try (var s = scheduler.createStatement()) { s.executeQuery("SELECT id FROM scheduling_resource WHERE id=1 FOR UPDATE"); }
            long begin = System.nanoTime();
            assertEquals(0, cleanup.guestAuthority(10, DAY));
            assertTrue(Duration.ofNanos(System.nanoTime() - begin).compareTo(Duration.ofSeconds(1)) < 0);
            scheduler.rollback();
        }
    }

    @Test void providerFailureMidChainRollsBackEveryDeleteWithoutLeakingProviderText() {
        chain(1, "QUEUED", null);
        var failing = new PostgresSecurityCleanup(ThrottleFixture.failing(ThrottleFixture.at(fixture.source, clock), (sql, call) -> {
            if (sql.contains("DELETE FROM email_challenge")) throw new SQLException("synthetic-private-diagnostic", "XX000");
        }));
        var failure = assertThrows(IllegalStateException.class, () -> failing.guestAuthority(10, DAY));
        assertEquals("SECURITY_CLEANUP_FAILED", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(2, count("access_grant"));
        assertEquals(2, count("app_session"));
        assertEquals(1, count("email_challenge"));
        assertEquals("synthetic-k:not-decryptable-envelope", jdbc.queryForObject("SELECT encrypted_envelope FROM notification_job", String.class));
        assertEquals(3, cleanup.guestAuthority(10, DAY));
    }

    @Test void twentyConcurrentMixedSweepsRemoveEveryChainExactlyOnceWithoutDeadlocks() throws Exception {
        for (int i = 1; i <= 12; i++) chain(i, i % 2 == 0 ? "QUEUED" : "SENT", null);
        int total = concurrently(() -> new PostgresSecurityCleanup(ThrottleFixture.at(fixture.source("p17_mixed"), clock)).guestAuthority(5, DAY), 20);
        assertEquals(12 * 3, total, "each of 12 chains has two grants and one challenge, counted once");
        for (String table : List.of("app_session", "access_grant", "email_challenge")) assertEquals(0, count(table), table);
        assertEquals(12, jdbc.queryForObject("SELECT count(*) FROM notification_job WHERE encrypted_envelope IS NULL AND challenge_id IS NULL", Integer.class));
    }

    @Test void boundsAndConfigurationAreValidatedBeforeStorage() {
        for (int limit : new int[]{0, -1, 501})
            assertEquals("INVALID_CLEANUP_BOUNDS", assertThrows(IllegalArgumentException.class, () -> cleanup.sessions(limit, HOUR)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> cleanup.proofs(1, null));
        assertThrows(IllegalArgumentException.class, () -> cleanup.rateBuckets(1, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> cleanup.guestAuthority(0, DAY));
        assertThrows(NullPointerException.class, () -> new PostgresSecurityCleanup(null));
        assertEquals("SecurityCleanupStore[configuration=REDACTED]", cleanup.toString());
        var unavailable = new PostgresSecurityCleanup(new org.springframework.jdbc.datasource.AbstractDataSource() {
            public Connection getConnection() throws SQLException { throw new SQLException("synthetic-private-diagnostic"); }
            public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        });
        var failure = assertThrows(IllegalStateException.class, () -> unavailable.sessions(1, HOUR));
        assertEquals("SECURITY_CLEANUP_FAILED", failure.getMessage());
        assertNull(failure.getCause());
    }

    int concurrently(Callable<Integer> sweep, int workers) throws Exception {
        var barrier = new CyclicBarrier(workers);
        int total = 0;
        try (var pool = Executors.newFixedThreadPool(workers)) {
            var futures = new ArrayList<Future<Integer>>();
            for (int w = 0; w < workers; w++) futures.add(pool.submit(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                int removed = 0, last;
                do { last = sweep.call(); removed += last; } while (last > 0);
                return removed;
            }));
            for (var future : futures) total += future.get(120, TimeUnit.SECONDS);
        }
        return total;
    }
}
