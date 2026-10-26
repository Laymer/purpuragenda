package com.purpuragenda.bootstrap;

import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class RuntimeWorkersTest {
    static RuntimeWorkers workers(Runnable delivery, Runnable cleanup, BooleanSupplier ready, boolean suppressed) {
        return new RuntimeWorkers(delivery, cleanup, ready, suppressed,
                Duration.ofMillis(5), Duration.ofMillis(5), Duration.ofSeconds(2));
    }
    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(); }
    }
    static void until(BooleanSupplier condition) throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < limit) Thread.sleep(2);
        assertTrue(condition.getAsBoolean());
    }
    @Test void restoreNeverStartsThreadsOrCallsAnyPortAndStopIsIdempotent() {
        var calls = new AtomicInteger();
        var worker = workers(calls::incrementAndGet, calls::incrementAndGet, () -> { calls.incrementAndGet(); return true; }, true);
        worker.start(); worker.start(); worker.stop(); worker.stop();
        assertFalse(worker.isRunning()); assertEquals(0, calls.get());
        assertEquals("RuntimeWorkers[configuration=REDACTED]", worker.toString());
        assertEquals(Integer.MAX_VALUE - 2048, worker.getPhase());
    }
    @Test void unreadyOrFailingReadinessDoesNoWorkAndRecoversWithoutRestart() throws Exception {
        var ready = new AtomicInteger(); var checked = new CountDownLatch(3); var work = new CountDownLatch(2);
        var worker = workers(work::countDown, work::countDown, () -> {
            checked.countDown();
            if (ready.get() == 0) throw new IllegalStateException("synthetic-private-detail");
            return ready.get() == 2;
        }, false);
        try {
            worker.start(); worker.start(); await(checked); assertEquals(2, work.getCount());
            ready.set(1); Thread.sleep(25); assertEquals(2, work.getCount());
            ready.set(2); await(work); assertTrue(worker.isRunning());
        } finally { worker.stop(); }
    }
    @Test void failedAttemptsDoNotCancelEitherPeriodicTask() {
        var delivery = new AtomicInteger(); var cleanup = new AtomicInteger();
        var delivered = new CountDownLatch(1); var cleaned = new CountDownLatch(1);
        var worker = workers(() -> { if (delivery.incrementAndGet() == 1) throw new IllegalStateException("synthetic-private-detail"); delivered.countDown(); },
                () -> { if (cleanup.incrementAndGet() == 1) throw new IllegalStateException("synthetic-private-detail"); cleaned.countDown(); }, () -> true, false);
        try { worker.start(); await(delivered); await(cleaned); assertTrue(delivery.get() >= 2); assertTrue(cleanup.get() >= 2); }
        finally { worker.stop(); }
    }
    @Test void slowDeliveryCannotOverlapItselfOrBlockCleanupAndShutdownDrainsTheCurrentAttempt() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var cleanup = new CountDownLatch(1);
        var active = new AtomicInteger(); var maximum = new AtomicInteger(); var calls = new AtomicInteger();
        var worker = workers(() -> {
            calls.incrementAndGet(); maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            entered.countDown(); await(release); active.decrementAndGet();
        }, cleanup::countDown, () -> true, false);
        worker.start(); await(entered); await(cleanup);
        var stopped = new CountDownLatch(1); worker.stop(stopped::countDown);
        until(() -> !worker.isRunning()); assertFalse(stopped.await(20, TimeUnit.MILLISECONDS));
        release.countDown(); await(stopped);
        assertEquals(1, maximum.get()); assertEquals(1, calls.get()); assertEquals(0, active.get());
        worker.stop();
    }
    @Test void shutdownTimeoutInterruptsWorkAndRetainsBoundedCallback() {
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        var worker = new RuntimeWorkers(() -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { interrupted.countDown(); Thread.currentThread().interrupt(); }
        }, () -> {}, () -> true, false, Duration.ofMillis(1), Duration.ofHours(1), Duration.ofMillis(10));
        worker.start(); await(entered); worker.stop(); await(interrupted); assertFalse(worker.isRunning());
    }
    @Test void interruptedStopPreservesCallerInterruptAndCancelsPendingWork() {
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        var worker = workers(() -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { interrupted.countDown(); }
        }, () -> {}, () -> true, false);
        worker.start(); await(entered); Thread.currentThread().interrupt();
        try { worker.stop(); assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
        await(interrupted); assertFalse(worker.isRunning());
    }
    @Test void completedStopAllowsASeparateRuntimeRestart() {
        var delivered = new AtomicInteger(); var twice = new CountDownLatch(2);
        var worker = workers(() -> { delivered.incrementAndGet(); twice.countDown(); }, () -> {}, () -> true, false);
        try {
            worker.start(); until(() -> delivered.get() > 0); worker.stop(); int stopped = delivered.get();
            worker.start(); until(() -> delivered.get() > stopped); await(twice);
        } catch (Exception failure) { throw new AssertionError(); }
        finally { worker.stop(); }
    }
    @Test void invalidIntervalsAndMissingWorkFailBeforeAnyThreadStarts() {
        Runnable noop = () -> {}; BooleanSupplier yes = () -> true;
        assertThrows(NullPointerException.class, () -> workers(null, noop, yes, false));
        assertThrows(NullPointerException.class, () -> workers(noop, null, yes, false));
        assertThrows(NullPointerException.class, () -> workers(noop, noop, null, false));
        for (Duration invalid : new Duration[] {null, Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(1)}) {
            assertThrows(IllegalArgumentException.class, () -> new RuntimeWorkers(noop, noop, yes, false, invalid, Duration.ofSeconds(1), Duration.ofSeconds(1)));
            assertThrows(IllegalArgumentException.class, () -> new RuntimeWorkers(noop, noop, yes, false, Duration.ofSeconds(1), invalid, Duration.ofSeconds(1)));
            assertThrows(IllegalArgumentException.class, () -> new RuntimeWorkers(noop, noop, yes, false, Duration.ofSeconds(1), Duration.ofSeconds(1), invalid));
        }
    }
    @Test void allFiveLanesRemainIndependentWhenBothMailSendersBlock() {
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        var maintenance = new CountDownLatch(3);
        Runnable mail = () -> { entered.countDown(); await(release); };
        var lanes = List.of(new RuntimeWorkers.Lane(mail,0,5),new RuntimeWorkers.Lane(mail,0,5),
                new RuntimeWorkers.Lane(maintenance::countDown,0,5),new RuntimeWorkers.Lane(maintenance::countDown,0,5),
                new RuntimeWorkers.Lane(maintenance::countDown,0,5));
        var worker = new RuntimeWorkers(lanes,()->true,false,Duration.ofSeconds(2));
        try { worker.start(); await(entered); await(maintenance); }
        finally { release.countDown(); worker.stop(); }
    }
    @Test void eachLaneRecoversFromFailureAndTheLaneListIsDefensivelyCopied() {
        var recovered = new CountDownLatch(5); var lanes = new ArrayList<RuntimeWorkers.Lane>();
        for(int i=0;i<5;i++) {
            var attempts = new AtomicInteger();
            lanes.add(new RuntimeWorkers.Lane(()-> {
                if(attempts.incrementAndGet()==1)throw new IllegalStateException("synthetic-private-detail");
                if(attempts.get()==2)recovered.countDown();
            },0,5));
        }
        var worker = new RuntimeWorkers(lanes,()->true,false,Duration.ofSeconds(2)); lanes.clear();
        try { worker.start(); await(recovered); } finally { worker.stop(); }
    }
    @Test void productionFiveLaneConfigurationSuppressesEveryPortInRestore() {
        Runnable forbidden = () -> fail("Restore invoked a work port");
        var worker = new RuntimeWorkers(forbidden,forbidden,forbidden,forbidden,forbidden,
                ()->{fail("Restore checked readiness");return true;},true);
        worker.start(); assertFalse(worker.isRunning()); worker.stop();
    }
    @Test void laneBoundsAreValidatedBeforeScheduling() {
        Runnable noop=()->{};
        assertThrows(IllegalArgumentException.class,()->new RuntimeWorkers.Lane(noop,-1,1));
        assertThrows(IllegalArgumentException.class,()->new RuntimeWorkers.Lane(noop,0,0));
        assertThrows(NullPointerException.class,()->new RuntimeWorkers.Lane(null,0,1));
        assertThrows(IllegalArgumentException.class,()->new RuntimeWorkers(null,()->true,false,Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->new RuntimeWorkers(List.of(),()->true,false,Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->new RuntimeWorkers(java.util.Collections.nCopies(6,new RuntimeWorkers.Lane(noop,0,1)),()->true,false,Duration.ofSeconds(1)));
    }

}
