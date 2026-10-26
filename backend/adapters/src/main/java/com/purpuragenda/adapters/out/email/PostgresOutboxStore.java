package com.purpuragenda.adapters.out.email;

import com.purpuragenda.application.port.out.OutboxStore;
import java.sql.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;

/** Expansion only: no scheduling resource lock, no SMTP and no content. Each event is its own short transaction. */
public final class PostgresOutboxStore implements OutboxStore {
    private final DataSource source;
    public PostgresOutboxStore(DataSource source) { this.source=Objects.requireNonNull(source); }

    @Override public Outcome expandNext(Function<String,Plan> planner) {
        Objects.requireNonNull(planner);
        try(var c=source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try(var statement=c.createStatement()) { statement.execute("SET LOCAL lock_timeout='2s'");statement.execute("SET LOCAL statement_timeout='5s'"); }
            try {
                UUID event,booking;String type;long version;
                try(var query=c.prepareStatement("SELECT id,booking_id,type,version FROM outbox_event WHERE expanded_at IS NULL ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1");
                    var result=query.executeQuery()) {
                    if(!result.next()) { c.commit();return Outcome.IDLE; }
                    event=result.getObject(1,UUID.class);booking=result.getObject(2,UUID.class);type=result.getString(3);version=result.getLong(4);
                }
                Instant now;
                try(var query=c.createStatement();var result=query.executeQuery("SELECT clock_timestamp()")) { result.next();now=result.getTimestamp(1).toInstant(); }
                Plan plan=Objects.requireNonNull(planner.apply(type));
                Outcome outcome;
                if(plan instanceof Jobs jobs) {
                    for(Spec spec:jobs.specs()) insert(c,event,booking,spec.audience(),spec.template(),version,now,"QUEUED",null);
                    outcome=jobs.specs().isEmpty()?Outcome.NO_NOTIFICATION:Outcome.EXPANDED;
                } else {
                    // Visible, terminal and cause-free: an operator sees it in the failed queue; no consumer is blocked or crashed.
                    insert(c,event,booking,"OWNER","UNKNOWN_OUTBOX_TYPE",version,now,"FAILED","UNKNOWN_OUTBOX_TYPE");
                    outcome=Outcome.UNKNOWN_TYPE;
                }
                try(var update=c.prepareStatement("UPDATE outbox_event SET expanded_at=? WHERE id=?")) {
                    update.setTimestamp(1,Timestamp.from(now));update.setObject(2,event);update.executeUpdate();
                }
                c.commit();return outcome;
            } catch(SQLException|RuntimeException failure) { try { c.rollback(); } catch(SQLException ignored) { } throw failure; }
        } catch(SQLException|RuntimeException ignored) { throw new IllegalStateException("NOTIFICATION_STORAGE_FAILED"); }
    }
    private static void insert(Connection c,UUID event,UUID booking,String audience,String template,long version,Instant now,String state,String error) throws SQLException {
        try(var query=c.prepareStatement("""
            INSERT INTO notification_job(id,event_id,booking_id,audience,template,version,due_at,state,safe_error)
            VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(event_id,audience,template,version) DO NOTHING
            """)) {
            query.setObject(1,UUID.randomUUID());query.setObject(2,event);query.setObject(3,booking);query.setString(4,audience);query.setString(5,template);
            query.setLong(6,version);query.setTimestamp(7,Timestamp.from(now));query.setString(8,state);query.setString(9,error);query.executeUpdate();
        }
    }
}
