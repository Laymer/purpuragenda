package com.purpuragenda.application;

import com.purpuragenda.application.port.out.AuthDeliveryStore;
import com.purpuragenda.application.port.out.SecurityCleanupStore;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SecurityCleanupTest {
    static final class Store implements SecurityCleanupStore {
        final Map<String, ArrayDeque<Integer>> script = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        final List<Duration> ages = new ArrayList<>();
        final List<Integer> limits = new ArrayList<>();
        int take(String step, int limit, Duration age) {
            calls.add(step); limits.add(limit); ages.add(age);
            var queue = script.get(step);
            if (queue == null || queue.isEmpty()) return 0;
            int value = queue.poll();
            if (value == -2) throw new IllegalStateException("synthetic-private-detail");
            return value;
        }
        Store with(String step, Integer... counts) { script.put(step, new ArrayDeque<>(List.of(counts))); return this; }
        @Override public int sessions(int limit, Duration grace) { return take("sessions", limit, grace); }
        @Override public int proofs(int limit, Duration grace) { return take("proofs", limit, grace); }
        @Override public int guestAuthority(int limit, Duration retention) { return take("guest-authority", limit, retention); }
        @Override public int rateBuckets(int limit, Duration grace) { return take("rate-buckets", limit, grace); }
    }
    static AuthDeliveryStore delivery(List<String> calls, Integer... counts) {
        var queue = new ArrayDeque<>(List.of(counts));
        return (AuthDeliveryStore) Proxy.newProxyInstance(AuthDeliveryStore.class.getClassLoader(), new Class<?>[]{AuthDeliveryStore.class},
                (proxy, method, arguments) -> {
                    if (!method.getName().equals("eraseExpired")) throw new UnsupportedOperationException();
                    calls.add("envelopes");
                    return queue.isEmpty() ? 0 : queue.poll();
                });
    }

    @Test void stepsRunInDependencyOrderWithApprovedRetentionAndBatchBound() {
        var store = new Store();
        var report = new SecurityCleanup(delivery(store.calls), store, 50, 3).runOnce();
        assertEquals(List.of("envelopes", "sessions", "proofs", "guest-authority", "rate-buckets"), store.calls);
        assertEquals(List.of(50, 50, 50, 50), store.limits);
        assertEquals(List.of(Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(24), Duration.ofHours(1)), store.ages);
        assertTrue(report.complete());
        assertEquals(Map.of("envelopes", 0, "sessions", 0, "proofs", 0, "guest-authority", 0, "rate-buckets", 0), report.removed());
    }

    @Test void fullBatchesRepeatUntilDrainedOrTheRunBoundIsReached() {
        var store = new Store().with("sessions", 10, 10, 4).with("proofs", 10, 10, 10, 10).with("rate-buckets", 10, 0);
        var report = new SecurityCleanup(delivery(store.calls), store, 10, 3).runOnce();
        assertEquals(24, report.removed().get("sessions"));
        assertEquals(30, report.removed().get("proofs"));
        assertEquals(10, report.removed().get("rate-buckets"));
        assertEquals(3, store.calls.stream().filter("sessions"::equals).count());
        assertEquals(3, store.calls.stream().filter("proofs"::equals).count());
        assertEquals(2, store.calls.stream().filter("rate-buckets"::equals).count());
    }

    @Test void envelopeErasureUsesItsOwnFixedBatchSize() {
        var calls = new ArrayList<String>();
        var report = new SecurityCleanup(delivery(calls, 100, 100, 7), new Store(), 10, 5).runOnce();
        assertEquals(207, report.removed().get("envelopes"));
        assertEquals(3, calls.stream().filter("envelopes"::equals).count());
    }

    @Test void oneFailingStepIsNamedWithoutDetailAndDoesNotStopOtherSteps() {
        var store = new Store().with("sessions", 10, -2).with("rate-buckets", 3);
        var report = new SecurityCleanup(delivery(store.calls), store, 10, 5).runOnce();
        assertFalse(report.complete());
        assertEquals(Set.of("sessions"), report.failed());
        assertEquals(10, report.removed().get("sessions"));
        assertEquals(3, report.removed().get("rate-buckets"));
        assertFalse(report.toString().contains("synthetic-private-detail"));
        assertTrue(store.calls.contains("guest-authority"));
    }

    @Test void negativeCountsFromAnAdapterAreTreatedAsFailures() {
        var store = new Store().with("proofs", -1);
        assertEquals(Set.of("proofs"), new SecurityCleanup(delivery(store.calls), store).runOnce().failed());
    }

    @Test void boundsAndDependenciesAreValidated() {
        var d = delivery(new ArrayList<>());
        var s = new Store();
        for (int[] bounds : new int[][]{{0, 1}, {SecurityCleanup.MAX_BATCH + 1, 1}, {1, 0}, {1, SecurityCleanup.MAX_BATCHES + 1}})
            assertEquals("INVALID_CLEANUP_BOUNDS", assertThrows(IllegalArgumentException.class, () -> new SecurityCleanup(d, s, bounds[0], bounds[1])).getMessage());
        assertThrows(NullPointerException.class, () -> new SecurityCleanup(null, s));
        assertThrows(NullPointerException.class, () -> new SecurityCleanup(d, null));
        assertNotNull(new SecurityCleanup(d, s, SecurityCleanup.MAX_BATCH, SecurityCleanup.MAX_BATCHES));
    }
}
