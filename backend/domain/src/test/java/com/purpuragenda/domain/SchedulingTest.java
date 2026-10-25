package com.purpuragenda.domain;

import com.purpuragenda.domain.scheduling.*;
import com.purpuragenda.domain.scheduling.AvailabilityCalendar.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class SchedulingTest {
    static final Instant NOW=Instant.parse("2027-01-10T09:00:00Z");
    @Test void reservationHasExactElapsedDurationAndHalfOpenAdjacency() {
        Instant at=Instant.parse("2027-01-12T14:30:00Z");
        ReservationInterval block=ReservationInterval.reserve(at);
        assertEquals(Duration.ofHours(2),Duration.between(block.start(),block.end()));
        for(int offset=-240;offset<=240;offset++) {
            ReservationInterval other=ReservationInterval.reserve(at.plusSeconds(offset*60L));
            assertEquals(offset > -120 && offset < 120,block.intersects(other));
            assertEquals(block.intersects(other),other.intersects(block));
        }
        assertThrows(DomainFailure.class,()->new ReservationInterval(at,at));
    }
    @Test void windowsPermitStartsNotWholeSessionsAndGridStartsAtMidnight() {
        var calendar=AvailabilityCalendar.defaults();
        Instant late=LocalDate.of(2027,1,12).atTime(19,30).atZone(SchedulingPolicy.BUSINESS_ZONE).toInstant();
        assertTrue(calendar.permitsStart(late,30));
        assertFalse(calendar.permitsStart(late.plusSeconds(1800),30));
        var odd=new AvailabilityCalendar(Map.of(DayOfWeek.TUESDAY,List.of(new Window(550,600))),Map.of(),List.of(),1);
        assertEquals(List.of(LocalDate.of(2027,1,12).atTime(9,30).atZone(SchedulingPolicy.BUSINESS_ZONE).toInstant()),odd.candidates(LocalDate.of(2027,1,12),30));
    }
    @Test void dstOmitsGapAndEnumeratesBothOverlapOffsets() {
        var calendar=new AvailabilityCalendar(Map.of(DayOfWeek.SUNDAY,List.of(new Window(150,180))),Map.of(),List.of(),1);
        assertTrue(calendar.candidates(LocalDate.of(2027,3,28),30).isEmpty());
        var overlap=calendar.candidates(LocalDate.of(2026,10,25),30);
        assertEquals(List.of(Instant.parse("2026-10-25T00:30:00Z"),Instant.parse("2026-10-25T01:30:00Z")),overlap);
        assertEquals(2,overlap.stream().map(i->i.atZone(SchedulingPolicy.BUSINESS_ZONE).getOffset()).distinct().count());
    }
    @ParameterizedTest @CsvSource({"2027-01-31T13:30:00Z,2027-02-28T13:30:00Z","2028-01-31T13:30:00Z,2028-02-29T13:30:00Z","2027-02-28T01:30:00Z,2027-03-28T01:30:00Z","2026-09-25T00:30:00Z,2026-10-25T00:30:00Z"})
    void calendarMonthHorizonClampsAndResolvesZoneRules(String now,String expected) { assertEquals(Instant.parse(expected),SchedulingPolicy.defaults().horizon(Instant.parse(now))); }
    @Test void noticeAndHorizonAreInclusive() {
        var p=SchedulingPolicy.defaults();var c=AvailabilityCalendar.defaults();
        var atNotice=SchedulingRules.violations(NOW.plus(Duration.ofHours(24)),NOW,p,c,0,0,0);
        assertFalse(atNotice.contains(SchedulingRules.Rule.NOTICE));
        assertTrue(SchedulingRules.violations(NOW.plus(Duration.ofHours(24)).minusNanos(1000),NOW,p,c,0,0,0).contains(SchedulingRules.Rule.NOTICE));
        assertFalse(SchedulingRules.violations(p.horizon(NOW),NOW,p,c,0,0,0).contains(SchedulingRules.Rule.HORIZON));
        assertTrue(SchedulingRules.violations(p.horizon(NOW).plusNanos(1000),NOW,p,c,0,0,0).contains(SchedulingRules.Rule.HORIZON));
    }
    @Test void exceptionsDeduplicateAndClosedRestoresInheritanceWhenRemoved() {
        LocalDate date=LocalDate.of(2027,1,12);var base=AvailabilityCalendar.defaults();
        var add=new AvailabilityCalendar(base.weekly(),Map.of(date,new DateException(Mode.ADD,List.of(new Window(540,600)))),List.of(),1);
        assertEquals(base.candidates(date,30),add.candidates(date,30));
        assertTrue(new AvailabilityCalendar(base.weekly(),Map.of(date,new DateException(Mode.CLOSED,List.of())),List.of(),1).candidates(date,30).isEmpty());
        assertEquals(2,new AvailabilityCalendar(base.weekly(),Map.of(date,new DateException(Mode.REPLACE,List.of(new Window(0,60)))),List.of(),1).candidates(date,30).size());
    }
    @Test void hardBlackoutsCoverRecoveryAndRequireNamedOverrideButOverlapNeverOverrides() {
        var p=SchedulingPolicy.defaults();var base=AvailabilityCalendar.defaults();
        Instant start=Instant.parse("2027-01-12T18:30:00Z");UUID blackoutId=UUID.randomUUID();
        var c=new AvailabilityCalendar(base.weekly(),Map.of(),List.of(new Blackout(blackoutId,new ReservationInterval(start.plusSeconds(3600),start.plusSeconds(5400)),"Practitioner unavailable")),1);
        assertThrows(DomainFailure.class,()->SchedulingRules.validate(start,NOW,p,c,0,0,0,List.of(),SchedulingRules.Override.none()));
        var override=new SchedulingRules.Override(Set.of(SchedulingRules.Rule.HARD_BLACKOUT),"Exceptional working arrangement",Set.of(blackoutId));
        assertDoesNotThrow(()->SchedulingRules.validate(start,NOW,p,c,0,0,0,List.of(),override));
        DomainFailure conflict=assertThrows(DomainFailure.class,()->SchedulingRules.validate(start,NOW,p,c,0,0,0,List.of(ReservationInterval.reserve(start)),override));
        assertEquals("SLOT_UNAVAILABLE",conflict.code());
        assertThrows(DomainFailure.class,()->new SchedulingRules.Override(Set.of(SchedulingRules.Rule.NOTICE),"short",Set.of()));
    }
    @Test void allThreeQuotasAreSeparateAndCapacityMayBeZero() {
        var rules=SchedulingRules.violations(NOW.plusSeconds(172800),NOW,SchedulingPolicy.defaults(),AvailabilityCalendar.defaults(),3,3,3);
        assertTrue(rules.containsAll(Set.of(SchedulingRules.Rule.DAILY_CAPACITY,SchedulingRules.Rule.ACTIVE_LIMIT,SchedulingRules.Rule.REQUEST_LIMIT)));
        var zero=new SchedulingPolicy(2,30,0,Duration.ZERO,Duration.ofHours(24),Duration.ofHours(1),Duration.ofHours(24));
        assertTrue(SchedulingRules.violations(NOW.plusSeconds(172800),NOW,zero,AvailabilityCalendar.defaults(),0,0,0).contains(SchedulingRules.Rule.DAILY_CAPACITY));
    }
    @Test void zonesAndSubmissionDatesAreIndependent() {
        Instant instant=Instant.parse("2027-01-10T23:30:00Z");
        assertEquals(LocalDate.of(2027,1,11),instant.atZone(SchedulingPolicy.BUSINESS_ZONE).toLocalDate());
        assertEquals(LocalDate.of(2027,1,10),instant.atZone(ZoneId.of("America/Los_Angeles")).toLocalDate());
        assertEquals(LocalTime.of(10,30),LocalDate.of(2027,3,20).atTime(15,30).atZone(SchedulingPolicy.BUSINESS_ZONE).withZoneSameInstant(ZoneId.of("America/New_York")).toLocalTime());
    }
}
