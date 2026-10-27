package com.purpuragenda.application;

import com.purpuragenda.application.port.out.CustomerReschedulingQuery;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.scheduling.*;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.scheduling.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CustomerReschedulingAvailabilityTest {
    final PublicSchedulingTest.Fixture f=new PublicSchedulingTest.Fixture();
    final SessionBinding session=new SessionBinding(UUID.randomUUID(),"a".repeat(64));
    Booking original=f.pending(Instant.parse("2026-01-03T08:00:00Z"));
    int active=1,reads;boolean authorized=true;
    final CustomerReschedulingAvailability query=new CustomerReschedulingAvailability((s,id,range)->{
        assertEquals(session,s);assertEquals(original.id(),id);reads++;f.loaded=range;
        return authorized?Optional.of(new CustomerReschedulingQuery.Context(original,f.policy,f.calendar,f.bookings,active,f.now)):Optional.empty();
    });
    PublicScheduling.Availability on(String day,String zone) {
        return query.find(session,original.id(),LocalDate.parse(day),LocalDate.parse(day),zone).orElseThrow();
    }
    void confirmed() { original=original.approve(f.now,Meeting.parse("https://zoom.us/j/123456789",Set.of("zoom.us")),Booking.ConfirmationBasis.MANUAL_APPROVAL); }
    @Test void originalTermsRemainAvailableWithoutAnyCurrentServiceLookupAndOwnReservationIsExcluded() {
        f.service=new ServiceDefinition(f.service.id(),2,"Archived",30,new Money(900,"EUR"),false);f.services=List.of(f.service);
        f.bookings=List.of(original);active=3;
        f.policy=new SchedulingPolicy(2,30,1,Duration.ZERO,Duration.ofDays(1),Duration.ZERO,Duration.ZERO);
        var result=on("2026-01-03","Europe/Brussels");
        var slot=result.slots().stream().filter(s->s.start().equals(original.start())).findFirst().orElseThrow();
        assertEquals(3600,Duration.between(slot.start(),slot.sessionEnd()).toSeconds());
        assertEquals(7200,Duration.between(slot.start(),slot.reservationEnd()).toSeconds());
        assertEquals(f.now,result.serverNow());assertEquals(2,result.policyVersion());assertEquals(1,reads);
        assertEquals("Europe/Brussels",result.businessZone());assertEquals(f.policy.horizon(f.now),result.horizon());
        assertThrows(UnsupportedOperationException.class,()->result.slots().clear());
        active=4;assertTrue(on("2026-01-03","UTC").slots().isEmpty());
    }
    @Test void pendingUsesOriginalBufferAndConfirmedIgnoresAnyPendingBuffer() {
        f.now=Instant.parse("2026-01-02T08:00:00Z");
        f.policy=new SchedulingPolicy(1,30,20,Duration.ZERO,Duration.ofDays(1),Duration.ofHours(1),Duration.ZERO);
        original=f.pending(Instant.parse("2026-01-03T08:00:00Z"));
        f.policy=new SchedulingPolicy(2,30,20,Duration.ZERO,Duration.ofDays(1),Duration.ofDays(50),Duration.ZERO);
        assertEquals(Instant.parse("2026-01-02T09:30:00Z"),on("2026-01-02","UTC").slots().getFirst().start());
        confirmed();assertEquals(Instant.parse("2026-01-02T08:30:00Z"),on("2026-01-02","UTC").slots().getFirst().start());
        f.policy=new SchedulingPolicy(3,30,20,Duration.ofHours(1),Duration.ofDays(1),Duration.ZERO,Duration.ZERO);
        assertEquals(Instant.parse("2026-01-02T09:00:00Z"),on("2026-01-02","UTC").slots().getFirst().start());
        f.policy=new SchedulingPolicy(4,30,20,Duration.ofSeconds(Long.MAX_VALUE),Duration.ofDays(1),Duration.ZERO,Duration.ZERO);
        assertTrue(on("2026-01-02","UTC").slots().isEmpty());
    }
    @Test void inclusiveOriginalCutoffAndStrictExpiryControlAllAdvice() {
        f.policy=SchedulingPolicy.defaults();original=f.pending(Instant.parse("2026-01-03T08:00:00Z"));confirmed();
        f.now=original.start().minus(Duration.ofHours(24));
        assertTrue(on("2026-01-04","UTC").restrictions().isEmpty());
        f.now=f.now.plusNanos(1000);assertEquals(List.of("BOOKING_NOT_CHANGEABLE"),on("2026-01-04","UTC").restrictions());
        f.now=Instant.parse("2026-01-01T00:00:00Z");original=f.pending(Instant.parse("2026-01-03T08:00:00Z"));
        f.now=original.expiresAt().minusNanos(1000);assertTrue(on("2026-01-04","UTC").restrictions().isEmpty());
        f.now=original.expiresAt();assertEquals(List.of("BOOKING_NOT_CHANGEABLE"),on("2026-01-04","UTC").restrictions());
    }
    @Test void otherReservationsCapacityBlackoutsAndExpiryStillApply() {
        var other=f.pending(Instant.parse("2026-01-02T08:00:00Z"));f.bookings=List.of(original,other);
        var starts=on("2026-01-02","UTC").slots().stream().map(PublicScheduling.Slot::start).toList();
        assertFalse(starts.contains(other.start().plusSeconds(5400)));assertTrue(starts.contains(other.start().plusSeconds(7200)));
        f.policy=new SchedulingPolicy(2,30,1,Duration.ZERO,Duration.ofDays(1),Duration.ZERO,Duration.ZERO);
        assertTrue(on("2026-01-02","UTC").slots().isEmpty());confirmed();f.now=other.expiresAt();
        assertFalse(on("2026-01-02","UTC").slots().isEmpty());
        f.calendar=new AvailabilityCalendar(Map.of(),Map.of(),List.of(),1);assertTrue(on("2026-01-03","UTC").slots().isEmpty());
    }
    @Test void fallOffsetsSpringGapsAndMonthEndAreCalculatedFromInstants() {
        f.now=Instant.parse("2026-10-01T00:00:00Z");original=f.pending(Instant.parse("2026-10-28T08:00:00Z"));confirmed();
        f.calendar=new AvailabilityCalendar(Map.of(DayOfWeek.SUNDAY,List.of(new AvailabilityCalendar.Window(120,180))),Map.of(),List.of(),1);
        assertEquals(List.of("+02:00","+02:00","+01:00","+01:00"),on("2026-10-25","Europe/Brussels").slots().stream().map(PublicScheduling.Slot::displayOffset).toList());
        f.now=Instant.parse("2026-03-01T00:00:00Z");original=f.pending(Instant.parse("2026-03-31T08:00:00Z"));confirmed();assertTrue(on("2026-03-29","Europe/Brussels").slots().isEmpty());
        f.now=Instant.parse("2026-01-31T10:00:00Z");original=f.pending(Instant.parse("2026-02-28T12:00:00Z"));confirmed();f.calendar=AvailabilityCalendar.defaults();
        assertEquals(Instant.parse("2026-02-28T10:00:00Z"),on("2026-02-28","UTC").slots().getLast().start());
    }
    @Test void displayBoundsAndEmptyWindowsAreSafe() {
        confirmed();f.calendar=new AvailabilityCalendar(Map.of(DayOfWeek.FRIDAY,List.of(new AvailabilityCalendar.Window(0,1440))),Map.of(),List.of(),1);
        assertTrue(on("2026-01-01","America/New_York").slots().stream().allMatch(s->s.displayLocal().startsWith("2026-01-01")));
        assertEquals(Instant.parse("2025-12-31T23:00:00Z"),f.loaded.start());assertEquals(Instant.parse("2026-01-03T01:00:00Z"),f.loaded.end());
        assertTrue(on("2025-12-01","UTC").slots().isEmpty());assertTrue(on("2026-03-01","UTC").slots().isEmpty());
        f.now=Instant.parse("2011-12-01T00:00:00Z");assertTrue(on("2011-12-30","Pacific/Apia").slots().isEmpty());
    }
    @Test void invalidInputsAndMissingAuthorityNeverReturnBookingContext() {
        authorized=false;var day=LocalDate.parse("2026-01-02");assertTrue(query.find(session,original.id(),day,day,"UTC").isEmpty());
        for(LocalDate[] dates:List.of(new LocalDate[]{null,day},new LocalDate[]{day,null},new LocalDate[]{day,day.minusDays(1)},new LocalDate[]{day,day.plusDays(31)},new LocalDate[]{LocalDate.MAX,LocalDate.MAX}))
            assertEquals("INVALID_DATE_RANGE",assertThrows(DomainFailure.class,()->query.find(session,original.id(),dates[0],dates[1],"UTC")).code());
        assertEquals("INVALID_ZONE",assertThrows(DomainFailure.class,()->on("2026-01-02","bogus")).code());
        assertThrows(NullPointerException.class,()->new CustomerReschedulingAvailability(null));
        assertThrows(IllegalArgumentException.class,()->new CustomerReschedulingQuery.Context(original,f.policy,f.calendar,List.of(),-1,f.now));
        assertEquals("ReschedulingContext[REDACTED]",new CustomerReschedulingQuery.Context(original,f.policy,f.calendar,List.of(),0,f.now).toString());
    }
}
