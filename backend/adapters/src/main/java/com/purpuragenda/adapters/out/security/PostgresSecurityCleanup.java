package com.purpuragenda.adapters.out.security;

import com.purpuragenda.application.port.out.SecurityCleanupStore;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

/**
 * Retention cleanup. Independent rows are claimed with SKIP LOCKED in id order and never waited for. The one mixed
 * transaction takes the scheduling resource first, then grants, referencing sessions, delivery jobs and finally
 * challenges, and deletes children before parents. Time is captured once before waiting for the resource lock, which
 * is conservative for lease liveness: a lease is live whenever lease_until is after that time, and live fenced
 * leases are never altered. No envelope is ever read or decrypted.
 */
public final class PostgresSecurityCleanup implements SecurityCleanupStore {
    private static final int MAX_LIMIT = 500;
    private final DataSource source;

    public PostgresSecurityCleanup(DataSource source) { this.source = Objects.requireNonNull(source); }

    @FunctionalInterface private interface Work { int run(Connection c) throws SQLException; }

    @Override public int sessions(int limit, Duration grace) {
        check(limit, grace);
        return transaction(c -> {
            Instant now = now(c);
            return update(c, """
                DELETE FROM app_session WHERE id IN (
                  SELECT id FROM app_session
                  WHERE LEAST(COALESCE(revoked_at,'infinity'::timestamptz),absolute_expires_at,
                              last_seen_at+CASE WHEN kind='ADMIN' THEN INTERVAL '15 minutes' ELSE INTERVAL '30 minutes' END)
                        +?*INTERVAL '1 second'<=?
                  ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED)
                """, grace.toSeconds(), now, limit);
        });
    }

    @Override public int proofs(int limit, Duration grace) {
        check(limit, grace);
        return transaction(c -> {
            Instant now = now(c);
            int removed = update(c, """
                DELETE FROM admin_authentication_proof WHERE id IN (
                  SELECT id FROM admin_authentication_proof
                  WHERE LEAST(expires_at,COALESCE(consumed_at,'infinity'::timestamptz),COALESCE(revoked_at,'infinity'::timestamptz))
                        +?*INTERVAL '1 second'<=?
                  ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED)
                """, grace.toSeconds(), now, limit);
            if (removed >= limit) return removed;
            removed += update(c, """
                DELETE FROM account_authentication_proof WHERE id IN (
                  SELECT id FROM account_authentication_proof
                  WHERE LEAST(expires_at,COALESCE(consumed_at,'infinity'::timestamptz),COALESCE(revoked_at,'infinity'::timestamptz))
                        +?*INTERVAL '1 second'<=?
                  ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED)
                """, grace.toSeconds(), now, limit - removed);
            if (removed >= limit) return removed;
            // A grant whose recovery code is still usable is never removed: the unique code binding must remain.
            return removed + update(c, """
                DELETE FROM admin_reenrollment_grant WHERE id IN (
                  SELECT g.id FROM admin_reenrollment_grant g
                  WHERE LEAST(g.expires_at,COALESCE(g.consumed_at,'infinity'::timestamptz),COALESCE(g.revoked_at,'infinity'::timestamptz))
                        +?*INTERVAL '1 second'<=?
                    AND EXISTS(SELECT 1 FROM admin_recovery_code k WHERE k.id=g.recovery_code_id AND (k.consumed_at IS NOT NULL OR k.revoked_at IS NOT NULL))
                  ORDER BY g.id LIMIT ? FOR UPDATE OF g SKIP LOCKED)
                """, grace.toSeconds(), now, limit - removed);
        });
    }

    @Override public int rateBuckets(int limit, Duration grace) {
        check(limit, grace);
        return transaction(c -> {
            Instant now = now(c);
            return update(c, """
                DELETE FROM rate_limit_bucket WHERE key IN (
                  SELECT key FROM rate_limit_bucket WHERE reset_at+?*INTERVAL '1 second'<=?
                  ORDER BY key LIMIT ? FOR UPDATE SKIP LOCKED)
                """, grace.toSeconds(), now, limit);
        });
    }

