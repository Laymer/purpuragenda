package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.adapters.out.security.Secrets;
import com.purpuragenda.application.port.out.AdminChangesUnitOfWork.Classification;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.AdministratorBookingChanges;
import com.purpuragenda.application.scheduling.AdministratorBookingChanges.*;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.scheduling.SchedulingRules;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL administrator changes: locked authority, transitions, refund follow-up, audit/outbox and replay. */
@Timeout(240)
@ResourceLock("postgres-migration-fixture")
class AdminChangesPostgresTest {
    static final String REASON="Synthetic administrative reason";
    final AdminBookingPostgresTest b=new AdminBookingPostgresTest();
    @TempDir Path directory;
    CustomerCreationPostgresTest c;AdministratorBookingChanges changes;
    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void setup() throws Exception { b.directory=directory;b.setup();c=b.c;changes=new AdministratorBookingChanges(new PostgresAdminChanges(c.f.source)); }
    @AfterEach void cleanup() { b.cleanup(); }

    UUID create(int days,int hour,String meeting) {
        var receipt=b.create("create-"+days+"-"+hour,b.request(b.day(days,hour,0),"customer@example.test",meeting,SchedulingRules.Override.none()));
        assertTrue(receipt.success());return receipt.entityId();
    }
    UUID paid(UUID booking,long amount) {
        UUID id=UUID.randomUUID();
        c.jdbc.update("INSERT INTO payment_entry(id,booking_id,kind,amount_minor,currency,method,reference,verified_at) VALUES(?,?,'RECEIPT',?,'EUR','SYNTHETIC',?,clock_timestamp())",id,booking,amount,"ref-"+id);
        return id;
    }
    String state(UUID id) { return c.jdbc.queryForObject("SELECT state FROM booking WHERE id=?",String.class,id); }
    int count(String sql) { return c.count(sql); }
    /** An administrator session live at a fixed captured time, for changes evaluated at that time. */
    SessionBinding sessionAt(Instant at) {
        UUID sid=UUID.randomUUID();String browser="d".repeat(64);
        c.jdbc.update("""
            INSERT INTO app_session(id,token_hash,browser_hash,csrf_hash,kind,admin_id,admin_security_version,issued_at,last_seen_at,absolute_expires_at,authenticated_at)
            VALUES(?,?,?,?,'ADMIN',?,0,?,?,?,?)
            """,sid,Secrets.hash("synthetic-admin-token"+sid),browser,Secrets.hash("synthetic-admin-csrf"),b.adminId,
                Timestamp.from(at.minusSeconds(60)),Timestamp.from(at.minusSeconds(60)),Timestamp.from(at.plus(Duration.ofHours(7))),Timestamp.from(at.minusSeconds(60)));
        return new SessionBinding(sid,browser);
    }
    AdministratorBookingChanges at(Instant time) { return new AdministratorBookingChanges(new PostgresAdminChanges(c.clock(time,new AtomicInteger()))); }

    @Test void rejectionReleasesOccupancyAndRequestsRefundFollowUpExactlyOnce() {
        UUID id=create(3,10,null);UUID receipt=paid(id,1500);
        assertEquals(Receipt.failure(Code.STALE_VERSION),changes.reject(b.session,id,"stale",new Rejection(2,REASON)));
        var rejected=changes.reject(b.session,id,"reject",new Rejection(1,REASON));assertEquals(new Receipt(Code.OK,id,2),rejected);
        assertEquals(rejected,changes.reject(b.session,id,"reject",new Rejection(1,REASON)));
        assertEquals("REJECTED",state(id));assertEquals(1,count("booking_occupancy WHERE released_at IS NOT NULL"));
        assertEquals("REJECTED",c.jdbc.queryForObject("SELECT reason FROM refund_obligation WHERE receipt_id=?",String.class,receipt));
        assertEquals(1,count("outbox_event WHERE type='REJECTED'"));assertEquals(1,count("audit_event WHERE type='REJECTED' AND actor_kind='ADMIN' AND reason='"+REASON+"'"));
        assertEquals(Receipt.failure(Code.IDEMPOTENCY_KEY_REUSED),changes.reject(b.session,id,"reject",new Rejection(1,"Another synthetic reason")));
        assertEquals(Receipt.failure(Code.INVALID_TRANSITION),changes.cancel(b.session,id,"cancel",new Cancellation(2,Classification.PROVIDER,REASON)));
        assertEquals(1,count("payment_entry"));
    }

