package com.purpuragenda.application.notification;

import com.purpuragenda.application.port.out.*;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One booking-notification attempt. The lease is claimed and committed first, facts are read from the CURRENT booking, and
 * SMTP runs with no database transaction open. A crash after SMTP acceptance but before the acknowledgement leaves the
 * lease to expire and the message is sent again with the same Message-ID: duplicates are possible, never silent loss.
 */
public final class BookingNotificationWorker {
    public enum Outcome { IDLE, SUPPRESSED_BY_OPERATOR, STALE, DISCARDED, SENT, RETRY_LATER }
    private final NotificationQueue queue;
    private final BookingNotificationStore store;
    private final MailDelivery mail;
    private final boolean suppressed;
    private final long heartbeatNanos;
    public BookingNotificationWorker(NotificationQueue queue,BookingNotificationStore store,MailDelivery mail,boolean suppressed) {
        this(queue,store,mail,suppressed,Duration.ofSeconds(20));
    }
    public BookingNotificationWorker(NotificationQueue queue,BookingNotificationStore store,MailDelivery mail,boolean suppressed,Duration heartbeat) {
        if(heartbeat==null || heartbeat.isNegative() || heartbeat.isZero() || heartbeat.compareTo(Duration.ofSeconds(20))>0)
            throw new IllegalArgumentException("INVALID_NOTIFICATION_HEARTBEAT");
        heartbeatNanos=heartbeat.toNanos();
        this.queue=Objects.requireNonNull(queue);this.store=Objects.requireNonNull(store);this.mail=Objects.requireNonNull(mail);this.suppressed=suppressed;
    }
    public Outcome tick() {
        try {
            if(suppressed)return Outcome.SUPPRESSED_BY_OPERATOR;
            var claimed=queue.claimBooking();
            if(claimed.isEmpty())return Outcome.IDLE;
            var lease=claimed.orElseThrow();
            if(!queue.renew(lease))return Outcome.STALE;
            var facts=store.load(lease);
            if(facts.isEmpty())return Outcome.STALE;
            var prepared=NotificationContent.prepare(facts.orElseThrow());
            if(prepared instanceof NotificationContent.Suppress discard)
                return queue.suppressed(lease,discard.code())?Outcome.DISCARDED:Outcome.STALE;
            var message=((NotificationContent.Send)prepared).message();
            // Recheck the live lease and operator suppression after fact preparation, immediately before SMTP.
            if(!queue.renew(lease))return Outcome.STALE;
            MailDelivery.Result result;var lost=new AtomicBoolean();
            try(var heartbeat=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())) {
                var task=heartbeat.scheduleWithFixedDelay(()->{
                    try { if(!queue.renew(lease))lost.set(true); }
                    catch(RuntimeException failed) { lost.set(true); }
                },heartbeatNanos,heartbeatNanos,TimeUnit.NANOSECONDS);
                try { result=mail.send(message); }
                catch(RuntimeException failed) { result=MailDelivery.Result.RETRY_LATER; }
                finally { task.cancel(false); }
            }
            if(lost.get())return Outcome.STALE;
            if(result==MailDelivery.Result.SENT)return queue.sent(lease)?Outcome.SENT:Outcome.STALE;
            return queue.failed(lease)?Outcome.RETRY_LATER:Outcome.STALE;
        } catch(RuntimeException failed) { throw new IllegalStateException("NOTIFICATION_WORKER_FAILED"); }
    }
    @Override public String toString() { return "BookingNotificationWorker[configuration=REDACTED]"; }
}
