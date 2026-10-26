package com.purpuragenda.application.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Distributed throttle storage. Subjects are normalized by the caller and are never stored or
 * logged raw: adapters persist only scoped keyed digests.
 */
public interface RateLimitStore {
    /** Limits and fixed-window lengths are fixed by the approved policy and mirrored by migration 013. */
    enum Kind {
        EMAIL_COOLDOWN(1, Duration.ofSeconds(60)),
        EMAIL_ADDRESS(5, Duration.ofHours(1)),
        EMAIL_IP(30, Duration.ofHours(1)),
        ADMIN_ATTEMPT(5, Duration.ofMinutes(15)),
        ACCOUNT_ATTEMPT(5, Duration.ofMinutes(15)),
        AVAILABILITY_IP(120, Duration.ofSeconds(60));
        private final int limit;
        private final Duration window;
        Kind(int limit, Duration window) { this.limit = limit; this.window = window; }
        public int limit() { return limit; }
        public Duration window() { return window; }
    }

    record Charge(Kind kind, String subject) {
        public Charge {
            Objects.requireNonNull(kind);
            if (subject == null || subject.isEmpty() || subject.length() > 600) throw new IllegalArgumentException("INVALID_THROTTLE_SUBJECT");
        }
        @Override public String toString() { return "ThrottleCharge[REDACTED]"; }
    }

    /** Opaque handle for releasing exactly the window that admitted one reserved attempt. */
    record Reservation(Kind kind, String bucket, Instant windowStartedAt) {
        public Reservation {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(windowStartedAt);
            if (bucket == null || !bucket.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("INVALID_THROTTLE_RESERVATION");
        }
        @Override public String toString() { return "ThrottleReservation[REDACTED]"; }
    }

    /** Denials carry a positive whole-second Retry-After and no reservations. */
    record Decision(boolean allowed, long retryAfterSeconds, List<Reservation> reservations) {
        public Decision {
            reservations = List.copyOf(reservations);
            if (allowed ? retryAfterSeconds != 0 : retryAfterSeconds < 1 || !reservations.isEmpty())
                throw new IllegalArgumentException("INVALID_THROTTLE_DECISION");
        }
        public static Decision allow(List<Reservation> reservations) { return new Decision(true, 0, reservations); }
        public static Decision deny(long retryAfterSeconds) { return new Decision(false, retryAfterSeconds, List.of()); }
    }

    /**
     * Atomically admits all charges or none, using one database-captured time after sorted bucket
     * locks. Each admitted charge counts once; the reservations are in input order.
     */
    Decision admit(List<Charge> charges);

    /** Decrements only the exact still-current window; an elapsed or reset window is left untouched. */
    boolean release(Reservation reservation);
}
