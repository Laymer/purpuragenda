package com.purpuragenda.application.port.out;

import com.purpuragenda.domain.customer.Input;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Atomic challenge/delivery persistence only; caller supplies verified authority separately. */
public interface AuthDeliveryStore {
    enum Purpose { CREATE, ACCOUNT, MANAGE, CONTACT }
    record Issue(String email, Purpose purpose, UUID bookingId, String browserHash) {
        public Issue {
            email=Input.email(email); Objects.requireNonNull(purpose);
            boolean browserBound=purpose==Purpose.CREATE || purpose==Purpose.ACCOUNT;
            if (browserBound ? bookingId!=null || browserHash==null || !browserHash.matches("[a-f0-9]{64}")
                    : bookingId==null || browserHash!=null) throw new IllegalArgumentException("INVALID_CHALLENGE_SCOPE");
        }
        @Override public String toString() { return "AuthDeliveryIssue[private=REDACTED]"; }
    }
    record Receipt(UUID challengeId, UUID jobId, Instant expiresAt) { }
    Receipt issue(Issue issue);
    /** Bounded, no-decryption cleanup. Live leases remain owned by their worker. */
    int eraseExpired();
    Optional<MailDelivery.Message> prepare(NotificationQueue.Lease lease);
}
