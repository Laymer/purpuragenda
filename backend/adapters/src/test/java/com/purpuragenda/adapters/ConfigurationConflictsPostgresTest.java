package com.purpuragenda.adapters;

import com.purpuragenda.application.scheduling.ConfigurationConflicts;
import com.purpuragenda.application.scheduling.ConfigurationConflicts.*;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.scheduling.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL configuration assessment: stored bookings, persisted calendar/policy and read-only behaviour. */
@Timeout(240)
@ResourceLock("postgres-migration-fixture")
class ConfigurationConflictsPostgresTest {
    final CustomerCreationPostgresTest c=new CustomerCreationPostgresTest();
    @TempDir Path directory;
    ConfigurationConflicts query;
    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void setup() throws Exception { c.directory=directory;c.setup();query=new ConfigurationConflicts(c.scheduling); }
    @AfterEach void cleanup() { c.cleanup(); }

    UUID book(String email,int day,int hour) {
        var receipt=c.create(c.guest(email),"k-"+email,c.request(day,hour));assertTrue(receipt.success());return receipt.entityId();
    }
    List<UUID> ids(Assessment assessment) { return assessment.conflicts().stream().map(Conflict::bookingId).toList(); }
    AvailabilityCalendar current() { return c.scheduling.read(v->v.calendar()); }

    @Test void persistedBookingsAreAssessedAgainstProposedWindowsBlackoutsAndPolicyWithoutAnyWrite() {
        UUID morning=book("one@example.test",3,9),evening=book("two@example.test",4,19);
        Map<String,Integer> before=snapshot();
        var windows=new EnumMap<DayOfWeek,List<AvailabilityCalendar.Window>>(DayOfWeek.class);
        for(var day:DayOfWeek.values())windows.put(day,List.of(new AvailabilityCalendar.Window(540,1080)));
        var shrunk=new AvailabilityCalendar(windows,Map.of(),List.of(),current().version()+1);
        var assessment=query.assess(new Proposal(shrunk,null));
        assertEquals(List.of(evening),ids(assessment));assertEquals(Set.of(Reason.START_RULES),assessment.conflicts().getFirst().reasons());
        assertEquals(shrunk.version(),assessment.calendarVersion());assertEquals(1,assessment.policyVersion());
        Booking stored=c.load(morning);UUID blackout=UUID.randomUUID();
        var closed=new AvailabilityCalendar(current().weekly(),Map.of(),List.of(new AvailabilityCalendar.Blackout(blackout,
                new ReservationInterval(stored.sessionEnd().plusSeconds(60),stored.reservedEnd()),"Synthetic closure reason")),current().version()+1);
        var blackedOut=query.assess(new Proposal(closed,null));
        assertEquals(List.of(morning),ids(blackedOut));assertEquals(Set.of(blackout),blackedOut.conflicts().getFirst().blackoutIds());
        var policy=c.scheduling.read(v->v.policy());
        var zeroCapacity=new SchedulingPolicy(2,policy.gridMinutes(),0,policy.minimumNotice(),policy.pendingLifetime(),policy.expiryBuffer(),policy.customerCutoff());
        assertEquals(Set.of(morning,evening),new HashSet<>(ids(query.assess(new Proposal(null,zeroCapacity)))));
        assertTrue(query.assess(new Proposal(current(),null)).conflicts().isEmpty());
        assertEquals(before,snapshot());
    }

    @Test void nonOccupyingBookingsAreIgnoredAndTheAssessmentUsesDatabaseTime() {
        UUID kept=book("one@example.test",3,9),cancelled=book("two@example.test",4,9);
        var session=c.guest("two@example.test",cancelled);
        assertTrue(new com.purpuragenda.application.scheduling.CustomerBookingChanges(new com.purpuragenda.adapters.out.postgres.PostgresBookingChanges(c.f.source))
                .cancel(c.binding(session),cancelled,"cancel",new com.purpuragenda.application.scheduling.CustomerBookingChanges.Cancellation(1)).success());
        var policy=c.scheduling.read(v->v.policy());
        var zeroCapacity=new SchedulingPolicy(2,policy.gridMinutes(),0,policy.minimumNotice(),policy.pendingLifetime(),policy.expiryBuffer(),policy.customerCutoff());
        Instant before=c.now();var assessment=query.assess(new Proposal(null,zeroCapacity));Instant after=c.now();
        assertEquals(List.of(kept),ids(assessment));assertFalse(assessment.assessedAt().isBefore(before));assertFalse(assessment.assessedAt().isAfter(after));
    }

    Map<String,Integer> snapshot() {
        Map<String,Integer> counts=new TreeMap<>();
        for(String table:List.of("booking","booking_occupancy","audit_event","outbox_event","idempotency_record","customer_identity","request_charge"))counts.put(table,c.count(table));
        counts.put("calendar",c.f.value("SELECT calendar::text FROM business_settings").hashCode());return counts;
    }
}
