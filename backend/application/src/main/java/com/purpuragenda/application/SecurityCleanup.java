package com.purpuragenda.application;

import com.purpuragenda.application.port.out.AuthDeliveryStore;
import com.purpuragenda.application.port.out.SecurityCleanupStore;
import java.time.Duration;
import java.util.*;

/**
 * Hourly security retention hook. Scheduling is a composition concern; one call performs a bounded number
 * of bounded batches and never reports row contents.
 */
public final class SecurityCleanup {
    public static final Duration SESSION_GRACE = Duration.ofHours(1);
    public static final Duration CHALLENGE_RETENTION = Duration.ofHours(24);
    public static final Duration RATE_GRACE = Duration.ofHours(1);
    public static final int MAX_BATCH = 500;
    public static final int MAX_BATCHES = 20;

    /** Counts by step plus the names of steps whose transaction failed and will be retried next run. */
    public record Report(Map<String, Integer> removed, Set<String> failed) {
        public Report { removed = Map.copyOf(removed); failed = Set.copyOf(failed); }
        public boolean complete() { return failed.isEmpty(); }
    }

    /** AuthDeliveryStore erases at most this many envelopes per call. */
    static final int ERASE_BATCH = 100;

    private interface Step { int run(int limit); }

    private final AuthDeliveryStore delivery;
    private final SecurityCleanupStore store;
    private final int batch, batches;

    public SecurityCleanup(AuthDeliveryStore delivery, SecurityCleanupStore store) { this(delivery, store, 100, 10); }

    public SecurityCleanup(AuthDeliveryStore delivery, SecurityCleanupStore store, int batch, int batches) {
        if (batch < 1 || batch > MAX_BATCH || batches < 1 || batches > MAX_BATCHES) throw new IllegalArgumentException("INVALID_CLEANUP_BOUNDS");
        this.delivery = Objects.requireNonNull(delivery);
        this.store = Objects.requireNonNull(store);
        this.batch = batch;
        this.batches = batches;
    }

    public Report runOnce() {
        var removed = new LinkedHashMap<String, Integer>();
        var failed = new TreeSet<String>();
        // Envelope erasure first: it never decrypts, and later challenge deletion then finds no readable reference.
        run("envelopes", removed, failed, ERASE_BATCH, limit -> delivery.eraseExpired());
        run("sessions", removed, failed, batch, limit -> store.sessions(limit, SESSION_GRACE));
        run("proofs", removed, failed, batch, limit -> store.proofs(limit, SESSION_GRACE));
        run("guest-authority", removed, failed, batch, limit -> store.guestAuthority(limit, CHALLENGE_RETENTION));
        run("rate-buckets", removed, failed, batch, limit -> store.rateBuckets(limit, RATE_GRACE));
        return new Report(removed, failed);
    }

    private void run(String name, Map<String, Integer> removed, Set<String> failed, int full, Step step) {
        int total = 0;
        try {
            for (int i = 0; i < batches; i++) {
                int count = step.run(batch);
                if (count < 0) throw new IllegalStateException();
                total += count;
                // A short batch means the eligible set is drained; otherwise the bound defers work to the next run.
                if (count < full) break;
            }
        } catch (RuntimeException failure) { failed.add(name); }
        removed.put(name, total);
    }
}
