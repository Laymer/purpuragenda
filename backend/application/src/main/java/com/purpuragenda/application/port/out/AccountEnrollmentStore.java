package com.purpuragenda.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Atomic account enrollment: ACCOUNT challenge consumption, identity link, credential and profile commit together. */
public interface AccountEnrollmentStore {
    /** Browser hash comes from the server-owned binding; the encoded hash is already derived from a policy-valid password. */
    record Enrollment(String token, String browserHash, String passwordHash) {
        public Enrollment {
            if (token == null || !token.matches("[A-Za-z0-9_-]{43}") || browserHash == null || !browserHash.matches("[a-f0-9]{64}")
                    || passwordHash == null || passwordHash.isEmpty() || passwordHash.length() > 200)
                throw new IllegalArgumentException("INVALID_ACCOUNT_ENROLLMENT");
        }
        @Override public String toString() { return "AccountEnrollment[REDACTED]"; }
    }
    /** Internal metadata, not a bearer capability; sessions are created only by a separate authenticated exchange. */
    record Enrolled(UUID accountId, UUID customerId, Instant enrolledAt) {
        @Override public String toString() { return "AccountEnrolled[REDACTED]"; }
    }
    /**
     * Empty for unknown, replayed, expired, wrong-purpose or wrong-browser proof and for an identity that already has
     * an account; nothing is consumed or created in those cases.
     */
    Optional<Enrolled> enroll(Enrollment request);
}
