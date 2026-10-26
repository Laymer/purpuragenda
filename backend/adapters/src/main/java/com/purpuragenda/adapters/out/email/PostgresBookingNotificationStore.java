package com.purpuragenda.adapters.out.email;

import com.purpuragenda.application.port.out.BookingNotificationStore;
import com.purpuragenda.application.port.out.NotificationQueue.Lease;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

/** Read-only view of the current booking for a leased job. Intake, notes and tokens are never selected. */
public final class PostgresBookingNotificationStore implements BookingNotificationStore {
    private final DataSource source;
    public PostgresBookingNotificationStore(DataSource source) { this.source=Objects.requireNonNull(source); }

    @Override public Optional<Facts> load(Lease lease) {
        Objects.requireNonNull(lease);
        try(var c=source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try(var statement=c.createStatement()) { statement.execute("SET LOCAL lock_timeout='2s'");statement.execute("SET LOCAL statement_timeout='5s'"); }
            try {
                UUID token,booking;Instant until;String audience,template;long version;
                // Lock first, then capture time: a wait on the row lock must not revive an expired lease.
                try(var query=c.prepareStatement("SELECT lease_token,lease_until,booking_id,audience,template,version FROM notification_job WHERE id=? AND state='LEASED' AND template<>'AUTH' AND booking_id IS NOT NULL FOR UPDATE")) {
                    query.setObject(1,lease.id());
                    try(var r=query.executeQuery()) {
                        if(!r.next()) { c.commit();return Optional.empty(); }
                        token=r.getObject(1,UUID.class);until=r.getTimestamp(2)==null?null:r.getTimestamp(2).toInstant();
                        booking=r.getObject(3,UUID.class);audience=r.getString(4);template=r.getString(5);version=r.getLong(6);
                    }
                }
                Instant now;
                try(var query=c.createStatement();var r=query.executeQuery("SELECT clock_timestamp()")) { r.next();now=r.getTimestamp(1).toInstant(); }
                if(token==null || until==null || !token.equals(lease.token()) || !until.isAfter(now)) { c.commit();return Optional.empty(); }
                String reference,state,deliveryEmail,meeting,service;Instant start,expires;
                try(var query=c.prepareStatement("""
                    SELECT b.reference,b.state,b.start_at,b.expires_at,b.delivery_email,b.meeting_url,s.document->'service'->>'name'
                    FROM booking b JOIN booking_snapshot s ON s.booking_id=b.id WHERE b.id=?
                    """)) {
                    query.setObject(1,booking);
                    try(var r=query.executeQuery()) {
                        if(!r.next()) { c.commit();return Optional.empty(); }
                        reference=r.getString(1);state=r.getString(2);start=r.getTimestamp(3).toInstant();expires=r.getTimestamp(4)==null?null:r.getTimestamp(4).toInstant();
                        deliveryEmail=r.getString(5);meeting=r.getString(6);service=r.getString(7);
                    }
                }
                String recipient=deliveryEmail;
                if(audience.equals("OWNER")) {
                    recipient=null;
                    try(var query=c.prepareStatement("SELECT email_key FROM admin_user WHERE enabled");var r=query.executeQuery()) { if(r.next())recipient=r.getString(1); }
                }
                Set<String> later=new HashSet<>();
                try(var query=c.prepareStatement("SELECT DISTINCT type FROM outbox_event WHERE booking_id=? AND version>?")) {
                    query.setObject(1,booking);query.setLong(2,version);
                    try(var r=query.executeQuery()) { while(r.next())later.add(r.getString(1)); }
                }
                c.commit();
                return Optional.of(new Facts(lease.id(),audience,template,version,recipient,reference,service,state,start,expires,meeting,later,now));
            } catch(SQLException|RuntimeException failure) { try { c.rollback(); } catch(SQLException ignored) { } throw failure; }
        } catch(SQLException|RuntimeException ignored) { throw new IllegalStateException("NOTIFICATION_STORAGE_FAILED"); }
    }
}
