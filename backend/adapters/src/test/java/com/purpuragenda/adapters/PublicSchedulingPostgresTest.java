package com.purpuragenda.adapters;

import com.purpuragenda.application.scheduling.PublicScheduling;
import com.purpuragenda.domain.scheduling.SchedulingPolicy;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class PublicSchedulingPostgresTest {
    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @Test void databaseTimeCatalogAndOccupancyDriveAnonymousAvailability() {
        BookingPersistenceTest fixture=new BookingPersistenceTest();fixture.prepare();
        try {
            var booking=fixture.create();
            var query=new PublicScheduling(fixture.unit);
            assertEquals(2,query.services().size());
            var day=booking.start().atZone(SchedulingPolicy.BUSINESS_ZONE).toLocalDate();
            var before=Instant.now();
            var result=query.availability(booking.snapshot().service().id(),day,day,"Europe/Brussels");
            assertFalse(result.serverNow().isBefore(before)); assertFalse(result.serverNow().isAfter(Instant.now()));
            assertFalse(result.slots().isEmpty());
            assertTrue(result.slots().stream().noneMatch(slot->booking.interval().intersects(
                    com.purpuragenda.domain.scheduling.ReservationInterval.reserve(slot.start()))));
            assertEquals(1,fixture.count("booking"));assertEquals(1,fixture.count("idempotency_record"));
        } finally {fixture.cleanup();}
    }
    @Test void publicReadKeepsOneSnapshotAcrossConcurrentAtomicServiceAndPolicyChange() throws Exception {
        BookingPersistenceTest fixture=new BookingPersistenceTest();fixture.prepare();
        try(var writer=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var versions=fixture.unit.read(view->{
                var service=view.services().getFirst();var now=view.now();
                try {
                    writer.submit(()->fixture.unit.execute(fixture.key(),tx->{
                        fixture.jdbc.update("INSERT INTO policy_version(version,document) SELECT 2,jsonb_set(document,'{version}','2') FROM policy_version WHERE version=1");
                        fixture.jdbc.update("UPDATE business_settings SET policy_version=2 WHERE id=1");
                        fixture.jdbc.update("UPDATE service SET version=2,price_minor=price_minor+1 WHERE id=?",service.id());
                        return new com.purpuragenda.application.port.out.SchedulingUnitOfWork.Receipt(
                                com.purpuragenda.application.port.out.SchedulingUnitOfWork.Code.OK,null,0);
                    })).get(10,java.util.concurrent.TimeUnit.SECONDS);
                } catch(Exception failure) { throw new AssertionError("CONCURRENT_CONFIGURATION_DID_NOT_COMMIT"); }
                assertEquals(now,view.now());
                return java.util.List.of(service.version(),view.policy().version(),view.services().getFirst().version());
            });
            assertEquals(java.util.List.of(1L,1L,1L),versions);
            assertEquals(2L,fixture.unit.<Long>read(view->view.policy().version()).longValue());
            assertEquals(2L,fixture.unit.<Long>read(view->view.services().getFirst().version()).longValue());
            assertEquals(0,fixture.count("booking"));assertEquals(1,fixture.count("idempotency_record"));
        } finally { fixture.cleanup(); }
    }
    @Test void lateWindowIncludesNextDateOccupancyAndAllowsExactTwoHourAdjacency() {
        BookingPersistenceTest fixture=new BookingPersistenceTest();fixture.prepare();
        try {
            AtomicReference<com.purpuragenda.domain.booking.Booking> blocker=new AtomicReference<>();
            AtomicReference<LocalDate> date=new AtomicReference<>();
            fixture.unit.execute(fixture.key(),tx->{
                LocalDate day=tx.now().atZone(SchedulingPolicy.BUSINESS_ZONE).toLocalDate().plusDays(2);date.set(day);
                fixture.jdbc.update("""
                    UPDATE business_settings SET calendar=jsonb_build_object(
                      'weekly',jsonb_build_object(?::text,jsonb_build_array(jsonb_build_object('startMinute',1380,'endMinute',1440))),
                      'exceptions','{}'::jsonb,'blackouts','[]'::jsonb,'version',1)
                    """,day.getDayOfWeek().name());
                var value=fixture.pending(tx,day.plusDays(1).atStartOfDay(SchedulingPolicy.BUSINESS_ZONE).toInstant());
                tx.save(value);blocker.set(value);return fixture.ok(value);
            });
            var query=new PublicScheduling(fixture.unit);var original=blocker.get();
            assertTrue(query.availability(original.snapshot().service().id(),date.get(),date.get(),"Europe/Brussels").slots().isEmpty());
            fixture.unit.execute(fixture.key(),tx->{
                var moved=tx.booking(original.id()).orElseThrow().reschedule(tx.now(),original.start().plusSeconds(5400),false);
                tx.save(moved);return fixture.ok(moved);
            });
            var available=query.availability(original.snapshot().service().id(),date.get(),date.get(),"Europe/Brussels");
            assertEquals(2,available.slots().size());
            assertEquals(fixture.load(original.id()).start(),available.slots().getLast().reservationEnd());
        } finally {fixture.cleanup();}
    }
}
