package com.purpuragenda.application;

import com.purpuragenda.application.scheduling.PublicScheduling;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.customer.Intake;
import com.purpuragenda.domain.scheduling.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PublicSchedulingTest {
    final Fixture f=new Fixture();
    final PublicScheduling query=new PublicScheduling(f);
    @Test void catalogExcludesArchivedServicesAndIsImmutable() {
        f.services=List.of(f.service,new ServiceDefinition(UUID.randomUUID(),1,"Archived",30,new Money(1,"EUR"),false));
        assertEquals(List.of(f.service),query.services());
        assertThrows(UnsupportedOperationException.class,()->query.services().clear());
        assertThrows(NullPointerException.class,()->new PublicScheduling(null));
    }
    @Test void resultsCarryCapturedTimePolicyAndBothDurations() {
        var result=query.availability(f.service.id(),LocalDate.parse("2026-01-02"),LocalDate.parse("2026-01-02"),"Europe/Brussels");
        assertEquals(f.now,result.serverNow()); assertEquals("Europe/Brussels",result.businessZone());
        assertEquals("Europe/Brussels",result.displayZone()); assertEquals(f.policy.horizon(f.now),result.horizon());
        assertEquals(f.policy.version(),result.policyVersion()); assertTrue(result.restrictions().isEmpty());
        var first=result.slots().getFirst(); assertEquals(Instant.parse("2026-01-02T08:00:00Z"),first.start());
        assertEquals(first.start().plusSeconds(3600),first.sessionEnd());
        assertEquals(first.start().plusSeconds(7200),first.reservationEnd());
        assertEquals("2026-01-02T09:00",first.displayLocal()); assertEquals("+01:00",first.displayOffset());
        assertThrows(UnsupportedOperationException.class,()->result.slots().clear());
        assertThrows(UnsupportedOperationException.class,()->result.restrictions().add("x"));
        assertEquals(1,f.reads);
    }
    @Test void invalidInputsAndInactiveServicesFailWithFixedCodes() {
        LocalDate day=LocalDate.parse("2026-01-02");
        assertCode("INVALID_ZONE",()->query.availability(f.service.id(),day,day,"bogus"));
        assertCode("SERVICE_UNAVAILABLE",()->query.availability(null,day,day,"UTC"));
        assertCode("INVALID_DATE_RANGE",()->query.availability(f.service.id(),null,day,"UTC"));
        assertCode("INVALID_DATE_RANGE",()->query.availability(f.service.id(),day,null,"UTC"));
        assertCode("INVALID_DATE_RANGE",()->query.availability(f.service.id(),day,day.minusDays(1),"UTC"));
        assertCode("INVALID_DATE_RANGE",()->query.availability(f.service.id(),day,day.plusDays(31),"UTC"));
        assertCode("INVALID_DATE_RANGE",()->query.availability(f.service.id(),LocalDate.MAX,LocalDate.MAX,"UTC"));
        assertCode("SERVICE_UNAVAILABLE",()->query.availability(UUID.randomUUID(),day,day,"UTC"));
        f.services=List.of(new ServiceDefinition(f.service.id(),1,"Archived",30,new Money(1,"EUR"),false));
        assertCode("SERVICE_UNAVAILABLE",()->query.availability(f.service.id(),day,day,"UTC"));
    }
    @Test void boundedWindowsOutsideHorizonPastAndSkippedCivilDayAvoidStorageScan() {
        for(String date:List.of("2025-12-30","2026-03-01")) assertTrue(on(date,"UTC").slots().isEmpty());
        f.now=Instant.parse("2011-12-01T00:00:00Z");
        assertTrue(on("2011-12-30","Pacific/Apia").slots().isEmpty());
        assertNull(f.loaded);
        f.policy=new SchedulingPolicy(1,30,3,Duration.ofSeconds(Long.MAX_VALUE),Duration.ofDays(1),Duration.ZERO,Duration.ZERO);
        assertTrue(on("2011-12-20","UTC").slots().isEmpty());
        f.policy=new SchedulingPolicy(1,30,3,Duration.ZERO,Duration.ofDays(1),Duration.ofSeconds(Long.MAX_VALUE),Duration.ZERO);
        assertTrue(on("2011-12-20","UTC").slots().isEmpty());
    }
    @Test void noticeAndPendingBufferCutoffsAndHorizonAreInclusiveWhereRequired() {
        f.now=Instant.parse("2026-01-02T08:00:00Z");
        f.policy=new SchedulingPolicy(2,30,20,Duration.ofHours(1),Duration.ofDays(1),Duration.ofHours(1),Duration.ZERO);
        assertEquals(Instant.parse("2026-01-02T09:30:00Z"),on("2026-01-02","UTC").slots().getFirst().start());
        f.policy=new SchedulingPolicy(2,30,20,Duration.ofHours(1),Duration.ofDays(1),Duration.ZERO,Duration.ZERO);
        assertEquals(Instant.parse("2026-01-02T09:00:00Z"),on("2026-01-02","UTC").slots().getFirst().start());
        assertEquals(f.policy.horizon(f.now),on("2026-02-02","UTC").slots().getLast().start());
        f.now=Instant.parse("2026-01-31T10:00:00Z");
        assertEquals(Instant.parse("2026-02-28T10:00:00Z"),on("2026-02-28","UTC").slots().getLast().start());
    }
    @Test void fallOverlapProducesBothOffsetsAndSpringGapProducesNone() {
        f.calendar=new AvailabilityCalendar(Map.of(DayOfWeek.SUNDAY,List.of(new AvailabilityCalendar.Window(120,180))),Map.of(),List.of(),1);
        f.now=Instant.parse("2026-10-01T00:00:00Z");
        var fall=on("2026-10-25","Europe/Brussels").slots();
        assertEquals(4,fall.size()); assertEquals(List.of("+02:00","+02:00","+01:00","+01:00"),fall.stream().map(PublicScheduling.Slot::displayOffset).toList());
        assertTrue(fall.getFirst().start().isBefore(fall.getLast().start()));
        f.now=Instant.parse("2026-03-01T00:00:00Z");
        assertTrue(on("2026-03-29","Europe/Brussels").slots().isEmpty());
    }
    @Test void displayDateBoundsDifferFromBusinessDatesAndQueryUsesWholeBrusselsDays() {
        f.now=Instant.parse("2026-01-01T00:00:00Z");
        f.calendar=new AvailabilityCalendar(Map.of(DayOfWeek.FRIDAY,List.of(new AvailabilityCalendar.Window(0,1440))),Map.of(),List.of(),1);
        var slots=on("2026-01-01","America/New_York").slots();
        assertEquals(LocalDate.parse("2026-01-02"),slots.getFirst().businessDate());
        assertTrue(slots.stream().allMatch(s->s.displayLocal().startsWith("2026-01-01")));
        assertEquals(Instant.parse("2025-12-31T23:00:00Z"),f.loaded.start());
        assertEquals(Instant.parse("2026-01-02T23:00:00Z"),f.loaded.end());
        assertTrue(slots.stream().noneMatch(s->s.start().equals(Instant.parse("2026-01-02T05:00:00Z"))));
    }
    @Test void fullReservationOverlapAndDailyCapacityIncludeAppointmentsOutsideDisplayWindow() {
        Booking occupied=f.pending(Instant.parse("2026-01-02T08:00:00Z")); f.bookings=List.of(occupied);
        var starts=on("2026-01-02","UTC").slots().stream().map(PublicScheduling.Slot::start).toList();
        assertFalse(starts.contains(Instant.parse("2026-01-02T09:30:00Z")));
        assertTrue(starts.contains(Instant.parse("2026-01-02T10:00:00Z")));
        f.policy=new SchedulingPolicy(1,30,1,Duration.ZERO,Duration.ofDays(1),Duration.ZERO,Duration.ZERO);
        assertTrue(on("2026-01-02","America/New_York").slots().isEmpty());
        f.now=occupied.expiresAt();
        assertFalse(on("2026-01-02","America/New_York").slots().isEmpty());
    }
    @Test void blackoutsAndClosedExceptionsAreAppliedToFullReservation() {
        var blackout=new AvailabilityCalendar.Blackout(UUID.randomUUID(),new ReservationInterval(Instant.parse("2026-01-02T10:00:00Z"),Instant.parse("2026-01-02T11:00:00Z")),"Synthetic maintenance");
        f.calendar=new AvailabilityCalendar(AvailabilityCalendar.defaults().weekly(),Map.of(),List.of(blackout),1);
        assertEquals(Instant.parse("2026-01-02T08:00:00Z"),on("2026-01-02","UTC").slots().getFirst().start());
        assertFalse(on("2026-01-02","UTC").slots().stream().anyMatch(s->s.start().equals(Instant.parse("2026-01-02T08:30:00Z"))));
        f.calendar=new AvailabilityCalendar(Map.of(),Map.of(LocalDate.parse("2026-01-02"),new AvailabilityCalendar.DateException(AvailabilityCalendar.Mode.CLOSED,List.of())),List.of(),1);
        assertTrue(on("2026-01-02","UTC").slots().isEmpty());
    }
    @Test void completedMorningStillConsumesCapacityForEveningAndThirtyOneDatesAreAccepted() {
        Booking pending=f.pending(Instant.parse("2026-01-02T08:00:00Z"));
        Booking confirmed=pending.approve(f.now,Meeting.parse("https://zoom.us/j/123456789",Set.of("zoom.us")),Booking.ConfirmationBasis.MANUAL_APPROVAL);
        f.now=Instant.parse("2026-01-02T11:00:00Z");
        f.bookings=List.of(confirmed.outcome(f.now,Booking.State.COMPLETED,""));
        f.policy=new SchedulingPolicy(1,30,1,Duration.ZERO,Duration.ofDays(1),Duration.ZERO,Duration.ZERO);
        assertTrue(on("2026-01-02","America/New_York").slots().isEmpty());
        var range=query.availability(f.service.id(),LocalDate.parse("2026-01-02"),LocalDate.parse("2026-02-01"),"UTC");
        assertFalse(range.slots().isEmpty());
        assertTrue(range.slots().stream().allMatch(slot->slot.start().isAfter(f.now)));
        assertTrue(range.slots().stream().noneMatch(slot->slot.businessDate().equals(LocalDate.parse("2026-01-02"))));
    }
    PublicScheduling.Availability on(String day,String zone) { return query.availability(f.service.id(),LocalDate.parse(day),LocalDate.parse(day),zone); }
    void assertCode(String code,Runnable action) { assertEquals(code,assertThrows(DomainFailure.class,action::run).code()); }
    static class Fixture implements SchedulingUnitOfWork,SchedulingUnitOfWork.View {
        Instant now=Instant.parse("2026-01-01T00:00:00Z"); int reads;
        ServiceDefinition service=new ServiceDefinition(UUID.randomUUID(),1,"Synthetic service",60,new Money(100,"EUR"),true);
        List<ServiceDefinition> services=List.of(service); List<Booking> bookings=List.of(); ReservationInterval loaded;
        SchedulingPolicy policy=new SchedulingPolicy(1,30,20,Duration.ZERO,Duration.ofDays(1),Duration.ZERO,Duration.ZERO);
        AvailabilityCalendar calendar=AvailabilityCalendar.defaults();
        public <T>T read(Function<View,T> work) { reads++; return work.apply(this); }
        public Receipt execute(CommandKey key,Function<Transaction,Receipt> work) { throw new AssertionError("Anonymous query must not mutate"); }
        public Instant now(){return now;} public List<ServiceDefinition> services(){return services;}
        public SchedulingPolicy policy(){return policy;} public AvailabilityCalendar calendar(){return calendar;}
        public Optional<Booking> booking(UUID id){throw new AssertionError("Unexpected identity read");}
        public List<Booking> bookings(ReservationInterval interval){loaded=interval;return bookings;}
        Booking pending(Instant start) {
            var intake=new Intake("Synthetic","Person","+32123456789",LocalDate.parse("2000-01-01"),LocalTime.NOON,"Synthetic town","");
            var snapshot=new Booking.Snapshot(service,policy,intake,"synthetic@example.invalid","UTC","v1");
            return Booking.pending(UUID.randomUUID(),"SYNTHETIC1",UUID.randomUUID(),snapshot,Booking.Origin.CUSTOMER,now,start);
        }
    }
}
