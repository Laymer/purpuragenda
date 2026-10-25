package com.purpuragenda.application;

import com.purpuragenda.application.port.out.*;
import java.util.Objects;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One AUTH attempt and a bounded cleanup batch. Ports finish their transactions before network delivery begins. */
public final class AuthDeliveryWorker {
    public enum Outcome { IDLE, SUPPRESSED, DISCARDED, STALE, SENT, RETRY_LATER }
    private final NotificationQueue queue;
    private final AuthDeliveryStore store;
    private final MailDelivery mail;
    private final boolean suppressed;
    private final long heartbeatNanos;
    public AuthDeliveryWorker(NotificationQueue queue,AuthDeliveryStore store,MailDelivery mail,boolean suppressed) {
        this(queue,store,mail,suppressed,Duration.ofSeconds(20));
    }
    public AuthDeliveryWorker(NotificationQueue queue,AuthDeliveryStore store,MailDelivery mail,boolean suppressed,Duration heartbeat) {
        if(heartbeat==null || heartbeat.isNegative() || heartbeat.isZero() || heartbeat.compareTo(Duration.ofSeconds(20))>0)
            throw new IllegalArgumentException("INVALID_AUTH_HEARTBEAT");
        heartbeatNanos=heartbeat.toNanos();
        this.queue=Objects.requireNonNull(queue); this.store=Objects.requireNonNull(store);
        this.mail=Objects.requireNonNull(mail); this.suppressed=suppressed;
    }
    public Outcome tick() {
        try {
            store.eraseExpired();
            if(suppressed) return Outcome.SUPPRESSED;
            var claimed=queue.claimAuth();
            if(claimed.isEmpty()) return Outcome.IDLE;
            var lease=claimed.orElseThrow();
            if(!queue.renew(lease)) return Outcome.STALE;
            var message=store.prepare(lease);
            if(message.isEmpty()) return Outcome.DISCARDED;
            MailDelivery.Result result; var lost=new AtomicBoolean();
            // No database transaction spans send. close() joins an in-progress renewal before ACK.
            try(var heartbeat=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())) {
                var task=heartbeat.scheduleWithFixedDelay(()->{
                    try { if(!queue.renew(lease)) lost.set(true); }
                    catch(RuntimeException failed) { lost.set(true); }
                },heartbeatNanos,heartbeatNanos,TimeUnit.NANOSECONDS);
                try { result=mail.send(message.orElseThrow()); }
                catch(RuntimeException failed) { result=MailDelivery.Result.RETRY_LATER; }
                finally { task.cancel(false); }
            }
            if(lost.get()) return Outcome.STALE;
            if(result==MailDelivery.Result.SENT)
                return queue.sent(lease)?Outcome.SENT:Outcome.STALE;
            return queue.failed(lease)?Outcome.RETRY_LATER:Outcome.STALE;
        } catch(RuntimeException failed) { throw new IllegalStateException("AUTH_WORKER_FAILED"); }
    }
    @Override public String toString() { return "AuthDeliveryWorker[configuration=REDACTED]"; }
}
