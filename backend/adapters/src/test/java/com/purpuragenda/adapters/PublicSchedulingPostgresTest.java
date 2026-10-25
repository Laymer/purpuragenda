package com.purpuragenda.adapters;

import com.purpuragenda.application.scheduling.PublicScheduling;
import com.purpuragenda.domain.scheduling.SchedulingPolicy;
import java.time.*;
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
}
