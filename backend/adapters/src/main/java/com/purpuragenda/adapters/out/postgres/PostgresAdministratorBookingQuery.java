package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.application.port.out.AdministratorBookingQuery;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.payment.PaymentLedger;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** MVCC authority and booking facts: no mutation, session touch, scheduling lock, or network work. */
public final class PostgresAdministratorBookingQuery implements AdministratorBookingQuery {
    private final JdbcTemplate jdbc;private final TransactionTemplate reader;private final PostgresBookingStore bookings;
    public PostgresAdministratorBookingQuery(DataSource source,Set<String> meetingHosts) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(source));
        if(meetingHosts==null || meetingHosts.isEmpty())throw new IllegalArgumentException("MEETING_HOSTS_REQUIRED");
        bookings=new PostgresBookingStore(jdbc,Set.copyOf(meetingHosts));
        var manager=new DataSourceTransactionManager(source);manager.setEnforceReadOnly(true);
        reader=new TransactionTemplate(manager);reader.setReadOnly(true);reader.setTimeout(15);
        reader.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reader.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    private <T>T read(SessionBinding session,Function<Instant,T> work) {
        Objects.requireNonNull(session);
        try {
            return reader.execute(status->{
                jdbc.execute("SET LOCAL lock_timeout='2s'");jdbc.execute("SET LOCAL statement_timeout='5s'");
                Instant now=jdbc.queryForObject("SELECT clock_timestamp()",(row,n)->row.getTimestamp(1).toInstant());
                PersistedSession.administrator(jdbc,session,now);
                return work.apply(now);
            });
        } catch(Failure denial) { throw denial; }
        catch(RuntimeException unavailable) { throw new Failure(Failure.Kind.STORAGE_FAILURE); }
    }
    @Override public Page agenda(SessionBinding session,Window window,Cursor after,int limit) {
        Objects.requireNonNull(window);
        if(limit<1 || limit>100 || Duration.between(window.from(),window.until()).compareTo(Duration.ofHours(745))>0
                || after!=null && (after.start().isBefore(window.from()) || !after.start().isBefore(window.until())))
            throw new IllegalArgumentException("INVALID_AGENDA_RANGE");
        return read(session,now->{
            var parameters=new ArrayList<Object>(List.of(Timestamp.from(window.from()),Timestamp.from(window.until())));
            String cursor="";
            if(after!=null) { cursor=" AND (b.start_at,b.id)>(?,?)";parameters.add(Timestamp.from(after.start()));parameters.add(after.id()); }
            parameters.add(limit+1);
            var rows=jdbc.query("""
                SELECT b.id,b.reference,b.version,b.state,b.start_at,b.session_end,s.document->'service'->>'name'
                FROM booking b JOIN booking_snapshot s ON s.booking_id=b.id
                WHERE b.start_at>=? AND b.start_at<?
                """+cursor+" ORDER BY b.start_at,b.id LIMIT ?",(row,n)->new Entry(row.getObject(1,UUID.class),row.getString(2),row.getLong(3),Booking.State.valueOf(row.getString(4)),
                        row.getTimestamp(5).toInstant(),row.getTimestamp(6).toInstant(),row.getString(7)),parameters.toArray());
            boolean more=rows.size()>limit;List<Entry> visible=more?rows.subList(0,limit):rows;
            var last=visible.isEmpty()?null:visible.getLast();
            return new Page(visible,more?new Cursor(last.start(),last.id()):null,now);
        });
    }
    @Override public Optional<Detail> detail(SessionBinding session,UUID bookingId) {
        Objects.requireNonNull(bookingId);
        return read(session,now->bookings.booking(bookingId).map(booking->{
            var entries=jdbc.query("SELECT id,kind,amount_minor,currency,method,reference,verified_at,receipt_id FROM payment_entry WHERE booking_id=? ORDER BY verified_at,id",
                    (row,n)->new PaymentLedger.Entry(row.getObject(1,UUID.class),bookingId,PaymentLedger.Kind.valueOf(row.getString(2)),new Money(row.getLong(3),row.getString(4)),row.getString(5),row.getString(6),
                            PersistedSession.time(row,"verified_at"),row.getObject(8,UUID.class)),bookingId);
            var obligations=jdbc.query("SELECT o.receipt_id,o.due_minor,o.reason FROM refund_obligation o JOIN payment_entry p ON p.id=o.receipt_id WHERE p.booking_id=? ORDER BY o.receipt_id",
                    (row,n)->new PaymentLedger.Obligation(row.getObject(1,UUID.class),row.getLong(2),row.getString(3)),bookingId);
            var corrected=jdbc.query("SELECT document->'intake' FROM booking_amendment WHERE booking_id=? AND document->>'kind'='INTAKE_CORRECTION' ORDER BY (document->>'revision')::bigint DESC LIMIT 1",(r,n)->PersistedJson.read(r.getString(1),com.purpuragenda.domain.customer.Intake.class),bookingId);
            return new Detail(booking,new PaymentLedger(entries,obligations),now,corrected.isEmpty()?booking.snapshot().intake():corrected.getFirst());
        }));
    }
}