    @Override public int guestAuthority(int limit, Duration retention) {
        check(limit, retention);
        return transaction(c -> {
            Instant now = now(c);
            long seconds = retention.toSeconds();
            // Cheap unlocked peek: the scheduling resource is not contended when nothing is eligible.
            if (!exists(c, """
                SELECT EXISTS(SELECT 1 FROM access_grant WHERE LEAST(expires_at,COALESCE(revoked_at,expires_at))+?*INTERVAL '1 second'<=?)
                    OR EXISTS(SELECT 1 FROM email_challenge WHERE expires_at+?*INTERVAL '1 second'<=?)
                """, seconds, now, seconds, now)) return 0;
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT id FROM scheduling_resource WHERE id=1 FOR UPDATE")) {
                if (!r.next()) throw new SQLException();
            }
            // 1. grants, children first so a parent is never chosen ahead of its children, skipping rows held elsewhere.
            var grants = new LinkedHashMap<UUID, UUID>();
            try (var q = c.prepareStatement("""
                    SELECT id,parent_grant_id FROM access_grant
                    WHERE LEAST(expires_at,COALESCE(revoked_at,expires_at))+?*INTERVAL '1 second'<=?
                    ORDER BY parent_grant_id IS NULL,id LIMIT ? FOR UPDATE SKIP LOCKED
                    """)) {
                q.setLong(1, seconds); q.setTimestamp(2, Timestamp.from(now)); q.setInt(3, limit);
                try (var r = q.executeQuery()) { while (r.next()) grants.put(r.getObject(1, UUID.class), r.getObject(2, UUID.class)); }
            }
            // A parent is removable only when every child is removed in this same statement sequence.
            if (!grants.isEmpty()) {
                try (var q = c.prepareStatement("SELECT DISTINCT parent_grant_id FROM access_grant WHERE parent_grant_id=ANY(?) AND NOT id=ANY(?)")) {
                    Array ids = c.createArrayOf("uuid", grants.keySet().toArray());
                    q.setArray(1, ids); q.setArray(2, ids);
                    try (var r = q.executeQuery()) { while (r.next()) grants.remove(r.getObject(1, UUID.class)); }
                }
            }
            int removed = 0;
            if (!grants.isEmpty()) {
                Array all = c.createArrayOf("uuid", grants.keySet().toArray());
                // 2. referencing sessions, locked after their grants, then removed before any grant.
                update(c, "DELETE FROM app_session WHERE id IN (SELECT id FROM app_session WHERE grant_id=ANY(?) ORDER BY id FOR UPDATE)", all);
                // 3. children before parents.
                var children = new ArrayList<UUID>();
                var parents = new ArrayList<UUID>();
                grants.forEach((id, parent) -> (parent == null ? parents : children).add(id));
                if (!children.isEmpty()) removed += update(c, "DELETE FROM access_grant WHERE id=ANY(?)", c.createArrayOf("uuid", children.toArray()));
                if (!parents.isEmpty()) removed += update(c, "DELETE FROM access_grant WHERE id=ANY(?)", c.createArrayOf("uuid", parents.toArray()));
            }
            return removed + challenges(c, now, seconds, limit);
        });
    }

    /** Jobs first, then challenges, matching the delivery worker's job -> challenge order. */
    private static int challenges(Connection c, Instant now, long seconds, int limit) throws SQLException {
        var eligible = new ArrayList<UUID>();
        try (var q = c.prepareStatement("""
                SELECT e.id FROM email_challenge e
                WHERE e.expires_at+?*INTERVAL '1 second'<=? AND NOT EXISTS(SELECT 1 FROM access_grant g WHERE g.challenge_id=e.id)
                ORDER BY e.id LIMIT ?
                """)) {
            q.setLong(1, seconds); q.setTimestamp(2, Timestamp.from(now)); q.setInt(3, limit);
            try (var r = q.executeQuery()) { while (r.next()) eligible.add(r.getObject(1, UUID.class)); }
        }
        if (eligible.isEmpty()) return 0;
        Array candidates = c.createArrayOf("uuid", eligible.toArray());
        var blocked = new HashSet<UUID>();
        var detach = new ArrayList<UUID>();
        var locked = new HashSet<UUID>();
        try (var q = c.prepareStatement("""
                SELECT id,challenge_id,state,lease_until FROM notification_job
                WHERE challenge_id=ANY(?) ORDER BY id FOR UPDATE SKIP LOCKED
                """)) {
            q.setArray(1, candidates);
            try (var r = q.executeQuery()) {
                while (r.next()) {
                    UUID id = r.getObject(1, UUID.class), challenge = r.getObject(2, UUID.class);
                    Timestamp lease = r.getTimestamp(4);
                    locked.add(id);
                    if ("LEASED".equals(r.getString(3)) && lease != null && lease.toInstant().isAfter(now)) blocked.add(challenge);
                    else detach.add(id);
                }
            }
        }
        // Any referencing job we could not lock belongs to a concurrent worker: leave its challenge alone.
        try (var q = c.prepareStatement("SELECT id,challenge_id FROM notification_job WHERE challenge_id=ANY(?)")) {
            q.setArray(1, candidates);
            try (var r = q.executeQuery()) { while (r.next()) if (!locked.contains(r.getObject(1, UUID.class))) blocked.add(r.getObject(2, UUID.class)); }
        }
        eligible.removeAll(blocked);
        if (eligible.isEmpty()) return 0;
        Array removable = c.createArrayOf("uuid", eligible.toArray());
        // Clear the encrypted envelope and the reference together; terminal states keep their recorded outcome.
        try (var u = c.prepareStatement("""
                UPDATE notification_job SET encrypted_envelope=NULL,challenge_id=NULL,lease_token=NULL,lease_until=NULL,
                  safe_error=CASE WHEN state IN ('SENT','FAILED','SUPPRESSED') THEN safe_error ELSE 'AUTH_DELIVERY_EXPIRED' END,
                  state=CASE WHEN state IN ('SENT','FAILED','SUPPRESSED') THEN state ELSE 'SUPPRESSED' END
                WHERE challenge_id=ANY(?) AND id=ANY(?)
                """)) {
            u.setArray(1, removable); u.setArray(2, c.createArrayOf("uuid", detach.toArray()));
            u.executeUpdate();
        }
        return update(c, """
            DELETE FROM email_challenge WHERE id IN (
              SELECT e.id FROM email_challenge e WHERE e.id=ANY(?) AND NOT EXISTS(SELECT 1 FROM access_grant g WHERE g.challenge_id=e.id)
                AND NOT EXISTS(SELECT 1 FROM notification_job j WHERE j.challenge_id=e.id)
              ORDER BY e.id FOR UPDATE SKIP LOCKED)
            """, removable);
    }

    private static void check(int limit, Duration age) {
        if (limit < 1 || limit > MAX_LIMIT || age == null || age.isNegative()) throw new IllegalArgumentException("INVALID_CLEANUP_BOUNDS");
    }

    private static boolean exists(Connection c, String sql, Object... values) throws SQLException {
        try (var q = c.prepareStatement(sql)) {
            bind(q, values);
            try (var r = q.executeQuery()) { r.next(); return r.getBoolean(1); }
        }
    }

    private static int update(Connection c, String sql, Object... values) throws SQLException {
        try (var q = c.prepareStatement(sql)) { bind(q, values); return q.executeUpdate(); }
    }

    private static void bind(PreparedStatement q, Object[] values) throws SQLException {
        for (int i = 0; i < values.length; i++) q.setObject(i + 1, values[i] instanceof Instant t ? Timestamp.from(t) : values[i]);
    }

    private static Instant now(Connection c) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT clock_timestamp()")) { r.next(); return r.getTimestamp(1).toInstant(); }
    }

    private int transaction(Work work) {
        try (var c = source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try (var s = c.createStatement()) { s.execute("SET LOCAL lock_timeout='2s'"); s.execute("SET LOCAL statement_timeout='10s'"); }
            try { int result = work.run(c); c.commit(); return result; }
            catch (SQLException | RuntimeException failure) { try { c.rollback(); } catch (SQLException ignored) { } throw failure; }
        } catch (SQLException | RuntimeException failure) { throw new IllegalStateException("SECURITY_CLEANUP_FAILED"); }
    }

    @Override public String toString() { return "SecurityCleanupStore[configuration=REDACTED]"; }
}
