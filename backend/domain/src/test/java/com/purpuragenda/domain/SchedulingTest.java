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

    @Test void windowsMergeAndCalendarDefensivelyCopiesCollections() {
        LocalDate date=LocalDate.of(2027,1,12);
        var windows=new ArrayList<>(List.of(new Window(600,660),new Window(540,610),new Window(660,720),new Window(800,900)));
        var weekly=new EnumMap<DayOfWeek,List<Window>>(DayOfWeek.class); weekly.put(DayOfWeek.TUESDAY,windows);
        var exceptions=new HashMap<LocalDate,DateException>();
        var calendar=new AvailabilityCalendar(weekly,exceptions,new ArrayList<>(),1);
        windows.clear();weekly.clear();exceptions.put(date,new DateException(Mode.CLOSED,List.of()));
        assertEquals(List.of(new Window(540,720),new Window(800,900)),calendar.windows(date));
        assertThrows(UnsupportedOperationException.class,()->calendar.weekly().clear());
        assertThrows(UnsupportedOperationException.class,()->calendar.windows(date).clear());
        var add=new AvailabilityCalendar(calendar.weekly(),Map.of(date,new DateException(Mode.ADD,List.of(new Window(700,810)))),List.of(),1);
        assertEquals(List.of(new Window(540,900)),add.windows(date));
        assertEquals(List.of(),calendar.windows(date.plusDays(1)));
        assertFalse(calendar.permitsStart(date.atTime(12,0).atZone(SchedulingPolicy.BUSINESS_ZONE).toInstant(),30));
    }

    @Test void invalidCalendarAndGridValuesFailSafely() {
        var calendar=AvailabilityCalendar.defaults();
        for(int grid:List.of(0,121)) {
            assertEquals("INVALID_POLICY",assertThrows(DomainFailure.class,()->calendar.permitsStart(NOW,grid)).code());
            assertThrows(DomainFailure.class,()->calendar.candidates(LocalDate.of(2027,1,12),grid));
        }
        assertFalse(calendar.permitsStart(NOW.plusSeconds(1),30));
        assertFalse(calendar.permitsStart(NOW.plusNanos(1000),30));
        assertFalse(calendar.permitsStart(NOW.plusSeconds(60),30));
        assertFalse(new AvailabilityCalendar(Map.of(),Map.of(),List.of(),1).permitsStart(NOW,30));
        for(int[] bounds:List.of(new int[]{-1,60},new int[]{0,0},new int[]{60,59},new int[]{0,1441}))
            assertThrows(DomainFailure.class,()->new Window(bounds[0],bounds[1]));
        assertDoesNotThrow(()->new Window(0,1440));
        assertThrows(DomainFailure.class,()->new DateException(null,List.of()));
        assertThrows(DomainFailure.class,()->new DateException(Mode.ADD,null));
        assertThrows(DomainFailure.class,()->new DateException(Mode.CLOSED,List.of(new Window(0,60))));
        assertThrows(DomainFailure.class,()->new AvailabilityCalendar(null,Map.of(),List.of(),1));
        assertThrows(DomainFailure.class,()->new AvailabilityCalendar(Map.of(),null,List.of(),1));
        assertThrows(DomainFailure.class,()->new AvailabilityCalendar(Map.of(),Map.of(),null,1));
        assertThrows(DomainFailure.class,()->new AvailabilityCalendar(Map.of(),Map.of(),List.of(),0));
        var nullDay=new HashMap<DayOfWeek,List<Window>>();nullDay.put(null,List.of());
        assertThrows(DomainFailure.class,()->new AvailabilityCalendar(nullDay,Map.of(),List.of(),1));
        var nullWindows=new EnumMap<DayOfWeek,List<Window>>(DayOfWeek.class);nullWindows.put(DayOfWeek.MONDAY,null);
        assertThrows(DomainFailure.class,()->new AvailabilityCalendar(nullWindows,Map.of(),List.of(),1));
        nullWindows.put(DayOfWeek.MONDAY,Arrays.asList((Window)null));
        assertThrows(DomainFailure.class,()->new AvailabilityCalendar(nullWindows,Map.of(),List.of(),1));
        assertThrows(DomainFailure.class,()->new Blackout(null,ReservationInterval.reserve(NOW),"Long enough reason"));
        assertThrows(DomainFailure.class,()->new Blackout(UUID.randomUUID(),null,"Long enough reason"));
    }

    @Test void policyExpiryAndCustomerCutoffUseInclusiveElapsedTime() {
        var p=SchedulingPolicy.defaults();
        assertEquals(NOW.plusSeconds(86400),p.expiresAt(NOW,NOW.plusSeconds(172800)));
        assertEquals(NOW.plusSeconds(3600),p.expiresAt(NOW,NOW.plusSeconds(7200)));
        assertThrows(DomainFailure.class,()->p.expiresAt(NOW,NOW.plusSeconds(3600)));
        assertTrue(p.customerMayChange(NOW.plusSeconds(86400),NOW));
        assertFalse(p.customerMayChange(NOW.plusSeconds(86400).minusNanos(1000),NOW));
        assertTrue(p.customerMayChange(NOW.plusSeconds(86400).plusNanos(1000),NOW));
    }

    @Test void everyPolicyFieldRejectsInvalidConfiguration() {
        var day=Duration.ofDays(1);var hour=Duration.ofHours(1);var negative=Duration.ofSeconds(-1);
        for(long version:List.of(0L,-1L))assertThrows(DomainFailure.class,()->new SchedulingPolicy(version,30,3,day,day,hour,day));
        for(int grid:List.of(0,121))assertThrows(DomainFailure.class,()->new SchedulingPolicy(1,grid,3,day,day,hour,day));
        for(int cap:List.of(-1))assertThrows(DomainFailure.class,()->new SchedulingPolicy(1,30,cap,day,day,hour,day));
        assertThrows(DomainFailure.class,()->new SchedulingPolicy(1,30,3,null,day,hour,day));
        assertThrows(DomainFailure.class,()->new SchedulingPolicy(1,30,3,negative,day,hour,day));
        assertThrows(DomainFailure.class,()->new SchedulingPolicy(1,30,3,day,null,hour,day));
        assertThrows(DomainFailure.class,()->new SchedulingPolicy(1,30,3,day,Duration.ZERO,hour,day));
        assertThrows(DomainFailure.class,()->new SchedulingPolicy(1,30,3,day,negative,hour,day));
        assertThrows(DomainFailure.class,()->new SchedulingPolicy(1,30,3,day,day,null,day));
        assertThrows(DomainFailure.class,()->new SchedulingPolicy(1,30,3,day,day,negative,day));
        assertThrows(DomainFailure.class,()->new SchedulingPolicy(1,30,3,day,day,hour,null));
        assertThrows(DomainFailure.class,()->new SchedulingPolicy(1,30,3,day,day,hour,negative));
        assertDoesNotThrow(()->new SchedulingPolicy(1,120,Integer.MAX_VALUE,Duration.ZERO,hour,Duration.ZERO,Duration.ZERO));
    }

    @Test void eachRuleHasAStableFailureAndExplicitOverride() {
        var p=SchedulingPolicy.defaults();var base=AvailabilityCalendar.defaults();
        Instant start=Instant.parse("2027-01-12T09:00:00Z");
        assertDoesNotThrow(()->SchedulingRules.validate(start,NOW,p,base,0,0,0,List.of(),SchedulingRules.Override.none()));
        assertEquals("START_NOT_FUTURE",assertThrows(DomainFailure.class,()->SchedulingRules.validate(NOW,NOW,p,base,0,0,0,List.of(),SchedulingRules.Override.none())).code());
        Object[][] cases={
            {start.plusSeconds(1),NOW,0,0,0,"START_NOT_AVAILABLE"},
            {NOW.plusSeconds(3600),NOW,0,0,0,"MINIMUM_NOTICE"},
            {start.plusSeconds(40*86400),NOW,0,0,0,"OUTSIDE_HORIZON"},
            {start,NOW,3,0,0,"DAILY_CAPACITY_REACHED"},
            {start,NOW,0,3,0,"ACTIVE_LIMIT_REACHED"},
            {start,NOW,0,0,3,"REQUEST_LIMIT_REACHED"}
        };
        for(Object[] c:cases) {
            Instant target=(Instant)c[0],now=(Instant)c[1];int daily=(int)c[2],active=(int)c[3],requests=(int)c[4];
            assertEquals(c[5],assertThrows(DomainFailure.class,()->SchedulingRules.validate(target,now,p,base,daily,active,requests,List.of(),SchedulingRules.Override.none())).code());
            var override=new SchedulingRules.Override(SchedulingRules.violations(target,now,p,base,daily,active,requests),"Explicit synthetic exception",Set.of());
            assertDoesNotThrow(()->SchedulingRules.validate(target,now,p,base,daily,active,requests,List.of(),override));
        }
        var id=UUID.randomUUID();
        var calendar=new AvailabilityCalendar(base.weekly(),Map.of(),List.of(new Blackout(id,ReservationInterval.reserve(start),"Synthetic hard blackout")),1);
        var unnamed=new SchedulingRules.Override(Set.of(SchedulingRules.Rule.HARD_BLACKOUT),"Explicit synthetic exception",Set.of());
        assertEquals("BLACKOUT_OVERRIDE_REQUIRED",assertThrows(DomainFailure.class,()->SchedulingRules.validate(start,NOW,p,calendar,0,0,0,List.of(),unnamed)).code());
    }
}
