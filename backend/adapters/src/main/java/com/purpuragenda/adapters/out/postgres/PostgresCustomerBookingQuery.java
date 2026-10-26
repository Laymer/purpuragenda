package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.application.port.out.CustomerBookingQuery;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import com.purpuragenda.domain.booking.Money;
import com.purpuragenda.domain.payment.PaymentLedger;
import java.sql.Timestamp;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** MVCC reads never lock or mutate authority. A revocation committed before this snapshot always denies access. */
public final class PostgresCustomerBookingQuery implements CustomerBookingQuery {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate reader;
    private final PostgresBookingStore bookings;
    public PostgresCustomerBookingQuery(DataSource source,Set<String> meetingHosts) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(source));
        if(meetingHosts==null || meetingHosts.isEmpty())throw new IllegalArgumentException("MEETING_HOSTS_REQUIRED");
        bookings=new PostgresBookingStore(jdbc,Set.copyOf(meetingHosts));
        var manager=new DataSourceTransactionManager(source);manager.setEnforceReadOnly(true);
        reader=new TransactionTemplate(manager);reader.setReadOnly(true);reader.setTimeout(15);
        reader.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reader.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Override public Optional<Authorized> find(SessionBinding session,UUID bookingId) {
        Objects.requireNonNull(session);Objects.requireNonNull(bookingId);
        try {
            return reader.execute(status->{
                var now=jdbc.queryForObject("SELECT clock_timestamp()",(r,n)->r.getTimestamp(1).toInstant());
                boolean permitted=Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (
                      SELECT 1 FROM app_session s JOIN booking b ON b.customer_id=s.customer_id
                      WHERE s.id=? AND s.browser_hash=? AND b.id=? AND s.revoked_at IS NULL
                        AND s.issued_at<=? AND s.last_seen_at<=? AND s.absolute_expires_at>? AND s.last_seen_at+interval '30 minutes'>?
                        AND (
                          (s.kind='ACCOUNT' AND EXISTS (
                            SELECT 1 FROM customer_account a WHERE a.id=s.account_id AND a.customer_id=s.customer_id
                              AND a.credential_version=s.account_credential_version))
                          OR (s.kind='GUEST' AND EXISTS (
                            SELECT 1 FROM access_grant g WHERE g.id=s.grant_id AND g.customer_id=s.customer_id
                              AND g.browser_hash=s.browser_hash AND g.booking_id=b.id AND g.revoked_at IS NULL AND g.issued_at<=?
                              AND ((g.kind='MANAGE' AND g.expires_at>? AND g.consumed_at IS NULL
                                AND (g.parent_grant_id IS NULL OR EXISTS (
                                  SELECT 1 FROM access_grant p WHERE p.id=g.parent_grant_id AND p.kind='CREATE' AND p.revoked_at IS NULL)))
                                OR (g.kind='CREATE' AND g.consumed_at IS NOT NULL AND EXISTS (
                                  SELECT 1 FROM access_grant c WHERE c.parent_grant_id=g.id AND c.kind='MANAGE'
                                    AND c.booking_id=b.id AND c.customer_id=s.customer_id AND c.browser_hash=s.browser_hash
                                    AND c.revoked_at IS NULL AND c.issued_at<=? AND c.expires_at>?)))
                          ))
                        )
                    )
                    """,Boolean.class,session.sessionId(),session.browserHash(),bookingId,
                        Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),
                        Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now)));
                if(!permitted)return Optional.empty();
                var booking=bookings.booking(bookingId).orElseThrow();
                var entries=jdbc.query("SELECT id,kind,amount_minor,currency,method,reference,verified_at,receipt_id FROM payment_entry WHERE booking_id=? ORDER BY verified_at,id",
                        (r,n)->new PaymentLedger.Entry(r.getObject(1,UUID.class),bookingId,PaymentLedger.Kind.valueOf(r.getString(2)),
                                new Money(r.getLong(3),r.getString(4)),r.getString(5),r.getString(6),
                                PersistedSession.time(r,"verified_at"),r.getObject(8,UUID.class)),bookingId);
                var obligations=jdbc.query("SELECT o.receipt_id,o.due_minor,o.reason FROM refund_obligation o JOIN payment_entry p ON p.id=o.receipt_id WHERE p.booking_id=? ORDER BY o.receipt_id",
                        (r,n)->new PaymentLedger.Obligation(r.getObject(1,UUID.class),r.getLong(2),r.getString(3)),bookingId);
                return Optional.of(new Authorized(booking,new PaymentLedger(entries,obligations),now));
            });
        } catch(RuntimeException unavailable) { throw new Failure(Failure.Kind.STORAGE_FAILURE); }
    }
}
