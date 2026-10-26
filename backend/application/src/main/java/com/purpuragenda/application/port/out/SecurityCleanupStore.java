package com.purpuragenda.application.port.out;

import java.time.Duration;

/**
 * Bounded retention cleanup. Every method removes at most {@code limit} root rows in its own
 * transaction, uses one database time, skips rows locked elsewhere and never reads secret material.
 */
public interface SecurityCleanupStore {
    /** Revoked, absolutely expired or idle-expired sessions older than the grace period. Session locks only. */
    int sessions(int limit, Duration grace);
    /** Expired/consumed/revoked authentication proofs and reenrollment grants older than the grace period. */
    int proofs(int limit, Duration grace);
    /**
     * Dead guest grants and expired challenges. Lock order: scheduling resource, grants, referencing sessions,
     * delivery jobs, challenges; children are deleted before parents, envelopes and references are cleared before
     * challenge deletion and live fenced leases are never stolen.
     */
    int guestAuthority(int limit, Duration retention);
    /** Fixed-window throttle rows whose window ended more than the grace period ago. */
    int rateBuckets(int limit, Duration grace);
}
