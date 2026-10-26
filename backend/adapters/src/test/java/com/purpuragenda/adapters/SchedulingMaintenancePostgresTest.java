package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.postgres.PostgresScheduling;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import com.purpuragenda.application.scheduling.*;
import com.purpuragenda.domain.booking.Booking;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(180)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class SchedulingMaintenancePostgresTest {
    ExpiryReclamationPostgresTest e;
    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void setup() { e=new ExpiryReclamationPostgresTest();e.prepare(); }
    @AfterEach void cleanup() { e.cleanup(); }
    DueBookingExpiry worker() { return new DueBookingExpiry(e.f.unit); }
    @Test void periodicExpiryCommitsRefundAndOutboxOnceWithoutArtificialReceiptsOrQuotaCharges() {
        var booking=e.due();e.f.unit.execute(e.f.key(),tx->{e.receipt(booking,100,"EUR");return e.success();});
        int receipts=e.f.count("idempotency_record");
        assertEquals(1,worker().tick());assertEquals(0,worker().tick());
        assertEquals(Booking.State.EXPIRED,e.f.load(booking.id()).state());
        assertEquals(1,e.f.count("refund_obligation"));assertEquals(1,e.f.count("audit_event"));assertEquals(1,e.f.count("outbox_event"));
        assertEquals(1,e.f.count("payment_entry"));assertEquals(0,e.f.count("request_charge"));
        assertEquals(receipts,e.f.count("idempotency_record"));
        assertEquals(0,e.f.jdbc.queryForObject("SELECT count(*) FROM booking_occupancy WHERE released_at IS NULL",Integer.class));
    }
    @Test void unexpectedFailureRollsBackExpiryOccupancyAndEveryEffectTogether() {
        var booking=e.due();int receipts=e.f.count("idempotency_record");
        assertThrows(IllegalStateException.class,()->e.f.unit.maintain(tx->{
            assertEquals(1,ExpiryReclamation.reclaim(tx));throw new IllegalStateException("SYNTHETIC_ABORT");
        }));
        assertEquals(Booking.State.PENDING_CONFIRMATION,e.f.load(booking.id()).state());
        assertEquals(1,e.f.jdbc.queryForObject("SELECT count(*) FROM booking_occupancy WHERE released_at IS NULL",Integer.class));
        assertEquals(0,e.f.count("outbox_event"));assertEquals(0,e.f.count("audit_event"));assertEquals(receipts,e.f.count("idempotency_record"));
        assertEquals(1,worker().tick());
    }
    @Test void twentyWorkersAcrossTwoAdaptersCommitExactlyOneExpiry() throws Exception {
        var booking=e.due();var other=new DueBookingExpiry(new PostgresScheduling(e.f.source));
        int receipts=e.f.count("idempotency_record");var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(20)) {
            List<Future<Integer>> results=new ArrayList<>();
            for(int i=0;i<20;i++) { var selected=i%2==0?worker():other;results.add(pool.submit(()->{start.await();return selected.tick();})); }
            start.countDown();int changed=0;for(var future:results)changed+=future.get(20,TimeUnit.SECONDS);
            assertEquals(1,changed);
        }
        assertEquals(2,e.f.load(booking.id()).version());assertEquals(1,e.f.count("outbox_event"));
        assertEquals(1,e.f.count("audit_event"));assertEquals(receipts,e.f.count("idempotency_record"));
    }
    @Test void maintenanceControlAndMissingConfigurationFailClosedBeforeCallback() {
        e.f.jdbc.update("UPDATE operation_control SET maintenance=true WHERE id=1");
        assertEquals(Failure.Kind.MAINTENANCE,assertThrows(Failure.class,()->e.f.unit.maintain(tx->{fail("MAINTENANCE_CALLBACK_RAN");return 0;})).kind());
        e.f.jdbc.update("DELETE FROM operation_control WHERE id=1");
        assertEquals(Failure.Kind.STORAGE_FAILURE,assertThrows(Failure.class,()->worker().tick()).kind());
        assertThrows(NullPointerException.class,()->e.f.unit.maintain(null));
    }
    @Test void authoritativeTimeIsCapturedAfterWaitingForTheSameResourceLock() throws Exception {
        var observed=new AtomicReference<Instant>();
        try(var blocker=e.f.source.getConnection();var pool=Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);try(var statement=blocker.createStatement()) { statement.execute("SELECT id FROM scheduling_resource WHERE id=1 FOR UPDATE"); }
            var future=pool.submit(()->e.f.unit.maintain(tx->{observed.set(tx.now());assertEquals(observed.get(),tx.now());return 0;}));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(e.f.jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%scheduling_resource%'",Integer.class)==0) {
                if(System.nanoTime()>deadline)fail("RESOURCE_LOCK_WAIT_NOT_OBSERVED");Thread.sleep(10);
            }
            assertNull(observed.get());Instant release=e.f.jdbc.queryForObject("SELECT clock_timestamp()",(r,n)->r.getTimestamp(1).toInstant());
            blocker.commit();assertEquals(0,future.get(10,TimeUnit.SECONDS));assertFalse(observed.get().isBefore(release));
        }
    }
    @Test void maintenanceCommitsIndependentlyOfAnUnrelatedOuterRollback() {
        var booking=e.due();var outer=new TransactionTemplate(new DataSourceTransactionManager(e.f.source));
        outer.execute(status->{assertEquals(1,worker().tick());status.setRollbackOnly();return null;});
        assertEquals(Booking.State.EXPIRED,e.f.load(booking.id()).state());assertEquals(1,e.f.count("outbox_event"));
    }
}
