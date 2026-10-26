package com.purpuragenda.bootstrap;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.springframework.context.SmartLifecycle;

/** Independent, bounded work lanes. Durable leases remain in PostgreSQL, never in this scheduler. */
final class RuntimeWorkers implements SmartLifecycle {
    private final Runnable delivery, cleanup;
    private final BooleanSupplier ready;
    private final boolean suppressed;
    private final long deliveryDelay, cleanupDelay, shutdownMillis;
    private final AtomicBoolean running = new AtomicBoolean();
    private ScheduledExecutorService executor;

    RuntimeWorkers(Runnable delivery, Runnable cleanup, BooleanSupplier ready, boolean suppressed) {
        this(delivery, cleanup, ready, suppressed, Duration.ofSeconds(1), Duration.ofHours(1), Duration.ofSeconds(25));
    }

    RuntimeWorkers(Runnable delivery, Runnable cleanup, BooleanSupplier ready, boolean suppressed,
                   Duration deliveryDelay, Duration cleanupDelay, Duration shutdown) {
        this.delivery = Objects.requireNonNull(delivery);
        this.cleanup = Objects.requireNonNull(cleanup);
        this.ready = Objects.requireNonNull(ready);
        this.suppressed = suppressed;
        this.deliveryDelay = positive(deliveryDelay);
        this.cleanupDelay = positive(cleanupDelay);
        this.shutdownMillis = positive(shutdown);
    }

    private static long positive(Duration duration) {
        if (duration == null || duration.isNegative() || duration.toMillis() < 1) throw new IllegalArgumentException("INVALID_WORKER_INTERVAL");
        return duration.toMillis();
    }

    @Override public synchronized void start() {
        if (suppressed || !running.compareAndSet(false, true)) return;
        executor = Executors.newScheduledThreadPool(2, Thread.ofVirtual().name("durable-work-", 0).factory());
        // Fixed delay prevents overlapping attempts and catch-up storms after a slow provider or host sleep.
        executor.scheduleWithFixedDelay(() -> attempt(delivery), deliveryDelay, deliveryDelay, TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(() -> attempt(cleanup), 0, cleanupDelay, TimeUnit.MILLISECONDS);
    }

    private void attempt(Runnable work) {
        if (!running.get()) return;
        try { if (ready.getAsBoolean() && running.get()) work.run(); }
        catch (RuntimeException unavailable) { /* Retry next tick; never emit provider details or authority. */ }
    }

    @Override public void stop() {
        ScheduledExecutorService stopped;
        synchronized (this) {
            running.set(false);
            stopped = executor;
            executor = null;
        }
        if (stopped == null) return;
        stopped.shutdown();
        try { if (!stopped.awaitTermination(shutdownMillis, TimeUnit.MILLISECONDS)) stopped.shutdownNow(); }
        catch (InterruptedException interrupted) { stopped.shutdownNow(); Thread.currentThread().interrupt(); }
    }

    @Override public void stop(Runnable callback) {
        Thread.ofVirtual().name("durable-work-stop").start(() -> { try { stop(); } finally { callback.run(); } });
    }
    @Override public boolean isRunning() { return running.get(); }
    @Override public int getPhase() { return Integer.MAX_VALUE - 2048; }
    @Override public String toString() { return "RuntimeWorkers[configuration=REDACTED]"; }
}
