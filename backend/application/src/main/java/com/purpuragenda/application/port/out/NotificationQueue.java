package com.purpuragenda.application.port.out;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable lease operations only. Preparation and network delivery occur after claim commits. */
public interface NotificationQueue {
    record Lease(UUID id, UUID token, Instant expiresAt) {
        public Lease { Objects.requireNonNull(id); Objects.requireNonNull(token); Objects.requireNonNull(expiresAt); }
        public boolean liveAt(Instant now) { return expiresAt.isAfter(now); }
        @Override public String toString() { return "NotificationLease[id=" + id + ", token=REDACTED]"; }
    }
    Optional<Lease> claim();
    /** AUTH-only selection and expired-lease reclamation; never mutates booking-template jobs. */
    Optional<Lease> claimAuth();
    boolean renew(Lease lease);
    boolean sent(Lease lease);
    boolean failed(Lease lease);
}
