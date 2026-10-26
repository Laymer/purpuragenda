package com.purpuragenda.application.port.out;

import java.time.Instant;
import java.util.*;

/** Current-state read model for one leased booking-notification job; read after the claim commits, never under a send. */
public interface BookingNotificationStore {
    /**
     * Facts about the CURRENT booking, recipient and later outbox events for the leased job, captured at database time.
     * Empty when the lease is no longer live, the job is not a booking notification or the booking vanished.
     */
    Optional<Facts> load(NotificationQueue.Lease lease);
    /** Intake, birth data, private notes and tokens are deliberately absent. */
    record Facts(UUID jobId,String audience,String template,long jobVersion,String recipient,String reference,String serviceName,
                 String state,Instant start,Instant expiresAt,String meetingUrl,Set<String> laterEventTypes,Instant now) {
        public Facts { laterEventTypes=Set.copyOf(laterEventTypes);Objects.requireNonNull(jobId);Objects.requireNonNull(now); }
        @Override public String toString() { return "NotificationFacts[job="+jobId+", content=REDACTED]"; }
    }
}
