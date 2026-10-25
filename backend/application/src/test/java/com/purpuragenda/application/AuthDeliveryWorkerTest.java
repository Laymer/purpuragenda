package com.purpuragenda.application;

import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.MailDelivery.Result;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.purpuragenda.application.AuthDeliveryWorker.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthDeliveryWorkerTest {
    static final class Ports implements NotificationQueue,AuthDeliveryStore,MailDelivery {
        final List<String> calls=Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger renewals=new AtomicInteger();
        Runnable onSend=()->{},onHeartbeat=()->{};
        final Lease lease=new Lease(UUID.randomUUID(),UUID.randomUUID(),Instant.MAX);
        Optional<Lease> claimed=Optional.of(lease);
        Optional<Message> prepared=Optional.of(new Message(lease.id(),"synthetic@example.test","Verify","Synthetic"));
        Result result=Result.SENT;
        boolean renewed=true,acknowledged=true,transportThrows,storageThrows;
        @Override public Optional<Lease> claim() { throw new AssertionError("General queue must never be consumed"); }
        @Override public Optional<Lease> claimAuth() { calls.add("claim");return claimed; }
        @Override public boolean renew(Lease l) { assertSame(lease,l);calls.add("renew");if(renewals.incrementAndGet()>1) onHeartbeat.run();return renewed; }
        @Override public boolean sent(Lease l) { assertSame(lease,l);calls.add("sent");return acknowledged; }
        @Override public boolean failed(Lease l) { assertSame(lease,l);calls.add("failed");return acknowledged; }
        @Override public Receipt issue(Issue request) { throw new AssertionError("Worker must not issue challenges"); }
        @Override public int eraseExpired() { calls.add("sweep");if(storageThrows) throw new IllegalArgumentException("SYNTHETIC_PRIVATE");return 0; }
        @Override public Optional<Message> prepare(Lease l) { assertSame(lease,l);calls.add("prepare");return prepared; }
        @Override public Result send(Message m) { assertSame(prepared.orElseThrow(),m);calls.add("send");onSend.run();if(transportThrows) throw new IllegalArgumentException("SYNTHETIC_PRIVATE");return result; }
        AuthDeliveryWorker worker(boolean suppressed) { return new AuthDeliveryWorker(this,this,this,suppressed); }
    }
    @Test void successfulTickSweepsClaimsAuthRenewsPreparesThenSendsAndAcknowledges() {
        var p=new Ports(); assertEquals(SENT,p.worker(false).tick());
        assertEquals(List.of("sweep","claim","renew","prepare","send","sent"),p.calls);
        assertTrue(p.worker(false).toString().contains("REDACTED"));
    }
    @Test void idleSuppressedLostLeaseAndDiscardedPreparationNeverSend() {
        var p=new Ports();assertEquals(SUPPRESSED,p.worker(true).tick());assertEquals(List.of("sweep"),p.calls);
        p=new Ports();p.claimed=Optional.empty();assertEquals(IDLE,p.worker(false).tick());assertEquals(List.of("sweep","claim"),p.calls);
        p=new Ports();p.renewed=false;assertEquals(STALE,p.worker(false).tick());assertEquals(List.of("sweep","claim","renew"),p.calls);
        p=new Ports();p.prepared=Optional.empty();assertEquals(DISCARDED,p.worker(false).tick());assertEquals(List.of("sweep","claim","renew","prepare"),p.calls);
    }
    @Test void everyNonSentTransportOutcomeUsesDurableFailurePolicy() {
        for(var result:Result.values()) if(result!=Result.SENT) {
            var p=new Ports();p.result=result;assertEquals(RETRY_LATER,p.worker(false).tick());
            assertEquals(List.of("sweep","claim","renew","prepare","send","failed"),p.calls);
        }
        var p=new Ports();p.result=null;assertEquals(RETRY_LATER,p.worker(false).tick());assertEquals("failed",p.calls.getLast());
        p=new Ports();p.transportThrows=true;assertEquals(RETRY_LATER,p.worker(false).tick());assertEquals("failed",p.calls.getLast());
    }
    @Test void leaseLossDuringEitherCallbackNeverReportsAcknowledgedDelivery() {
        for(var result:Result.values()) {
            var p=new Ports();p.result=result;p.acknowledged=false;assertEquals(STALE,p.worker(false).tick());
        }
    }
    @Test void heartbeatRenewsDuringBlockedSendAndStopsBeforeAcknowledgement() {
        var p=new Ports(); var renewed=new CountDownLatch(1);p.onHeartbeat=renewed::countDown;
        p.onSend=()->await(renewed);
        assertEquals(SENT,new AuthDeliveryWorker(p,p,p,false,Duration.ofMillis(1)).tick());
        assertTrue(p.renewals.get()>1);assertEquals("sent",p.calls.getLast());
    }
    @Test void failedOrThrowingHeartbeatNeverAcknowledgesTheTransportResult() {
        for(boolean throwing:new boolean[]{false,true}) {
            var p=new Ports();var heartbeat=new CountDownLatch(1);
            p.onHeartbeat=()->{p.renewed=false;heartbeat.countDown();if(throwing) throw new IllegalArgumentException("SYNTHETIC_PRIVATE");};
            p.onSend=()->await(heartbeat);
            assertEquals(STALE,new AuthDeliveryWorker(p,p,p,false,Duration.ofMillis(1)).tick());
            assertFalse(p.calls.contains("sent"));assertFalse(p.calls.contains("failed"));
        }
    }
    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5,TimeUnit.SECONDS)); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new AssertionError("SYNTHETIC_TIMEOUT"); }
    }
    @Test void storageExceptionsAreFixedAndCauseFreeAndDependenciesRequired() {
        var p=new Ports();p.storageThrows=true;
        for(Duration interval:new Duration[]{Duration.ZERO,Duration.ofSeconds(-1),Duration.ofSeconds(21)})
            assertThrows(IllegalArgumentException.class,()->new AuthDeliveryWorker(p,p,p,false,interval));
        assertThrows(IllegalArgumentException.class,()->new AuthDeliveryWorker(p,p,p,false,null));
        var failure=assertThrows(IllegalStateException.class,()->p.worker(false).tick());
        assertEquals("AUTH_WORKER_FAILED",failure.getMessage());assertNull(failure.getCause());assertEquals(0,failure.getSuppressed().length);
        assertThrows(NullPointerException.class,()->new AuthDeliveryWorker(null,p,p,false));
        assertThrows(NullPointerException.class,()->new AuthDeliveryWorker(p,null,p,false));
        assertThrows(NullPointerException.class,()->new AuthDeliveryWorker(p,p,null,false));
    }
}
