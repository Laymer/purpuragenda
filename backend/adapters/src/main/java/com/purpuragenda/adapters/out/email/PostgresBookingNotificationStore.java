package com.purpuragenda.adapters.out.email;

import com.purpuragenda.application.port.out.BookingNotificationStore;
import com.purpuragenda.application.port.out.NotificationQueue.Lease;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

/** Current booking facts only. Intake, notes and tokens are never selected. */
public final class PostgresBookingNotificationStore implements BookingNotificationStore {
    private final NotificationJdbc db;
    public PostgresBookingNotificationStore(DataSource source) { db=new NotificationJdbc(source); }
    private record Job(UUID token,Instant until,UUID booking,String audience,String template,long version,Instant scheduled,Integer grace) {
        @Override public String toString() { return "NotificationJob[REDACTED]"; }
    }
    private record BookingFacts(String reference,String state,Instant start,Instant expires,String email,String meeting,String service) {
        @Override public String toString() { return "BookingNotificationFacts[REDACTED]"; }
    }
    private static Instant instant(ResultSet row,int column) throws SQLException {
        Timestamp value=row.getTimestamp(column);return value==null?null:value.toInstant();
    }

    @Override public Optional<Facts> load(Lease lease) {
        Objects.requireNonNull(lease);
        return db.transaction(()->{
            // Acquire the lease row lock before the one authoritative time capture.
            var jobs=db.jdbc.query("SELECT lease_token,lease_until,booking_id,audience,template,version,scheduled_at,grace_seconds FROM notification_job WHERE id=? AND state='LEASED' AND template<>'AUTH' AND booking_id IS NOT NULL FOR UPDATE",
                    (row,n)->new Job(row.getObject(1,UUID.class),instant(row,2),row.getObject(3,UUID.class),row.getString(4),row.getString(5),row.getLong(6),instant(row,7),row.getObject(8,Integer.class)),lease.id());
            if(jobs.isEmpty())return Optional.empty();
            var job=jobs.getFirst();Instant now=db.now();
            if(!lease.token().equals(job.token()) || job.until()==null || !job.until().isAfter(now))return Optional.empty();
            // Missing booking/snapshot is an integrity failure; no partial facts escape.
            var booking=db.jdbc.queryForObject("""
                SELECT b.reference,b.state,b.start_at,b.expires_at,b.delivery_email,b.meeting_url,s.document->'service'->>'name'
                FROM booking b JOIN booking_snapshot s ON s.booking_id=b.id WHERE b.id=?
                """,(row,n)->new BookingFacts(row.getString(1),row.getString(2),instant(row,3),instant(row,4),row.getString(5),row.getString(6),row.getString(7)),job.booking());
            String recipient=booking.email();
            if(job.audience().equals("OWNER"))recipient=db.jdbc.query("SELECT email_key FROM admin_user WHERE enabled",(row,n)->row.getString(1)).stream().findFirst().orElse(null);
            var later=new HashSet<>(db.jdbc.query("SELECT DISTINCT type FROM outbox_event WHERE booking_id=? AND version>?",(row,n)->row.getString(1),job.booking(),job.version()));
            return Optional.of(new Facts(lease.id(),job.audience(),job.template(),job.version(),recipient,booking.reference(),booking.service(),booking.state(),booking.start(),booking.expires(),booking.meeting(),later,now,job.scheduled(),job.grace()));
        });
    }
}
