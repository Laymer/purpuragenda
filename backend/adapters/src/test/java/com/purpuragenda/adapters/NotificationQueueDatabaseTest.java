package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.email.PostgresNotificationQueue;
import com.purpuragenda.application.port.out.NotificationQueue.Lease;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.postgresql.ds.PGSimpleDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real pinned PostgreSQL, production migrations, separate connections; no dependency skips. */
@Timeout(180)
@ResourceLock("postgres-migration-fixture")
class NotificationQueueDatabaseTest {
    final MigrationTest database = new MigrationTest();
    PostgresNotificationQueue queue;
    PGSimpleDataSource source;
    UUID booking;
    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void setup() {
        database.fresh();
        source = new PGSimpleDataSource(); source.setURL(MigrationTest.jdbcUrl);
        source.setUser(MigrationTest.APP); source.setPassword(MigrationTest.appPassword);
        source.setCurrentSchema(database.schema + ",public");
        queue = new PostgresNotificationQueue(source, false);
        booking = database.booking(MigrationTest.START, "PENDING_CONFIRMATION", MigrationTest.snapshot());
    }
    @AfterEach void cleanup() { database.cleanup(); }
    UUID job(UUID owner, String audience) {
        UUID id = UUID.randomUUID();
        sql("INSERT INTO notification_job(id,booking_id,audience,template,due_at,encrypted_envelope) VALUES(?,?,?,'REQUESTED',clock_timestamp()-interval '1 minute','synthetic-encrypted-marker')", id, owner, audience);
        return id;
    }
    void sql(String text, Object... values) {
        try (var c = database.connection(MigrationTest.APP)) { MigrationTest.execute(c, text, values); }
        catch (SQLException failure) { throw MigrationTest.safe(failure); }
    }
    long count(String text) { return database.scalar(MigrationTest.APP, text); }
    long attempts() { return count("SELECT count(*) FROM delivery_attempt"); }
    static void safeFailure(org.junit.jupiter.api.function.Executable operation) {
        var error = assertThrows(IllegalStateException.class, operation);
        assertEquals("NOTIFICATION_STORAGE_FAILED", error.getMessage());
        assertNull(error.getCause()); assertEquals(0, error.getSuppressed().length);
    }

    @Test void committedClaimCanBeRenewedAndCompletedByAnotherInstanceExactlyOnce() {
        UUID id = job(booking, "CUSTOMER");
        Lease lease = queue.claim().orElseThrow(); assertTrue(id.equals(lease.id()));
        assertEquals(1, count("SELECT count(*) FROM notification_job WHERE state='LEASED' AND attempts=1"));
        var restarted = new PostgresNotificationQueue(source, false);
        assertTrue(restarted.renew(lease)); assertTrue(restarted.sent(lease));
        assertFalse(queue.sent(lease)); assertFalse(queue.failed(lease)); assertFalse(queue.renew(lease));
        assertEquals(1, attempts());
        assertEquals(1, count("SELECT count(*) FROM notification_job WHERE state='SENT' AND encrypted_envelope IS NULL AND lease_token IS NULL AND lease_until IS NULL AND safe_error IS NULL"));
        assertEquals(1, count("SELECT count(*) FROM delivery_attempt WHERE success AND safe_code IS NULL AND lease_token IS NOT NULL"));
    }

    @Test void expiredClaimRotatesTokenAndOldWorkerCannotAlterStateOrRecordAttempts() {
        UUID id = job(booking, "CUSTOMER"); Lease old = queue.claim().orElseThrow();
        sql("UPDATE notification_job SET lease_until=clock_timestamp() WHERE id=?", id);
        assertFalse(queue.renew(old)); assertFalse(queue.sent(old)); assertFalse(queue.failed(old));
        Lease replacement = queue.claim().orElseThrow();
        assertTrue(id.equals(replacement.id())); assertFalse(old.token().equals(replacement.token()));
        assertFalse(queue.renew(old)); assertFalse(queue.sent(old)); assertFalse(queue.failed(old));
        assertEquals(0, attempts()); assertTrue(queue.sent(replacement)); assertEquals(1, attempts());
    }

