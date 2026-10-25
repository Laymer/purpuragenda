package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.scheduling.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Uses the UOW's exact transaction-bound JdbcTemplate; never opens a separate connection. */
final class PostgresBookingStore {
    private final JdbcTemplate jdbc;
    private final Set<String> meetingHosts;
    private record StoredShape(UUID service,int minutes,Instant sessionEnd,Instant reservedEnd,LocalDate date) {}
    private record Occupancy(UUID booking,int resource,Instant start,Instant end,boolean lower,boolean upper,boolean released) {}
    private static final String BOOKINGS="""
        SELECT b.*,s.document::text AS snapshot,o.booking_id AS occupancy_id,
          o.resource_id AS occupied_resource,lower(o.occupied) AS occupied_start,upper(o.occupied) AS occupied_end,
          lower_inc(o.occupied) AS inclusive_start,upper_inc(o.occupied) AS inclusive_end,o.released_at
        FROM booking b LEFT JOIN booking_snapshot s ON s.booking_id=b.id LEFT JOIN booking_occupancy o ON o.booking_id=b.id
        """;
    PostgresBookingStore(JdbcTemplate jdbc,Set<String> meetingHosts) { this.jdbc=jdbc;this.meetingHosts=meetingHosts; }
    List<ServiceDefinition> services() {
        return List.copyOf(jdbc.query("SELECT * FROM service ORDER BY price_minor,id",(row,n)->{
            try { return new ServiceDefinition(row.getObject("id",UUID.class),row.getLong("version"),row.getString("name"),
                    row.getInt("duration_minutes"),new Money(row.getLong("price_minor"),row.getString("currency")),row.getBoolean("active")); }
            catch(RuntimeException failure) { throw invalid(); }
        }));
    }
    SchedulingPolicy policy() {
        return jdbc.queryForObject("SELECT p.version,p.document::text FROM business_settings s JOIN policy_version p ON p.version=s.policy_version WHERE s.id=1",(row,n)->{
            SchedulingPolicy value=PersistedJson.read(row.getString("document"),SchedulingPolicy.class);
            require(value.version()==row.getLong("version")); return value;
        });
    }
    AvailabilityCalendar calendar() { return PersistedJson.read(jdbc.queryForObject("SELECT calendar::text FROM business_settings WHERE id=1",String.class),AvailabilityCalendar.class); }
    Optional<Booking> booking(UUID id) { return jdbc.query(BOOKINGS+" WHERE b.id=?",(row,n)->map(row),id).stream().findFirst(); }
    List<Booking> bookings(ReservationInterval interval) {
        return List.copyOf(jdbc.query(BOOKINGS+" WHERE b.start_at<? AND b.reserved_end>? ORDER BY b.start_at,b.id",(row,n)->map(row),timestamp(interval.end()),timestamp(interval.start())));
    }
    private Booking map(ResultSet row) throws SQLException {
        try {
            String url=row.getString("meeting_url"); Meeting meeting=url==null?null:Meeting.parse(url,meetingHosts);
            require(Objects.equals(row.getString("meeting_id"),meeting==null?null:meeting.meetingId()));
            String basis=row.getString("confirmation_basis");
            Booking value=new Booking(row.getObject("id",UUID.class),row.getString("reference"),row.getObject("customer_id",UUID.class),
                    PersistedJson.read(row.getString("snapshot"),Booking.Snapshot.class),row.getString("delivery_email"),
                    Booking.State.valueOf(row.getString("state")),Booking.Origin.valueOf(row.getString("origin")),
                    instant(row,"created_at"),instant(row,"start_at"),instant(row,"expires_at"),meeting,
                    basis==null?null:Booking.ConfirmationBasis.valueOf(basis),row.getLong("version"),row.getInt("calendar_revision"),row.getBoolean("previously_confirmed"));
            value.snapshot().intake().validateAt(value.createdAt());
            require(new StoredShape(value.snapshot().service().id(),value.snapshot().service().durationMinutes(),value.sessionEnd(),value.reservedEnd(),value.businessDate())
                    .equals(new StoredShape(row.getObject("service_id",UUID.class),row.getInt("session_minutes"),instant(row,"session_end"),instant(row,"reserved_end"),row.getObject("business_date",LocalDate.class))));
            require(new Occupancy(value.id(),row.getInt("resource_id"),value.start(),value.reservedEnd(),true,false,!occupied(value))
                    .equals(new Occupancy(row.getObject("occupancy_id",UUID.class),row.getInt("occupied_resource"),instant(row,"occupied_start"),instant(row,"occupied_end"),
                            row.getBoolean("inclusive_start"),row.getBoolean("inclusive_end"),row.getTimestamp("released_at")!=null)));
            if(value.state()==Booking.State.PENDING_CONFIRMATION) require(ceiling(value).equals(instant(row,"expiry_ceiling")));
            return value;
        } catch(RuntimeException failure) { throw invalid(); }
    }
    void save(Booking value,Instant now) {
        value.snapshot().intake().validateAt(value.createdAt());
        if(value.meeting()!=null) require(Meeting.parse(value.meeting().participantUrl(),meetingHosts).meetingId().equals(value.meeting().meetingId()));
        require(value.createdAt().equals(value.createdAt().truncatedTo(ChronoUnit.MICROS))
                && value.start().equals(value.start().truncatedTo(ChronoUnit.MICROS))
                && (value.expiresAt()==null || value.expiresAt().equals(value.expiresAt().truncatedTo(ChronoUnit.MICROS))));
        Optional<Booking> previous=booking(value.id());
        if(previous.isPresent()) {
            require(previous.get().snapshot().equals(value.snapshot()));
            require(previous.get().customerId().equals(value.customerId()) && previous.get().reference().equals(value.reference())
                    && previous.get().origin()==value.origin() && previous.get().createdAt().equals(value.createdAt()));
            if(previous.get().equals(value)) return;
            require(previous.get().version()+1==value.version());
        } else require(value.version()==1 && value.createdAt().equals(now));
        jdbc.update("""
            INSERT INTO booking(id,reference,customer_id,service_id,state,origin,created_at,start_at,session_minutes,
              session_end,reserved_end,business_date,expires_at,expiry_ceiling,delivery_email,meeting_id,meeting_url,
              confirmation_basis,version,calendar_revision,previously_confirmed)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET state=excluded.state,start_at=excluded.start_at,session_end=excluded.session_end,
              reserved_end=excluded.reserved_end,business_date=excluded.business_date,expires_at=excluded.expires_at,
              expiry_ceiling=excluded.expiry_ceiling,delivery_email=excluded.delivery_email,meeting_id=excluded.meeting_id,
              meeting_url=excluded.meeting_url,confirmation_basis=excluded.confirmation_basis,version=excluded.version,
              calendar_revision=excluded.calendar_revision,previously_confirmed=excluded.previously_confirmed
            """,value.id(),value.reference(),value.customerId(),value.snapshot().service().id(),value.state().name(),value.origin().name(),
                timestamp(value.createdAt()),timestamp(value.start()),value.snapshot().service().durationMinutes(),timestamp(value.sessionEnd()),timestamp(value.reservedEnd()),
                value.businessDate(),timestamp(value.expiresAt()),timestamp(value.state()==Booking.State.PENDING_CONFIRMATION?ceiling(value):value.expiresAt()),
                value.deliveryEmail(),value.meeting()==null?null:value.meeting().meetingId(),value.meeting()==null?null:value.meeting().participantUrl(),
                value.confirmationBasis()==null?null:value.confirmationBasis().name(),value.version(),value.calendarRevision(),value.previouslyConfirmed());
        if(previous.isEmpty()) jdbc.update("INSERT INTO booking_snapshot(booking_id,document) VALUES(?,?::jsonb)",value.id(),PersistedJson.write(value.snapshot()));
        jdbc.update("""
            INSERT INTO booking_occupancy(booking_id,resource_id,occupied,released_at) VALUES(?,1,tstzrange(?,?,'[)'),?)
            ON CONFLICT(booking_id) DO UPDATE SET occupied=excluded.occupied,released_at=excluded.released_at
            """,value.id(),timestamp(value.start()),timestamp(value.reservedEnd()),occupied(value)?null:timestamp(now));
    }
    private static Instant ceiling(Booking value) {
        return Collections.min(List.of(value.createdAt().plus(value.snapshot().policy().pendingLifetime()),value.start().minus(value.snapshot().policy().expiryBuffer())));
    }
    private static boolean occupied(Booking value) { return Set.of(Booking.State.PENDING_CONFIRMATION,Booking.State.CONFIRMED,Booking.State.COMPLETED,Booking.State.NO_SHOW).contains(value.state()); }
    private static Instant instant(ResultSet row,String field) throws SQLException { Timestamp value=row.getTimestamp(field);return value==null?null:value.toInstant(); }
    private static Timestamp timestamp(Instant value) { return value==null?null:Timestamp.from(value); }
    private static void require(boolean valid) { if(!valid) throw invalid(); }
    private static Failure invalid() { return new Failure(Failure.Kind.STORAGE_FAILURE); }
}
