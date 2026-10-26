package com.purpuragenda.bootstrap;

import java.time.Duration;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.springframework.context.SmartLifecycle;

/** Independent, bounded work lanes. Durable leases remain in PostgreSQL, never in this scheduler. */
final class RuntimeWorkers implements SmartLifecycle {
    record Lane(Runnable work,long initialDelay,long delay) {
        Lane { Objects.requireNonNull(work);if(initialDelay<0 || delay<1)throw new IllegalArgumentException("INVALID_WORKER_INTERVAL"); }
    }
    private final List<Lane> lanes;
    private final BooleanSupplier ready;
    private final boolean suppressed;
    private final long shutdownMillis;
    private final AtomicBoolean running = new AtomicBoolean();
    private ScheduledExecutorService executor;

    RuntimeWorkers(Runnable delivery, Runnable cleanup, BooleanSupplier ready, boolean suppressed) {
        this(delivery, cleanup, ready, suppressed, Duration.ofSeconds(1), Duration.ofHours(1), Duration.ofSeconds(25));
    }

    RuntimeWorkers(Runnable delivery, Runnable cleanup, BooleanSupplier ready, boolean suppressed,
                   Duration deliveryDelay, Duration cleanupDelay, Duration shutdown) {
        this(List.of(new Lane(delivery,positive(deliveryDelay),positive(deliveryDelay)),new Lane(cleanup,0,positive(cleanupDelay))),ready,suppressed,shutdown);
    }
    RuntimeWorkers(Runnable auth,Runnable cleanup,Runnable expiry,Runnable expand,Runnable booking,BooleanSupplier ready,boolean suppressed) {
        this(List.of(new Lane(auth,1000,1000),new Lane(cleanup,0,3600000),new Lane(expiry,0,60000),
                new Lane(expand,1000,1000),new Lane(booking,1000,1000)),ready,suppressed,Duration.ofSeconds(25));
    }
    RuntimeWorkers(List<Lane> lanes,BooleanSupplier ready,boolean suppressed,Duration shutdown) {
        if(lanes==null || lanes.isEmpty() || lanes.size()>5)throw new IllegalArgumentException("INVALID_WORKER_LANES");
        this.lanes=List.copyOf(lanes);this.ready=Objects.requireNonNull(ready);this.suppressed=suppressed;this.shutdownMillis=positive(shutdown);
    }

    private static long positive(Duration duration) {
        if (duration == null || duration.isNegative() || duration.toMillis() < 1) throw new IllegalArgumentException("INVALID_WORKER_INTERVAL");
        return duration.toMillis();
    }

    @Override public synchronized void start() {
        if (suppressed || !running.compareAndSet(false, true)) return;
        executor = Executors.newScheduledThreadPool(lanes.size(), Thread.ofVirtual().name("durable-work-", 0).factory());
        // Fixed delay prevents overlapping attempts and catch-up storms after a slow provider or host sleep.
        for(var lane:lanes)executor.scheduleWithFixedDelay(() -> attempt(lane.work()),lane.initialDelay(),lane.delay(),TimeUnit.MILLISECONDS);
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