    @Test void retrySequenceIsDurableAndStopsAfterSixRetries() {
        UUID id = job(booking, "CUSTOMER"); int[] delays = {60, 300, 1800, 7200, 21600, 43200};
        for (int attempt=1; attempt<=7; attempt++) {
            Lease lease = queue.claim().orElseThrow(); assertTrue(queue.failed(lease));
            assertEquals(attempt, attempts());
            if (attempt<=6) {
                assertEquals(delays[attempt-1], count("SELECT extract(epoch FROM j.due_at-a.attempted_at)::bigint FROM notification_job j JOIN delivery_attempt a ON a.job_id=j.id ORDER BY a.attempted_at DESC LIMIT 1"));
                assertTrue(queue.claim().isEmpty());
                sql("UPDATE notification_job SET due_at=clock_timestamp()-interval '1 second' WHERE id=?", id);
            }
        }
        assertEquals(1, count("SELECT count(*) FROM notification_job WHERE state='FAILED' AND attempts=7 AND safe_error='SMTP_DELIVERY_FAILED'"));
        assertTrue(queue.claim().isEmpty());
    }

    @Test void twentyIndependentWorkersLeaseOnlyOneJobInTheSameBookingAudience() throws Exception {
        for (int i=0; i<20; i++) job(booking, "CUSTOMER");
        var ready = new CountDownLatch(20); var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(20)) {
            List<Future<Optional<Lease>>> results = new ArrayList<>();
            for (int i=0; i<20; i++) results.add(workers.submit(() -> {
                ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
                return new PostgresNotificationQueue(source, false).claim();
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            int claimed = 0;
            for (var result : results) if (result.get(20, TimeUnit.SECONDS).isPresent()) claimed++;
            assertEquals(1, claimed);
        }
        assertEquals(1, count("SELECT count(*) FROM notification_job WHERE state='LEASED'"));
        assertEquals(19, count("SELECT count(*) FROM notification_job WHERE state='QUEUED'"));
    }

    @Test void differentAudiencesAndBookingsProgressWithoutGlobalSerialization() {
        job(booking, "CUSTOMER"); job(booking, "OWNER");
        UUID second = database.booking(MigrationTest.START.plusSeconds(7200), "PENDING_CONFIRMATION", MigrationTest.snapshot());
        job(second, "CUSTOMER");
        assertTrue(queue.claim().isPresent()); assertTrue(queue.claim().isPresent()); assertTrue(queue.claim().isPresent());
        assertTrue(queue.claim().isEmpty());
        assertEquals(3, count("SELECT count(*) FROM notification_job WHERE state='LEASED'"));
    }

    @Test void lockedCandidateIsSkippedAndHeldAudienceAdvisoryLockDoesNotBlock() throws Exception {
        UUID first = job(booking, "CUSTOMER"), second = job(booking, "CUSTOMER");
        try (var lock = database.connection(MigrationTest.APP)) {
            lock.setAutoCommit(false);
            MigrationTest.execute(lock, "SELECT id FROM notification_job WHERE id=? FOR UPDATE", first);
            Lease lease = queue.claim().orElseThrow(); assertTrue(second.equals(lease.id()));
            assertTrue(queue.sent(lease)); lock.rollback();
        }
        try (var lock = database.connection(MigrationTest.APP)) {
            lock.setAutoCommit(false);
            MigrationTest.execute(lock, "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", booking + ":CUSTOMER");
            assertTrue(queue.claim().isEmpty()); lock.rollback();
        }
        assertTrue(first.equals(queue.claim().orElseThrow().id()));
    }

    @Test void renewalCannotUseTimeCapturedBeforeWaitingForTheRowLock() throws Exception {
        UUID id = job(booking, "CUSTOMER"); Lease lease = queue.claim().orElseThrow();
        String application = "queue-clock-" + UUID.randomUUID(); source.setApplicationName(application);
        try (var lock = database.connection(MigrationTest.APP);
             var observer = database.connection("postgres");
             var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            lock.setAutoCommit(false);
            MigrationTest.execute(lock, "SELECT id FROM notification_job WHERE id=? FOR UPDATE", id);
            var result = worker.submit(() -> queue.renew(lease));
            boolean blocked = false; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            try (var query = observer.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock' AND state='active'")) {
                query.setString(1, application);
                while (!blocked && System.nanoTime() < deadline) {
                    try (var rows = query.executeQuery()) { rows.next(); blocked = rows.getInt(1) == 1; }
                    if (!blocked) Thread.sleep(10);
                }
            }
            assertTrue(blocked, "Worker must be waiting on the database row lock");
            MigrationTest.execute(lock, "UPDATE notification_job SET lease_until=clock_timestamp()+interval '400 milliseconds' WHERE id=?", id);
            MigrationTest.execute(lock, "SELECT pg_sleep(0.8)"); lock.commit();
            assertFalse(result.get(5, TimeUnit.SECONDS));
        }
        assertTrue(queue.claim().isPresent());
    }

    @Test void queueUsesReadCommittedEvenWhenTheDataSourceDefaultsToRepeatableRead() {
        job(booking, "CUSTOMER"); var observed = new AtomicInteger();
        var repeatable = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
            (proxy, method, arguments) -> {
                var connection = source.getConnection();
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (wrapped, operation, values) -> {
                        if (operation.getName().equals("commit")) {
                            try (var query = connection.createStatement(); var rows = query.executeQuery("SHOW transaction_isolation")) {
                                assertTrue(rows.next()); assertEquals("read committed", rows.getString(1)); observed.incrementAndGet();
                            }
                        }
                        return operation.invoke(connection, values);
                    });
            });
        var configured = new PostgresNotificationQueue(repeatable, false);
        Lease lease = configured.claim().orElseThrow();
        assertTrue(configured.renew(lease)); assertTrue(configured.sent(lease)); assertEquals(3, observed.get());
    }

