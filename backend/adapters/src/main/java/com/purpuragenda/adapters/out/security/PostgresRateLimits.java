package com.purpuragenda.adapters.out.security;

import com.purpuragenda.application.port.out.RateLimitStore;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

/**
 * Fixed-window distributed throttles. Buckets are locked one statement at a time in ascending digest order
 * (the insert-or-touch upsert both creates and locks), one database time is captured after every lock is held,
 * and either every charge is admitted or the transaction rolls back with no count changed.
 */
public final class PostgresRateLimits implements RateLimitStore {
    private static final int MAX_CHARGES = 8;
    private final DataSource source;
    private final RateKey key;

    public PostgresRateLimits(DataSource source, RateKey key) {
        this.source = Objects.requireNonNull(source);
        this.key = Objects.requireNonNull(key);
    }

    private record Bucket(String digest, Charge charge, int index) { }
    private record Held(Bucket bucket, int count, Instant started, Instant reset) { }

    @Override public Decision admit(List<Charge> charges) {
        if (charges == null || charges.isEmpty() || charges.size() > MAX_CHARGES) throw new IllegalArgumentException("INVALID_THROTTLE_CHARGES");
        var buckets = new ArrayList<Bucket>();
        var seen = new HashSet<String>();
        for (Charge charge : charges) {
            String digest = key.digest(charge.kind(), charge.subject());
            if (!seen.add(digest)) throw new IllegalArgumentException("DUPLICATE_THROTTLE_CHARGE");
            buckets.add(new Bucket(digest, charge, buckets.size()));
        }
        buckets.sort(Comparator.comparing(Bucket::digest));
        try (var c = source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try {
                limits(c);
                var held = new ArrayList<Held>();
                for (Bucket bucket : buckets) held.add(lock(c, bucket));
                Instant now = now(c);
                long retry = 0;
                for (Held h : held) {
                    boolean elapsed = !now.isBefore(h.reset);
                    int count = elapsed ? 0 : h.count;
                    if (count + 1 > h.bucket.charge.kind().limit()) retry = Math.max(retry, seconds(Duration.between(now, h.reset)));
                }
                if (retry > 0) { c.rollback(); return Decision.deny(retry); }
                var reservations = new Reservation[held.size()];
                for (Held h : held) {
                    Kind kind = h.bucket.charge.kind();
                    boolean elapsed = !now.isBefore(h.reset);
                    Instant started = elapsed ? now : h.started;
                    try (var u = c.prepareStatement("UPDATE rate_limit_bucket SET count=?,window_started_at=?,reset_at=? WHERE key=?")) {
                        u.setInt(1, (elapsed ? 0 : h.count) + 1);
                        u.setTimestamp(2, Timestamp.from(started));
                        u.setTimestamp(3, Timestamp.from(started.plus(kind.window())));
                        u.setString(4, h.bucket.digest);
                        if (u.executeUpdate() != 1) throw new SQLException();
                    }
                    reservations[h.bucket.index] = new Reservation(kind, h.bucket.digest, started);
                }
                c.commit();
                return Decision.allow(Arrays.asList(reservations));
            } catch (SQLException | RuntimeException failure) {
                try { c.rollback(); } catch (SQLException ignored) { }
                throw failure;
            }
        } catch (SQLException | RuntimeException failure) { throw storage(); }
    }

    @Override public boolean release(Reservation reservation) {
        Objects.requireNonNull(reservation);
        try (var c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                limits(c);
                boolean released;
                try (var u = c.prepareStatement("UPDATE rate_limit_bucket SET count=count-1 WHERE key=? AND kind=? AND window_started_at=? AND count>0")) {
                    u.setString(1, reservation.bucket());
                    u.setString(2, reservation.kind().name());
                    u.setTimestamp(3, Timestamp.from(reservation.windowStartedAt()));
                    released = u.executeUpdate() == 1;
                }
                c.commit();
                return released;
            } catch (SQLException | RuntimeException failure) {
                try { c.rollback(); } catch (SQLException ignored) { }
                throw failure;
            }
        } catch (SQLException | RuntimeException failure) { throw storage(); }
    }

    private Held lock(Connection c, Bucket bucket) throws SQLException {
        Kind kind = bucket.charge.kind();
        try (var q = c.prepareStatement("""
                INSERT INTO rate_limit_bucket(key,kind,key_id,count,window_started_at,reset_at)
                VALUES(?,?,?,0,TIMESTAMPTZ 'epoch',TIMESTAMPTZ 'epoch'+?*INTERVAL '1 second')
                ON CONFLICT(key) DO UPDATE SET count=rate_limit_bucket.count
                RETURNING count,window_started_at,reset_at
                """)) {
            q.setString(1, bucket.digest);
            q.setString(2, kind.name());
            q.setString(3, key.keyId());
            q.setLong(4, kind.window().toSeconds());
            try (var r = q.executeQuery()) {
                if (!r.next()) throw new SQLException();
                return new Held(bucket, r.getInt(1), r.getTimestamp(2).toInstant(), r.getTimestamp(3).toInstant());
            }
        }
    }

    /** Ceiling to whole seconds, never below one. */
    static long seconds(Duration remaining) {
        long whole = remaining.getSeconds();
        return Math.max(1, remaining.getNano() > 0 ? whole + 1 : whole);
    }

    private static void limits(Connection c) throws SQLException {
        try (var s = c.createStatement()) { s.execute("SET LOCAL lock_timeout='2s'"); s.execute("SET LOCAL statement_timeout='5s'"); }
    }

    private static Instant now(Connection c) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT clock_timestamp()")) { r.next(); return r.getTimestamp(1).toInstant(); }
    }

    private static IllegalStateException storage() { return new IllegalStateException("RATE_LIMIT_STORAGE_FAILED"); }

    @Override public String toString() { return "RateLimitStore[configuration=REDACTED]"; }
}
