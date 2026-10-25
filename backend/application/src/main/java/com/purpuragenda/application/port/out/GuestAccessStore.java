package com.purpuragenda.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Challenge consumption and persisted scope creation commit together. No session is issued here. */
public interface GuestAccessStore {
    enum Kind { CREATE, MANAGE }
    /** Browser hash comes from a server-owned secret binding, never an arbitrary client field. */
    record Redemption(String token, Kind purpose, String browserHash) {
        public Redemption {
            if (token==null || !token.matches("[A-Za-z0-9_-]{43}") || purpose==null
                    || browserHash==null || !browserHash.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("INVALID_GUEST_PROOF");
        }
        @Override public String toString() { return "GuestRedemption[REDACTED]"; }
    }
    /** Internal metadata, not a bearer capability. P17 must attach it to the bound browser atomically. */
    record Grant(UUID id, UUID customerId, Kind kind, UUID bookingId, Instant issuedAt, Instant expiresAt) {
        @Override public String toString() { return "GuestGrant[REDACTED]"; }
    }
    /** Empty for unknown, replayed, expired, wrong-purpose/browser or obsolete delivery-contact proof. */
    Optional<Grant> redeem(Redemption request);
}