    @Test void restoreSuppressionAndMissingControlFailClosedWithoutDelivery() {
        job(booking, "CUSTOMER");
        assertTrue(new PostgresNotificationQueue(source, true).claim().isEmpty());
        sql("UPDATE operation_control SET delivery_suppressed=true WHERE id=1"); assertTrue(queue.claim().isEmpty());
        sql("UPDATE operation_control SET delivery_suppressed=false WHERE id=1"); Lease lease = queue.claim().orElseThrow();
        sql("UPDATE operation_control SET delivery_suppressed=true WHERE id=1"); assertFalse(queue.renew(lease));
        assertFalse(new PostgresNotificationQueue(source, true).renew(lease));
        sql("DELETE FROM operation_control WHERE id=1"); assertFalse(queue.renew(lease)); safeFailure(queue::claim);
        assertEquals(0, attempts());
    }

    @Test void malformedOldLeasesAreReclaimedAndUnknownJobsRemainUnchanged() {
        UUID id = job(booking, "CUSTOMER"); Lease lease = queue.claim().orElseThrow();
        sql("UPDATE notification_job SET lease_token=NULL WHERE id=?", id); assertFalse(queue.sent(lease));
        sql("UPDATE notification_job SET lease_token=?,lease_until=NULL WHERE id=?", lease.token(), id); assertFalse(queue.failed(lease));
        Lease replacement = queue.claim().orElseThrow(); assertFalse(lease.token().equals(replacement.token()));
        assertTrue(queue.sent(replacement));
        assertFalse(queue.renew(new Lease(UUID.randomUUID(), UUID.randomUUID(), Instant.MAX)));
        assertEquals(1, attempts());
    }
}