    @Test void providerAndLateCustomerCancellationsDifferOnlyInRefundFollowUp() {
        UUID provider=create(3,10,"https://zoom.us/j/123456789");UUID providerReceipt=paid(provider,1500);
        assertTrue(changes.cancel(b.session,provider,"provider",new Cancellation(1,Classification.PROVIDER,REASON)).success());
        assertEquals("PROVIDER_CANCELLED",c.jdbc.queryForObject("SELECT reason FROM refund_obligation WHERE receipt_id=?",String.class,providerReceipt));
        UUID late=create(4,10,"https://zoom.us/j/123456780");paid(late,1500);
        Instant start=c.jdbc.queryForObject("SELECT start_at FROM booking WHERE id=?",Timestamp.class,late).toInstant(),near=start.minus(Duration.ofHours(2));
        assertTrue(at(near).cancel(sessionAt(near),late,"late",new Cancellation(1,Classification.CUSTOMER,REASON)).success());
        assertEquals("CANCELLED",state(late));assertEquals(1,count("refund_obligation"));
        assertEquals(1,count("audit_event WHERE type='CANCELLED' AND metadata->>'classification'='LATE_CUSTOMER'"));assertEquals(2,count("outbox_event WHERE type='CANCELLED'"));
    }

    @Test void rescheduleKeepsMoneyRecordsUsedOverridesAndNeverOverlaps() {
        UUID id=create(3,10,null),other=create(5,10,null);long calendar=b.calendarVersion();paid(id,1500);
        Instant early=b.day(3,7,0);
        var moved=changes.reschedule(b.session,id,"early",new Reschedule(1,1,calendar,early,REASON,new SchedulingRules.Override(Set.of(SchedulingRules.Rule.START_RULES),REASON,Set.of())));
        assertEquals(new Receipt(Code.OK,id,2),moved);assertEquals(early,c.jdbc.queryForObject("SELECT start_at FROM booking WHERE id=?",Timestamp.class,id).toInstant());
        assertEquals(1,count("booking_override WHERE booking_version=2"));assertEquals(1,count("outbox_event WHERE type='RESCHEDULED'"));assertEquals(1,count("payment_entry"));
        assertEquals(Receipt.failure(Code.SLOT_UNAVAILABLE),changes.reschedule(b.session,id,"overlap",
                new Reschedule(2,1,calendar,b.day(5,11,0),REASON,SchedulingRules.Override.none())));
        assertEquals("PENDING_CONFIRMATION",state(other));assertEquals(0,count("refund_obligation"));
        assertEquals(Failure.Kind.NOT_FOUND,c.failure(()->changes.reschedule(b.session,UUID.randomUUID(),"missing",new Reschedule(1,1,calendar,b.day(6,10,0),REASON,SchedulingRules.Override.none()))).kind());
    }

    @Test void outcomesAreAuditedWithoutCustomerNotificationAndStaleFactorsAreRefused() {
        UUID id=create(3,10,"https://zoom.us/j/123456789");
        Instant start=c.jdbc.queryForObject("SELECT start_at FROM booking WHERE id=?",Timestamp.class,id).toInstant(),after=start.plus(Duration.ofHours(3));
        int outbox=count("outbox_event");
        assertTrue(at(after).outcome(sessionAt(after),id,"completed",new Outcome(1,Booking.State.COMPLETED,null)).success());
        assertEquals("COMPLETED",state(id));assertEquals(outbox,count("outbox_event"));assertEquals(1,count("audit_event WHERE type='COMPLETED'"));
        assertEquals(0,count("booking_occupancy WHERE released_at IS NOT NULL"));
        UUID other=create(4,10,null);
        c.jdbc.update("UPDATE app_session SET authenticated_at=authenticated_at-interval '601 seconds',issued_at=issued_at-interval '601 seconds',absolute_expires_at=absolute_expires_at-interval '601 seconds' WHERE id=?",b.session.sessionId());
        assertEquals(Failure.Kind.FRESH_AUTHENTICATION_REQUIRED,c.failure(()->changes.reject(b.session,other,"stale",new Rejection(1,REASON))).kind());
        c.jdbc.update("UPDATE admin_user SET security_version=security_version+1");
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,c.failure(()->changes.reject(b.session,other,"revoked",new Rejection(1,REASON))).kind());
        assertEquals("PENDING_CONFIRMATION",state(other));
    }
}
