package com.purpuragenda.adapters;

import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.domain.booking.Booking;
import java.sql.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual deferred constraints, without forcing each intermediate booking image. */
@Timeout(120)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class DeferredBookingIntegrityTest {
    BookingPersistenceTest f;
    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void prepare() { f=new BookingPersistenceTest();f.prepare(); }
    @AfterEach void cleanup() { f.cleanup(); }
    @Test void insertedPendingThenMovedInOneAttemptCommitsOnlyFinalOccupancy() {
        AtomicReference<Booking> finalBooking=new AtomicReference<>();
        Receipt result=f.unit.execute(f.key(),tx->tx.attempt(()->{
            Booking first=f.pending(tx,tx.now().plusSeconds(259200));tx.save(first);
            Booking moved=first.reschedule(tx.now(),first.start().plusSeconds(86400),false);
            tx.save(moved);finalBooking.set(moved);return f.ok(moved);
        }));
        assertEquals(Code.OK,result.code());assertEquals(finalBooking.get(),f.load(result.entityId()));
        assertEquals(1,f.count("booking_snapshot"));assertEquals(1,f.count("booking_occupancy"));
    }
    @Test void insertedPendingThenCancelledInOneAttemptCommitsReleasedOccupancy() {
        AtomicReference<Instant> captured=new AtomicReference<>();
        Receipt result=f.unit.execute(f.key(),tx->tx.attempt(()->{
            Booking first=f.pending(tx,tx.now().plusSeconds(259200));tx.save(first);
            Booking cancelled=first.cancel(tx.now(),false);tx.save(cancelled);captured.set(tx.now());return f.ok(cancelled);
        }));
        assertEquals(Booking.State.CANCELLED,f.load(result.entityId()).state());
        assertEquals(captured.get(),f.jdbc.queryForObject("SELECT released_at FROM booking_occupancy WHERE booking_id=?",Timestamp.class,result.entityId()).toInstant());
        assertEquals(1,f.count("booking"));assertEquals(1,f.count("booking_snapshot"));
    }
    @Test void repeatedMovesValidateFinalStateAcrossExplicitConstraintFlushes() {
        Booking original=f.create();AtomicReference<Booking> last=new AtomicReference<>();
        f.unit.execute(f.key(),tx->tx.attempt(()->{
            Booking first=tx.booking(original.id()).orElseThrow().reschedule(tx.now(),original.start().plusSeconds(86400),false);tx.save(first);
            f.jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");f.jdbc.execute("SET CONSTRAINTS ALL DEFERRED");
            Booking second=first.reschedule(tx.now(),first.start().plusSeconds(86400),false);tx.save(second);
            Booking third=second.reschedule(tx.now(),second.start().plusSeconds(86400),false);tx.save(third);last.set(third);return f.ok(third);
        }));
        assertEquals(last.get(),f.load(original.id()));assertEquals(4,last.get().version());
        assertEquals(1,f.count("booking_occupancy"));
    }
    @Test void invalidFinalOccupancyRollsBackAllInsertedImagesAndReceipt() {
        CommandKey key=f.key();
        Failure failure=assertThrows(Failure.class,()->f.unit.execute(key,tx->tx.attempt(()->{
            Booking first=f.pending(tx,tx.now().plusSeconds(259200));tx.save(first);
            Booking moved=first.reschedule(tx.now(),first.start().plusSeconds(86400),false);tx.save(moved);
            f.jdbc.update("UPDATE booking_occupancy SET occupied=tstzrange(?,?,'[)') WHERE booking_id=?",
                    Timestamp.from(first.start()),Timestamp.from(first.reservedEnd()),first.id());return f.ok(moved);
        })));
        assertEquals(Failure.Kind.STORAGE_FAILURE,failure.kind());assertNull(failure.getCause());
        assertEquals(0,f.count("booking"));assertEquals(0,f.count("booking_snapshot"));assertEquals(0,f.count("booking_occupancy"));assertEquals(0,f.count("idempotency_record"));
    }
    @Test void invalidFinalPolicyImageRollsBackExistingBookingMoves() {
        Booking original=f.create();String snapshot=f.snapshotHash(original.id());
        Failure failure=assertThrows(Failure.class,()->f.unit.execute(f.key(),tx->tx.attempt(()->{
            Booking first=tx.booking(original.id()).orElseThrow().reschedule(tx.now(),original.start().plusSeconds(86400),false);tx.save(first);
            Booking second=first.reschedule(tx.now(),first.start().plusSeconds(86400),false);tx.save(second);
            f.jdbc.update("UPDATE booking SET expiry_ceiling=expiry_ceiling+interval '1 second' WHERE id=?",original.id());return f.ok(second);
        })));
        assertEquals(Failure.Kind.STORAGE_FAILURE,failure.kind());assertEquals(original,f.load(original.id()));assertEquals(snapshot,f.snapshotHash(original.id()));
    }
    void legacy() { f.fixture.newSchema();f.fixture.migrate(8); }
    void move(Connection c,UUID id,Instant start) {
        MigrationTest.execute(c,"UPDATE booking SET start_at=?,session_end=?,reserved_end=?,business_date=(?::timestamptz AT TIME ZONE 'Europe/Brussels')::date WHERE id=?",
                start,start.plusSeconds(1800),start.plusSeconds(7200),start,id);
        MigrationTest.execute(c,"UPDATE booking_occupancy SET occupied=tstzrange(?,?,'[)') WHERE booking_id=?",start,start.plusSeconds(7200),id);
    }
    @Test void upgradeRepairsReproducedLegacyFailureAndPreservesValidHistory() throws Exception {
        legacy();UUID id=f.fixture.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        try(Connection c=f.fixture.connection(MigrationTest.APP)) {
            c.setAutoCommit(false);move(c,id,MigrationTest.START.plusSeconds(86400));move(c,id,MigrationTest.START.plusSeconds(172800));
            SQLException failure=assertThrows(SQLException.class,c::commit);assertEquals("23514",failure.getSQLState());c.rollback();
        }
        f.fixture.migrate(0);f.fixture.migrate(0);
        assertEquals(11,f.fixture.scalar(MigrationTest.MIGRATOR,"SELECT count(*) FROM databasechangelog"));
        try(Connection c=f.fixture.connection(MigrationTest.APP)) {
            c.setAutoCommit(false);move(c,id,MigrationTest.START.plusSeconds(86400));move(c,id,MigrationTest.START.plusSeconds(172800));c.commit();
        }
        assertEquals(1,f.fixture.scalar(MigrationTest.APP,"SELECT count(*) FROM booking WHERE start_at='2026-11-03T10:00:00Z'"));
    }
    void corruptLegacy(String table,String sql,UUID id) throws Exception {
        try(Connection c=f.fixture.connection(MigrationTest.MIGRATOR)) {
            c.setAutoCommit(false);MigrationTest.execute(c,"ALTER TABLE "+table+" DISABLE TRIGGER USER");
            MigrationTest.execute(c,sql,id);MigrationTest.execute(c,"ALTER TABLE "+table+" ENABLE TRIGGER USER");c.commit();
        }
    }
    void rejectedUpgrade() {
        assertEquals("MIGRATION_FAILED",assertThrows(AssertionError.class,()->f.fixture.migrate(0)).getMessage());
        assertEquals(8,f.fixture.scalar(MigrationTest.MIGRATOR,"SELECT count(*) FROM databasechangelog"));
    }
    @Test void upgradeRejectsPreviouslyCorruptedFinalOccupancy() throws Exception {
        legacy();UUID id=f.fixture.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        corruptLegacy("booking_occupancy","UPDATE booking_occupancy SET released_at=clock_timestamp() WHERE booking_id=?",id);rejectedUpgrade();
    }
    @Test void upgradeRejectsPreviouslyCorruptedSnapshotWithoutCertifying009() throws Exception {
        legacy();UUID id=f.fixture.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        corruptLegacy("booking_snapshot","UPDATE booking_snapshot SET document=document-'policy' WHERE booking_id=?",id);rejectedUpgrade();
    }
}
