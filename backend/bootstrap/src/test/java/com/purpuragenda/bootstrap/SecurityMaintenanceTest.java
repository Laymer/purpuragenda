package com.purpuragenda.bootstrap;

import com.purpuragenda.application.SecurityCleanup;
import com.purpuragenda.application.port.out.AuthDeliveryStore;
import com.purpuragenda.application.port.out.SecurityCleanupStore;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SecurityMaintenanceTest {
    static AuthDeliveryStore delivery(Runnable onErase) {
        return (AuthDeliveryStore) Proxy.newProxyInstance(AuthDeliveryStore.class.getClassLoader(), new Class<?>[]{AuthDeliveryStore.class},
                (proxy, method, arguments) -> { if (method.getName().equals("eraseExpired")) { onErase.run(); return 0; } throw new UnsupportedOperationException(); });
    }
    static final SecurityCleanupStore EMPTY = new SecurityCleanupStore() {
        public int sessions(int limit, Duration grace) { return 0; }
        public int proofs(int limit, Duration grace) { return 0; }
        public int guestAuthority(int limit, Duration retention) { return 0; }
        public int rateBuckets(int limit, Duration grace) { return 0; }
    };

    @Test void tickRunsTheBoundedSweepAndHidesConfiguration() {
        var maintenance = new SecurityMaintenance(new SecurityCleanup(delivery(() -> { }), EMPTY));
        var report = maintenance.tick();
        assertTrue(report.complete());
        assertEquals(java.util.Set.of("envelopes", "sessions", "proofs", "guest-authority", "rate-buckets"), report.removed().keySet());
        assertEquals("SecurityMaintenance[configuration=REDACTED]", maintenance.toString());
        assertThrows(NullPointerException.class, () -> new SecurityMaintenance(null));
    }

    @Test void overlappingTicksAreSkippedAndTheGuardIsReleasedAfterwards() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var maintenance = new SecurityMaintenance(new SecurityCleanup(delivery(() -> {
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }), EMPTY));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(maintenance::tick);
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var skipped = maintenance.tick();
            assertFalse(skipped.complete());
            assertEquals(java.util.Set.of("overlap"), skipped.failed());
            release.countDown();
            assertTrue(first.get(10, TimeUnit.SECONDS).complete());
        }
        assertTrue(maintenance.tick().complete());
    }

    @Test void aFailingStepIsReportedByNameWithoutDetailsAndDoesNotBlockLaterSweeps() {
        var failing = new SecurityCleanupStore() {
            public int sessions(int limit, Duration grace) { throw new IllegalStateException("synthetic-private-detail"); }
            public int proofs(int limit, Duration grace) { return 0; }
            public int guestAuthority(int limit, Duration retention) { return 0; }
            public int rateBuckets(int limit, Duration grace) { return 0; }
        };
        var maintenance = new SecurityMaintenance(new SecurityCleanup(delivery(() -> { }), failing));
        var report = maintenance.tick();
        assertEquals(java.util.Set.of("sessions"), report.failed());
        assertFalse(report.toString().contains("synthetic-private-detail"));
        assertEquals(java.util.Set.of("sessions"), maintenance.tick().failed());
    }
}
