package com.purpuragenda.application;

import com.purpuragenda.application.port.out.ConfigurationUnitOfWork;
import com.purpuragenda.application.port.out.ConfigurationUnitOfWork.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.*;
import com.purpuragenda.application.scheduling.AdministratorConfiguration.*;
import com.purpuragenda.application.scheduling.ConfigurationConflicts.Conflict;
import com.purpuragenda.application.scheduling.ConfigurationConflicts.Reason;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.scheduling.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdministratorConfigurationTest {
    final Fixture f=new Fixture();
    final AdministratorConfiguration config=new AdministratorConfiguration(f);
    final SessionBinding session=new SessionBinding(UUID.randomUUID(),"e".repeat(64));
    static final String REASON="Synthetic configuration reason";
    Review review(UUID... acknowledged){return new Review(REASON,Set.of(acknowledged));}
    Map<DayOfWeek,List<AvailabilityCalendar.Window>> weekly(int from,int to){Map<DayOfWeek,List<AvailabilityCalendar.Window>> days=new EnumMap<>(DayOfWeek.class);for(var day:DayOfWeek.values())days.put(day,List.of(new AvailabilityCalendar.Window(from,to)));return days;}
    Receipt replace(long version,Review review,int from,int to){return config.replaceWeekly(session,"synthetic-key",version,weekly(from,to),review);}
    Booking futureBooking(int hourUtc){f.policy=new SchedulingPolicy(1,30,20,Duration.ZERO,Duration.ofDays(400),Duration.ZERO,Duration.ZERO);Booking b=f.pending(Instant.parse(String.format("2026-01-05T%02d:00:00Z",hourUtc)));f.bookings=List.of(b);return b;}

    @Test void weeklyReplacementAdvancesTheVersionAndRecordsExactlyOneSaveAndEffect() {
        Receipt receipt=replace(1,review(),600,1000);
        assertEquals(new Receipt(Code.OK,AdministratorConfiguration.CALENDAR_ID,2),receipt);
        assertEquals(List.of("expiry","attempt","save-calendar","effects"),f.steps);assertEquals(2,f.calendar.version());
        assertEquals(Operation.REPLACE_WEEKLY,f.operation);assertEquals(2,f.effectVersion);assertEquals(REASON,f.effectReason);assertTrue(f.acknowledged.isEmpty());
        assertSame(session,f.command.session());assertEquals("ConfigurationCommand[REDACTED]",f.command.toString());assertEquals("ConfigurationReview[REDACTED]",review().toString());
        // A day omitted from the map becomes closed.
        f.reset();var monday=new EnumMap<DayOfWeek,List<AvailabilityCalendar.Window>>(DayOfWeek.class);monday.put(DayOfWeek.MONDAY,List.of(new AvailabilityCalendar.Window(540,600)));
        assertTrue(config.replaceWeekly(session,"k2",2,monday,review()).success());assertTrue(f.calendar.weekly().get(DayOfWeek.TUESDAY).isEmpty());
    }

    @Test void staleVersionsAndNoOpsAreExpectedReceiptsWithoutWrites() {
        assertEquals(Receipt.failure(Code.CALENDAR_CHANGED),replace(9,review(),600,1000));
        assertEquals(Receipt.failure(Code.INVALID_CONFIGURATION),config.replaceWeekly(session,"k",1,new EnumMap<>(f.calendar.weekly()),review()));
        assertEquals(Receipt.failure(Code.POLICY_CHANGED),config.updatePolicy(session,"k",9,values(60,3),review()));
        assertEquals(Receipt.failure(Code.INVALID_CONFIGURATION),config.updatePolicy(session,"k",1,new PolicyValues(f.policy.gridMinutes(),f.policy.dailyCapacity(),f.policy.minimumNotice(),f.policy.pendingLifetime(),f.policy.expiryBuffer(),f.policy.customerCutoff()),review()));
        assertEquals(0,f.saves);assertEquals(1,f.calendar.version());
    }

    @Test void futureConflictsNeedTheExactReasonedAcknowledgement() {
        Booking late=futureBooking(8);Booking other=f.pending(Instant.parse("2026-01-06T07:00:00Z"));
        f.bookings=List.of(late);
        // 09:00 Brussels is outside 10:00-12:00, so the booking conflicts.
        assertEquals(Receipt.failure(Code.CONFLICT_REVIEW_REQUIRED),replace(1,review(),600,720));
        assertEquals(Receipt.failure(Code.CONFLICT_REVIEW_REQUIRED),replace(1,review(late.id(),other.id()),600,720));
        assertEquals(0,f.saves);
        assertTrue(replace(1,review(late.id()),600,720).success());
        assertEquals(List.of(late.id()),f.acknowledged.stream().map(Conflict::bookingId).toList());assertEquals(1,f.saves);
        // Acknowledging a booking that does not conflict is refused as stale review.
        f.reset();assertEquals(Receipt.failure(Code.CONFLICT_REVIEW_REQUIRED),replace(2,review(late.id()),0,1440));
    }

    @Test void dateExceptionsAreSetReplacedAndRemovedOnlyForTodayOrLaterBrusselsDates() {
        var closed=new AvailabilityCalendar.DateException(AvailabilityCalendar.Mode.CLOSED,List.of());LocalDate today=LocalDate.of(2026,1,1);
        assertEquals(Receipt.failure(Code.INVALID_CONFIGURATION),config.setDateException(session,"k",1,today.minusDays(1),closed,review()));
        assertTrue(config.setDateException(session,"k1",1,today,closed,review()).success());assertEquals(closed,f.calendar.exceptions().get(today));
        var replaced=new AvailabilityCalendar.DateException(AvailabilityCalendar.Mode.REPLACE,List.of(new AvailabilityCalendar.Window(600,700)));
        assertTrue(config.setDateException(session,"k2",2,today,replaced,review()).success());assertEquals(replaced,f.calendar.exceptions().get(today));
        assertEquals(Receipt.failure(Code.INVALID_CONFIGURATION),config.removeDateException(session,"k3",3,today.plusDays(1),review()));
        assertTrue(config.removeDateException(session,"k4",3,today,review()).success());assertTrue(f.calendar.exceptions().isEmpty());
        assertThrows(DomainFailure.class,()->config.setDateException(session,"k",4,null,closed,review()));
        assertThrows(DomainFailure.class,()->config.removeDateException(session,"k",4,null,review()));
    }

    @Test void blackoutsAreAddedOnceRemovedByIdAndConflictWithFutureBookings() {
        Booking booking=futureBooking(8);UUID id=UUID.randomUUID();Instant start=booking.start();
        var blackout=new AvailabilityCalendar.Blackout(id,new ReservationInterval(start.plusSeconds(60),start.plusSeconds(600)),"Synthetic closure reason");
        assertEquals(Receipt.failure(Code.CONFLICT_REVIEW_REQUIRED),config.addBlackout(session,"k",1,blackout,review()));
        assertTrue(config.addBlackout(session,"k",1,blackout,review(booking.id())).success());assertEquals(Set.of(Reason.HARD_BLACKOUT),f.acknowledged.getFirst().reasons());
        assertEquals(Receipt.failure(Code.INVALID_CONFIGURATION),config.addBlackout(session,"dup",2,blackout,review()));
        var past=new AvailabilityCalendar.Blackout(UUID.randomUUID(),new ReservationInterval(f.now.minusSeconds(600),f.now),"Synthetic closure reason");
        assertEquals(Receipt.failure(Code.INVALID_CONFIGURATION),config.addBlackout(session,"past",2,past,review()));
        assertEquals(Receipt.failure(Code.INVALID_CONFIGURATION),config.removeBlackout(session,"unknown",2,UUID.randomUUID(),review()));
        f.bookings=List.of();assertTrue(config.removeBlackout(session,"rm",2,id,review()).success());assertTrue(f.calendar.blackouts().isEmpty());
        assertThrows(DomainFailure.class,()->config.removeBlackout(session,"rm2",3,null,review()));
        assertThrows(NullPointerException.class,()->config.addBlackout(session,"k",3,null,review()));
    }

    @Test void policyEditsAppendTheNextVersionAndLoweredCapacityConflictsNeedAcknowledgement() {
        Booking a=futureBooking(8);f.now=f.now.plusSeconds(1);Booking b=f.pending(Instant.parse("2026-01-05T11:00:00Z"));f.now=f.now.minusSeconds(1);f.bookings=List.of(a,b);
        assertEquals(Receipt.failure(Code.CONFLICT_REVIEW_REQUIRED),config.updatePolicy(session,"k",1,values(30,1),review()));
        assertTrue(config.updatePolicy(session,"k",1,values(30,1),review(b.id())).success());
        assertEquals(2,f.policy.version());assertEquals(Operation.UPDATE_POLICY,f.operation);assertEquals(1,f.policy.dailyCapacity());
        assertEquals(new Receipt(Code.OK,AdministratorConfiguration.POLICY_ID,3),config.updatePolicy(session,"k2",2,values(30,2),review()));
        assertThrows(DomainFailure.class,()->config.updatePolicy(session,"k",3,values(0,1),review()));
        assertThrows(DomainFailure.class,()->config.updatePolicy(session,"k",0,values(30,1),review()));
        assertThrows(DomainFailure.class,()->new Review("short",Set.of()));
    }

    @Test void requestFingerprintsCoverEveryFieldAndUnexpectedFailuresPropagate() {
        Set<String> seen=new HashSet<>();
        for(Runnable call:List.<Runnable>of(()->replace(1,review(),600,1000),()->replace(1,review(),600,1001),()->replace(1,new Review("Another synthetic reason",Set.of()),600,1000),
                ()->replace(1,review(UUID.randomUUID()),600,1000),()->replace(2,review(),600,1000),()->config.updatePolicy(session,"synthetic-key",1,values(60,3),review()))) {
            f.reset();f.command=null;call.run();assertNotNull(f.command);seen.add(f.command.fingerprint());
        }
        assertEquals(6,seen.size());
        f.reset();f.effectFailure=new IllegalStateException("SYNTHETIC");assertSame(f.effectFailure,assertThrows(IllegalStateException.class,()->replace(f.calendar.version(),review(),100,200)));
        assertThrows(NullPointerException.class,()->new AdministratorConfiguration(null));
    }

    PolicyValues values(int grid,int capacity){return new PolicyValues(grid,capacity,Duration.ZERO,Duration.ofDays(400),Duration.ZERO,Duration.ZERO);}

    static class Fixture extends PublicSchedulingTest.Fixture implements ConfigurationUnitOfWork,Configuration {
        Command command;Operation operation;long effectVersion;String effectReason;List<Conflict> acknowledged=List.of();RuntimeException effectFailure;
        final List<String> steps=new ArrayList<>();int saves;
        Fixture(){policy=new SchedulingPolicy(1,30,20,Duration.ZERO,Duration.ofDays(400),Duration.ZERO,Duration.ZERO);}
        void reset(){steps.clear();saves=0;effectFailure=null;acknowledged=List.of();}
        public Receipt execute(Command supplied,Function<Configuration,Receipt> work){command=supplied;steps.clear();return work.apply(this);}
        public List<Booking> duePending(){steps.add("expiry");return List.of();}
        public void expiryEffects(UUID id,long version){throw new AssertionError("nothing is due");}
        public Receipt attempt(Supplier<Receipt> mutation){steps.add("attempt");return mutation.get();}
        public CustomerQuota quota(UUID customer){throw new AssertionError("configuration never reads quotas");}
        public boolean chargeCreated(Booking value){throw new AssertionError("configuration never charges");}
        public void save(Booking value){throw new AssertionError("configuration never saves bookings");}
        public void saveCalendar(AvailabilityCalendar next){steps.add("save-calendar");saves++;assertEquals(calendar.version()+1,next.version());calendar=next;}
        public void savePolicy(SchedulingPolicy next){steps.add("save-policy");saves++;assertEquals(policy.version()+1,next.version());policy=next;}
        public void changedEffects(Operation op,long version,String reason,List<Conflict> conflicts){steps.add("effects");operation=op;effectVersion=version;effectReason=reason;acknowledged=conflicts;if(effectFailure!=null)throw effectFailure;}
    }
}
