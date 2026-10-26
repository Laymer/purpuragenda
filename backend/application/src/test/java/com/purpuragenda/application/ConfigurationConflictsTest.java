package com.purpuragenda.application;

import com.purpuragenda.application.scheduling.ConfigurationConflicts;
import com.purpuragenda.application.scheduling.ConfigurationConflicts.*;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.scheduling.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationConflictsTest {
    final PublicSchedulingTest.Fixture f=new PublicSchedulingTest.Fixture();
    final ConfigurationConflicts query=new ConfigurationConflicts(f);
    static final Instant MONDAY=Instant.parse("2026-01-05T08:00:00Z");   // 09:00 Brussels

    Booking booking(Instant start) { return booking(start,f.now); }
    Booking booking(Instant start,Instant created) {
        Instant keep=f.now;f.now=created;try { return f.pending(start); } finally { f.now=keep; }
    }
    Booking confirmed(Instant start,Instant created) {
        Booking value=booking(start,created);
        return Booking.confirmed(value.id(),value.reference(),value.customerId(),value.snapshot(),value.origin(),created,start,Meeting.parse("https://zoom.us/j/123456789",Set.of("zoom.us")));
    }
    AvailabilityCalendar calendar(Map<DayOfWeek,List<AvailabilityCalendar.Window>> weekly,Map<LocalDate,AvailabilityCalendar.DateException> exceptions,List<AvailabilityCalendar.Blackout> blackouts) {
        return new AvailabilityCalendar(weekly,exceptions,blackouts,f.calendar.version()+1);
    }
    Map<DayOfWeek,List<AvailabilityCalendar.Window>> weekly(int from,int to) {
        Map<DayOfWeek,List<AvailabilityCalendar.Window>> days=new EnumMap<>(DayOfWeek.class);
        for(var day:DayOfWeek.values())days.put(day,List.of(new AvailabilityCalendar.Window(from,to)));return days;
    }
    List<Conflict> assess(AvailabilityCalendar calendar,SchedulingPolicy policy) { return query.assess(new Proposal(calendar,policy)).conflicts(); }

    @Test void anUnchangedConfigurationAndImmaterialPolicyFieldsConflictWithNothing() {
        f.bookings=List.of(booking(MONDAY),booking(MONDAY.plusSeconds(86400)));
        var assessment=query.assess(new Proposal(f.calendar,null));assertTrue(assessment.conflicts().isEmpty());
        assertEquals(f.now,assessment.assessedAt());assertEquals(f.calendar.version(),assessment.calendarVersion());assertEquals(f.policy.version(),assessment.policyVersion());
        var p=f.policy;
        assertTrue(assess(null,new SchedulingPolicy(2,p.gridMinutes(),p.dailyCapacity(),Duration.ofDays(9),Duration.ofHours(2),Duration.ofHours(5),Duration.ofDays(3))).isEmpty());
        assertEquals(2,query.assess(new Proposal(null,new SchedulingPolicy(2,p.gridMinutes(),p.dailyCapacity(),p.minimumNotice(),p.pendingLifetime(),p.expiryBuffer(),p.customerCutoff()))).policyVersion());
        assertThrows(DomainFailure.class,()->new Proposal(null,null));assertThrows(NullPointerException.class,()->query.assess(null));
    }

    @Test void shrunkenWindowsReportOnlyFutureOccupyingBookingsOutsideThem() {
        var inside=booking(MONDAY);var outside=booking(Instant.parse("2026-01-05T18:00:00Z"));   // 19:00 Brussels
        var cancelled=booking(Instant.parse("2026-01-06T18:00:00Z")).cancel(f.now,false);
        var expired=booking(Instant.parse("2026-01-07T18:00:00Z"));expired=expired.expire(expired.expiresAt());
        var started=booking(f.now.plusSeconds(3600),f.now.minusSeconds(86400));   // not in the future once now moves
        f.bookings=List.of(inside,outside,cancelled,expired);
        var conflicts=assess(calendar(weekly(540,1080),Map.of(),List.of()),null);
        assertEquals(1,conflicts.size());var conflict=conflicts.getFirst();
        assertEquals(outside.id(),conflict.bookingId());assertEquals(Set.of(Reason.START_RULES),conflict.reasons());assertEquals(outside.reference(),conflict.reference());
        assertEquals(Booking.State.PENDING_CONFIRMATION,conflict.state());assertTrue(conflict.blackoutIds().isEmpty());
        f.bookings=List.of(started);f.now=started.start().plusSeconds(1);
        assertTrue(assess(calendar(weekly(0,60),Map.of(),List.of()),null).isEmpty());
        // A booking that already started is not a future conflict.
        f.now=Instant.parse("2026-01-05T08:30:00Z");f.bookings=List.of(inside);
        assertTrue(assess(calendar(weekly(0,60),Map.of(),List.of()),null).isEmpty());
    }

    @Test void gridClosedDatesAndExceptionsAreEvaluatedAgainstTheBrusselsCalendar() {
        var half=booking(MONDAY.plusSeconds(1800));var full=booking(MONDAY.plusSeconds(86400));
        f.bookings=List.of(half,full);
        var p=f.policy;var hourly=new SchedulingPolicy(2,60,p.dailyCapacity(),p.minimumNotice(),p.pendingLifetime(),p.expiryBuffer(),p.customerCutoff());
        assertEquals(List.of(half.id()),assess(null,hourly).stream().map(Conflict::bookingId).toList());
        var closed=calendar(f.calendar.weekly(),Map.of(LocalDate.of(2026,1,6),new AvailabilityCalendar.DateException(AvailabilityCalendar.Mode.CLOSED,List.of())),List.of());
        assertEquals(List.of(full.id()),assess(closed,null).stream().map(Conflict::bookingId).toList());
        var replaced=calendar(f.calendar.weekly(),Map.of(LocalDate.of(2026,1,6),new AvailabilityCalendar.DateException(AvailabilityCalendar.Mode.REPLACE,List.of(new AvailabilityCalendar.Window(600,660)))),List.of());
        assertEquals(1,assess(replaced,null).size());
        var added=calendar(f.calendar.weekly(),Map.of(LocalDate.of(2026,1,6),new AvailabilityCalendar.DateException(AvailabilityCalendar.Mode.ADD,List.of(new AvailabilityCalendar.Window(1250,1300)))),List.of());
        assertTrue(assess(added,null).isEmpty());
    }

    @Test void blackoutsConflictWithTheWholeReservationButNotWithAdjacentTime() {
        var booking=booking(MONDAY);f.bookings=List.of(booking);UUID after=UUID.randomUUID(),before=UUID.randomUUID(),adjacent=UUID.randomUUID(),far=UUID.randomUUID();
        Instant end=MONDAY.plus(Duration.ofMinutes(120));
        var blackouts=List.of(
                new AvailabilityCalendar.Blackout(after,new ReservationInterval(end.minusSeconds(60),end.plusSeconds(3600)),"Synthetic closure reason"),
                new AvailabilityCalendar.Blackout(before,new ReservationInterval(MONDAY.minusSeconds(3600),MONDAY.plusSeconds(1)),"Synthetic closure reason"),
                new AvailabilityCalendar.Blackout(adjacent,new ReservationInterval(end,end.plusSeconds(60)),"Synthetic closure reason"),
                new AvailabilityCalendar.Blackout(far,new ReservationInterval(MONDAY.minusSeconds(7200),MONDAY),"Synthetic closure reason"));
        var conflicts=assess(calendar(f.calendar.weekly(),Map.of(),blackouts),null);
        assertEquals(1,conflicts.size());assertEquals(Set.of(Reason.HARD_BLACKOUT),conflicts.getFirst().reasons());
        assertEquals(Set.of(after,before),conflicts.getFirst().blackoutIds());
        // A blackout already in force is not a new conflict.
        f.calendar=calendar(f.calendar.weekly(),Map.of(),blackouts);assertTrue(assess(f.calendar,null).isEmpty());
    }

    @Test void capacityKeepsEarlierCreatedBookingsAndFlagsTheExcessIncludingEarlierTodayCounts() {
        Instant day=Instant.parse("2026-01-05T08:00:00Z");
        f.policy=new SchedulingPolicy(1,30,20,Duration.ZERO,Duration.ofDays(30),Duration.ZERO,Duration.ZERO);
        Booking first=confirmed(day,f.now.minusSeconds(300)),second=booking(day.plusSeconds(10800),f.now.minusSeconds(200)),third=booking(day.plusSeconds(21600),f.now.minusSeconds(100));
        f.bookings=List.of(third,first,second);var p=f.policy;
        SchedulingPolicy cap1=new SchedulingPolicy(2,p.gridMinutes(),1,p.minimumNotice(),p.pendingLifetime(),p.expiryBuffer(),p.customerCutoff());
        assertEquals(List.of(second.id(),third.id()),assess(null,cap1).stream().map(Conflict::bookingId).toList());
        SchedulingPolicy zero=new SchedulingPolicy(2,p.gridMinutes(),0,p.minimumNotice(),p.pendingLifetime(),p.expiryBuffer(),p.customerCutoff());
        assertEquals(3,assess(null,zero).size());assertTrue(assess(null,zero).stream().allMatch(c->c.reasons().equals(Set.of(Reason.DAILY_CAPACITY))));
        // Earlier occupying bookings today (already started) still consume the new limit.
        f.now=day.plusSeconds(60);f.bookings=List.of(first,second);
        assertEquals(List.of(second.id()),assess(null,cap1).stream().map(Conflict::bookingId).toList());
        // Over-limit state that already exists is not blamed on the proposal.
        f.policy=cap1;assertTrue(assess(null,cap1).isEmpty());
    }

    @Test void combinedReasonsAreAllReportedForOneBooking() {
        var late=booking(Instant.parse("2026-01-05T18:00:00Z"));var second=booking(Instant.parse("2026-01-05T17:30:00Z"),f.now.plusSeconds(1));
        f.bookings=List.of(late,second);var p=f.policy;
        var blackout=new AvailabilityCalendar.Blackout(UUID.randomUUID(),new ReservationInterval(late.start(),late.start().plusSeconds(60)),"Synthetic closure reason");
        var conflicts=assess(calendar(weekly(540,600),Map.of(),List.of(blackout)),new SchedulingPolicy(2,p.gridMinutes(),1,p.minimumNotice(),p.pendingLifetime(),p.expiryBuffer(),p.customerCutoff()));
        assertEquals(2,conflicts.size());
        assertEquals(Set.of(Reason.START_RULES,Reason.HARD_BLACKOUT,Reason.DAILY_CAPACITY),conflicts.getFirst().reasons());
        assertEquals(Set.of(Reason.START_RULES,Reason.HARD_BLACKOUT),conflicts.getLast().reasons());
    }

    @Test void resultsAreImmutableCopies() {
        var reasons=new HashSet<>(Set.of(Reason.START_RULES));var conflict=new Conflict(UUID.randomUUID(),"SYNTHETIC1",MONDAY,Booking.State.CONFIRMED,reasons,Set.of());
        reasons.add(Reason.HARD_BLACKOUT);assertEquals(Set.of(Reason.START_RULES),conflict.reasons());
        var list=new ArrayList<>(List.of(conflict));var assessment=new Assessment(f.now,1,1,list);list.clear();
        assertEquals(1,assessment.conflicts().size());assertThrows(UnsupportedOperationException.class,()->assessment.conflicts().clear());
    }
}
