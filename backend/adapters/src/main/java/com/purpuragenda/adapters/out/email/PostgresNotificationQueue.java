package com.purpuragenda.adapters.out.email;

import com.purpuragenda.application.port.out.NotificationQueue;
import java.sql.*;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** No SMTP or scheduling-resource lock is held by this queue adapter. */
public final class PostgresNotificationQueue implements NotificationQueue {
    private static final int[] RETRIES = {60, 300, 1800, 7200, 21600, 43200};
    private final DataSource source;
    private final boolean suppressed;

    public PostgresNotificationQueue(DataSource source, boolean suppressed) {
        this.source = Objects.requireNonNull(source); this.suppressed = suppressed;
    }

    @Override public Optional<Lease> claim() {
        if (suppressed) return Optional.empty();
        return transaction(c -> {
            try (var query = c.prepareStatement("SELECT delivery_suppressed FROM operation_control WHERE id=1 FOR SHARE");
                 var result = query.executeQuery()) {
                if (!result.next()) throw new SQLException();
                if (result.getBoolean(1)) return Optional.empty();
            }
            Instant now = now(c);
            update(c, """
                UPDATE notification_job SET state='QUEUED',lease_until=NULL,lease_token=NULL
                WHERE id IN (SELECT id FROM notification_job WHERE state='LEASED'
                  AND (lease_until<=? OR lease_until IS NULL OR lease_token IS NULL)
                  ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 100)
                """, now);
            UUID id; String group;
            try (var query = c.prepareStatement("""
                SELECT j.id,COALESCE(j.booking_id,j.challenge_id,j.id)::text||':'||j.audience
                FROM notification_job j WHERE j.state='QUEUED' AND j.due_at<=?
                  AND NOT EXISTS (SELECT 1 FROM notification_job l WHERE l.state='LEASED'
                    AND COALESCE(l.booking_id,l.challenge_id,l.id)=COALESCE(j.booking_id,j.challenge_id,j.id)
                    AND l.audience=j.audience)
                ORDER BY j.due_at,j.id FOR UPDATE OF j SKIP LOCKED LIMIT 1
                """)) {
                query.setTimestamp(1, Timestamp.from(now));
                try (var result = query.executeQuery()) {
                    if (!result.next()) return Optional.empty();
                    id = result.getObject(1, UUID.class); group = result.getString(2);
                }
            }
            try (var query = c.prepareStatement("SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))")) {
                query.setString(1, group);
                try (var result = query.executeQuery()) { result.next(); if (!result.getBoolean(1)) return Optional.empty(); }
            }
            // A different claim may have committed after the SKIP LOCKED candidate query.
            try (var query = c.prepareStatement("""
                SELECT 1 FROM notification_job l WHERE l.state='LEASED'
                AND COALESCE(l.booking_id,l.challenge_id,l.id)::text||':'||l.audience=?
                """)) {
                query.setString(1, group);
                try (var result = query.executeQuery()) { if (result.next()) return Optional.empty(); }
            }
            UUID token = UUID.randomUUID(); Instant until = now.plusSeconds(60);
            update(c, "UPDATE notification_job SET state='LEASED',lease_until=?,lease_token=?,attempts=attempts+1 WHERE id=?",
                    until, token, id);
            return Optional.of(new Lease(id, token, until));
        });
    }

    @Override public boolean renew(Lease lease) { return change(lease, "RENEW"); }
    @Override public boolean sent(Lease lease) { return change(lease, "SENT"); }
    @Override public boolean failed(Lease lease) { return change(lease, "FAILED"); }

    private boolean change(Lease lease, String operation) {
        Objects.requireNonNull(lease);
        return transaction(c -> {
            UUID token; Instant until; int attempts;
            // Lock first: time captured before a queued row-lock wait could revive an expired lease.
            try (var query = c.prepareStatement("SELECT lease_token,lease_until,attempts FROM notification_job WHERE id=? AND state='LEASED' FOR UPDATE")) {
                query.setObject(1, lease.id());
                try (var result = query.executeQuery()) {
                    if (!result.next()) return false;
                    token = result.getObject(1, UUID.class);
                    until = result.getTimestamp(2) == null ? null : result.getTimestamp(2).toInstant();
                    attempts = result.getInt(3);
                }
            }
            Instant now = now(c);
            if (token == null || until == null || !token.equals(lease.token()) || !new Lease(lease.id(), token, until).liveAt(now)) return false;
            if (operation.equals("RENEW")) {
                if (suppressed) return false;
                try (var query = c.prepareStatement("SELECT delivery_suppressed FROM operation_control WHERE id=1"); var result = query.executeQuery()) {
                    if (!result.next() || result.getBoolean(1)) return false;
                }
                update(c, "UPDATE notification_job SET lease_until=? WHERE id=?", now.plusSeconds(60), lease.id());
                return true;
            }
            boolean success = operation.equals("SENT");
            String state = success ? "SENT" : attempts > RETRIES.length ? "FAILED" : "QUEUED";
            Instant due = now.plusSeconds(success || attempts > RETRIES.length ? 0 : RETRIES[attempts - 1]);
            update(c, """
                UPDATE notification_job SET state=?,due_at=CASE WHEN ?='SENT' THEN due_at ELSE ? END,lease_until=NULL,lease_token=NULL,
                  encrypted_envelope=CASE WHEN ?='SENT' THEN NULL ELSE encrypted_envelope END,safe_error=? WHERE id=?
                """, state, state, due, state, success ? null : "SMTP_DELIVERY_FAILED", lease.id());
            update(c, "INSERT INTO delivery_attempt(id,job_id,attempted_at,success,safe_code,lease_token) VALUES(?,?,?,?,?,?)",
                    UUID.randomUUID(), lease.id(), now, success, success ? null : "SMTP_DELIVERY_FAILED", lease.token());
            return true;
        });
    }

    private static Instant now(Connection c) throws SQLException {
        try (var query = c.createStatement(); var result = query.executeQuery("SELECT clock_timestamp()")) {
            result.next(); return result.getTimestamp(1).toInstant();
        }
    }
    private static void update(Connection c, String sql, Object... values) throws SQLException {
        try (var query = c.prepareStatement(sql)) {
            for (int i=0; i<values.length; i++) query.setObject(i+1, values[i] instanceof Instant instant ? Timestamp.from(instant) : values[i]);
            query.executeUpdate();
        }
    }
    @FunctionalInterface private interface Work<T> { T run(Connection connection) throws SQLException; }
    private <T> T transaction(Work<T> work) {
        try (var c = source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try (var statement = c.createStatement()) {
                statement.execute("SET LOCAL lock_timeout='2s'"); statement.execute("SET LOCAL statement_timeout='5s'");
            }
            try { T result = work.run(c); c.commit(); return result; }
            catch (SQLException | RuntimeException error) { try { c.rollback(); } catch (SQLException ignored) { } throw error; }
        } catch (SQLException | RuntimeException ignored) { throw new IllegalStateException("NOTIFICATION_STORAGE_FAILED"); }
    }
}
