package com.purpuragenda.application;

import com.purpuragenda.application.port.out.BookingChangesUnitOfWork;
import com.purpuragenda.application.port.out.BookingChangesUnitOfWork.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.*;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.scheduling.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CustomerBookingChangesTest {
    final Fixture f=new Fixture();
    final CustomerBookingChanges changes=new CustomerBookingChanges(f);
    final SessionBinding session=new SessionBinding(UUID.randomUUID(),"b".repeat(64));
    final Instant target=Instant.parse("2026-01-04T08:00:00Z");
    Receipt cancel(long version){return changes.cancel(session,f.current.id(),"synthetic-change",new CustomerBookingChanges.Cancellation(version));}
    Receipt move(long version,long policy,Instant start){return changes.reschedule(session,f.current.id(),"synthetic-change",new CustomerBookingChanges.Reschedule(version,policy,start));}
    Receipt move(Instant start){return move(f.current.version(),f.policy.version(),start);}
    void unchanged(Code code,Instant start){Booking original=f.current;assertEquals(Receipt.failure(code),move(start));assertSame(original,f.current);assertEquals(0,f.mutationSaves);assertEquals(0,f.effects);}
    void alwaysOpen(){Map<DayOfWeek,List<AvailabilityCalendar.Window>> days=new EnumMap<>(DayOfWeek.class);for(var day:DayOfWeek.values())days.put(day,List.of(new AvailabilityCalendar.Window(0,1440)));f.calendar=new AvailabilityCalendar(days,Map.of(),List.of(),1);}
    SchedulingPolicy policy(long version,int capacity,Duration notice,Duration buffer,Duration cutoff){return new SchedulingPolicy(version,30,capacity,notice,Duration.ofDays(1),buffer,cutoff);}

    @Test void cancellationPreservesSnapshotsContactMeetingAndIndependentFactsBeforeEffects() {
        f.current=f.current.deliveryContact("delivery@example.test");Booking original=f.current;
        Receipt receipt=cancel(original.version());Booking cancelled=f.current;
        assertEquals(new Receipt(Code.OK,original.id(),original.version()+1),receipt);
        assertEquals(Booking.State.CANCELLED,cancelled.state());assertSame(original.snapshot(),cancelled.snapshot());
        assertEquals(original.deliveryEmail(),cancelled.deliveryEmail());assertEquals(original.reference(),cancelled.reference());
        assertEquals(original.customerId(),cancelled.customerId());assertEquals(original.createdAt(),cancelled.createdAt());
        assertEquals(original.meeting(),cancelled.meeting());assertEquals(original.confirmationBasis(),cancelled.confirmationBasis());
        assertEquals(original.calendarRevision()+1,cancelled.calendarRevision());assertTrue(cancelled.previouslyConfirmed());
        assertEquals(List.of("expiry","attempt","authorized","save","cancel-effects"),f.steps);
        assertSame(original,f.effectOriginal);assertSame(cancelled,f.effectChanged);assertEquals(0,f.policyReads);
        assertSame(session,f.command.session());assertEquals(original.id(),f.command.bookingId());assertEquals(Operation.CANCEL,f.command.operation());
    }

    @Test void originalCustomerCutoffIsInclusiveForBothPendingAndConfirmedCancellation() {
        Instant start=f.current.start();
        for(boolean pending:List.of(false,true))for(long micros:new long[]{-1,0,1}) {
            f.now=start.minus(Duration.ofHours(36));f.reset(pending?f.pending(start):f.confirmed(start));Booking original=f.current;
            f.now=start.minus(Duration.ofHours(24)).plusNanos(micros*1000);
            Receipt result=cancel(original.version());
            if(micros<=0){assertTrue(result.success());assertEquals(pending?0:1,f.current.calendarRevision());}
            else {assertEquals(Receipt.failure(Code.CUTOFF_PASSED),result);assertSame(original,f.current);assertEquals(0,f.effects);}
        }
    }

    @Test void staleVersionIsAnExpectedFailureInsideAttemptBeforeMutation() {
        Booking original=f.current;assertEquals(Receipt.failure(Code.STALE_VERSION),cancel(original.version()+1));
        assertEquals(List.of("expiry","attempt","authorized"),f.steps);assertSame(original,f.current);
        assertEquals(Receipt.failure(Code.STALE_VERSION),move(original.version()+1,1,target));assertEquals(0,f.effects);
    }

    @Test void changedAndUnchangedReschedulingUseTheInclusiveOriginalCutoff() {
        Booking original=f.current;
        for(Instant start:List.of(target,original.start()))for(long micros:new long[]{-1,0,1}) {
            f.reset(original);f.now=original.start().minus(Duration.ofHours(24)).plusNanos(micros*1000);
            Receipt result=move(start);
            if(micros<=0)assertTrue(result.success());
            else {assertEquals(Receipt.failure(Code.CUTOFF_PASSED),result);assertSame(original,f.current);assertEquals(0,f.mutationSaves);}
        }
    }

    @Test void terminalBookingsCannotBeCancelledOrMovedEvenToTheSameStart() {
        for(var state:List.of(Booking.State.CANCELLED,Booking.State.REJECTED,Booking.State.EXPIRED,Booking.State.COMPLETED,Booking.State.NO_SHOW)) {
            Booking pending=f.pending(f.current.start());Booking confirmed=f.confirmed(f.current.start());
            Booking terminal=switch(state) {
                case CANCELLED -> confirmed.cancel(f.now,false);case REJECTED -> pending.reject();case EXPIRED -> pending.expire(pending.expiresAt());
                default -> confirmed.outcome(confirmed.sessionEnd(),state,"");
            };
            f.reset(terminal);assertEquals(Receipt.failure(Code.INVALID_TRANSITION),cancel(terminal.version()));
            unchanged(Code.INVALID_TRANSITION,target);unchanged(Code.INVALID_TRANSITION,terminal.start());
        }
    }

    @Test void pendingExpiryEqualityReclaimsBeforeRefreshingTheAuthorizedBooking() {
        Instant start=f.current.start(),created=f.now;
        for(long micros:new long[]{-1,0,1}) {
            f.now=created;f.reset(f.pending(start));Booking original=f.current;f.now=original.expiresAt().plusNanos(micros*1000);
            Receipt result=cancel(original.version());
            if(micros<0)assertTrue(result.success());
            else {
                assertEquals(Receipt.failure(Code.STALE_VERSION),result);assertEquals(Booking.State.EXPIRED,f.current.state());
                assertEquals(List.of("expiry","reclaim","expiry-effects","attempt","authorized"),f.steps);
                unchanged(Code.INVALID_TRANSITION,start);assertEquals(1,f.expiryEffects);
            }
        }
    }

    @Test void reschedulingPreservesAllSnapshotsApprovalAndContactWithoutChargingAgain() {
        f.current=f.current.deliveryContact("delivery@example.test");Booking original=f.current;
        f.requests=3;assertTrue(move(target).success());Booking moved=f.current;
        assertEquals(target,moved.start());assertEquals(original.version()+1,moved.version());assertEquals(original.calendarRevision()+1,moved.calendarRevision());
        assertSame(original.snapshot(),moved.snapshot());assertEquals(original.deliveryEmail(),moved.deliveryEmail());
        assertEquals(original.createdAt(),moved.createdAt());assertEquals(original.origin(),moved.origin());assertEquals(original.state(),moved.state());
        assertEquals(original.customerId(),moved.customerId());assertEquals(original.meeting(),moved.meeting());
        assertEquals(original.confirmationBasis(),moved.confirmationBasis());assertEquals(original.expiresAt(),moved.expiresAt());
        assertEquals(Duration.ofHours(2),Duration.between(moved.start(),moved.reservedEnd()));assertEquals(3,f.requests);
        assertSame(original,f.effectOriginal);assertSame(moved,f.effectChanged);assertEquals(original.customerId(),f.quotaCustomer);
        assertEquals(List.of("expiry","attempt","authorized","quota","save","reschedule-effects"),f.steps);
    }

    @Test void unchangedTimePreservesVersionAndIgnoresNewPolicyCalendarAndQuotas() {
        f.policy=policy(2,0,Duration.ofDays(40),Duration.ofHours(2),Duration.ofDays(10));
        f.calendar=new AvailabilityCalendar(Map.of(),Map.of(),List.of(),2);f.active=4;f.requests=3;
        for(boolean pending:List.of(false,true)) {
            Booking original=f.current;
            if(pending){f.policy=SchedulingPolicy.defaults();original=f.pending(original.start());f.policy=policy(2,0,Duration.ofDays(40),Duration.ZERO,Duration.ofDays(10));}
            f.reset(original);assertEquals(new Receipt(Code.OK,original.id(),original.version()),move(original.version(),1,original.start()));
            assertSame(original,f.current);assertEquals(0,f.mutationSaves);assertEquals(0,f.effects);
            assertEquals(0,f.policyReads);assertEquals(0,f.calendarReads);assertEquals(0,f.quotaReads);
        }
    }

    @Test void unchangedTimeStillRequiresCurrentVersionOriginalStateAndOriginalCutoff() {
        Booking original=f.current;assertEquals(Receipt.failure(Code.STALE_VERSION),move(2,1,original.start()));
        f.now=original.start().minus(Duration.ofHours(24)).plusNanos(1000);unchanged(Code.CUTOFF_PASSED,original.start());
        f.policy=policy(2,3,Duration.ZERO,Duration.ZERO,Duration.ZERO);f.reset(f.confirmed(original.start().plusSeconds(3600)));
        f.now=f.current.start();assertTrue(move(f.current.version(),1,f.current.start()).success());assertEquals(0,f.mutationSaves);
    }

    @Test void changedTargetRequiresCurrentReviewedPolicyButRetainsOriginalCutoff() {
        f.policy=policy(2,3,Duration.ZERO,Duration.ZERO,Duration.ZERO);Booking original=f.current;
        assertEquals(Receipt.failure(Code.POLICY_CHANGED),move(original.version(),1,target));assertSame(original,f.current);
        f.now=original.start().minus(Duration.ofHours(24)).plusNanos(1000);unchanged(Code.CUTOFF_PASSED,target);
        f.reset(f.confirmed(original.start()));f.policy=SchedulingPolicy.defaults();assertTrue(move(target).success());
    }

    @Test void selfIsExcludedFromDailyOccupancyAndActiveQuotaAndSubmissionLimitIsNeverReapplied() {
        f.policy=policy(2,1,Duration.ZERO,Duration.ZERO,Duration.ZERO);f.active=3;f.requests=3;
        Instant changed=f.current.start().plusSeconds(1800);assertTrue(move(changed).success());
        assertEquals(3,f.requests);assertEquals(1,f.mutationSaves);assertEquals(1,f.effects);
        f.reset(f.current);f.active=4;unchanged(Code.ACTIVE_LIMIT_REACHED,changed.plusSeconds(1800));
    }

    @Test void fullCrossMidnightOccupancyIncludesNextDateAndExactAdjacencyRemainsAvailable() {
        alwaysOpen();Instant late=Instant.parse("2026-01-04T22:30:00Z");
        f.bookings=List.of(f.confirmed(Instant.parse("2026-01-05T00:00:00Z")));unchanged(Code.SLOT_UNAVAILABLE,late);
        assertEquals(late.plusSeconds(7200),f.loaded.end());
        f.bookings=List.of(f.confirmed(late.plusSeconds(7200)));assertTrue(move(late).success());
        f.reset(f.current);Instant midnight=Instant.parse("2026-01-06T23:00:00Z");
        f.bookings=List.of(f.confirmed(midnight.minusSeconds(3600)));unchanged(Code.SLOT_UNAVAILABLE,midnight);
        assertEquals(midnight,f.loaded.start());
        f.bookings=List.of(f.confirmed(midnight.minusSeconds(7200)));assertTrue(move(midnight).success());
    }

    @Test void completedAndNoShowStillConsumeDailyCapacityWhileReleasedStatesDoNot() {
        f.now=Instant.parse("2026-01-04T10:00:00Z");f.policy=policy(2,1,Duration.ZERO,Duration.ZERO,Duration.ZERO);
        f.reset(f.confirmed(f.now.plus(Duration.ofDays(2))));Instant later=Instant.parse("2026-01-04T18:00:00Z");
        for(var state:List.of(Booking.State.COMPLETED,Booking.State.NO_SHOW)) {
            Booking earlier=f.confirmed(f.now.minusSeconds(7200));f.bookings=List.of(earlier.outcome(f.now,state,""));
            unchanged(Code.DAILY_CAPACITY_REACHED,later);
        }
        Booking released=f.confirmed(later).cancel(f.now,false);f.bookings=List.of(released);assertTrue(move(later).success());
    }

    @Test void pendingMoveNeverExtendsExpiryAndUsesOriginalSnapshotBuffer() {
        alwaysOpen();Instant start=f.current.start();f.reset(f.pending(start));Booking original=f.current;
        f.policy=policy(2,3,Duration.ZERO,Duration.ZERO,Duration.ZERO);assertTrue(move(target).success());
        assertEquals(original.expiresAt(),f.current.expiresAt());assertEquals(0,f.current.calendarRevision());
        f.reset(original);Instant early=f.now.plusSeconds(7200);assertTrue(move(early).success());
        assertEquals(early.minusSeconds(3600),f.current.expiresAt());assertSame(original.snapshot(),f.current.snapshot());
    }

    @Test void movingPendingExpiryMustRemainStrictlyAfterCapturedNow() {
        alwaysOpen();f.reset(f.pending(f.current.start()));Booking original=f.current;Instant early=f.now.plusSeconds(7200);
        f.policy=policy(2,3,Duration.ZERO,Duration.ZERO,Duration.ZERO);f.now=early.minusSeconds(3600);
        unchanged(Code.IMPOSSIBLE_PENDING_HOLD,early);f.now=f.now.minusNanos(1000);
        assertTrue(move(early).success());assertEquals(f.now.plusNanos(1000),f.current.expiresAt());assertSame(original.snapshot(),f.current.snapshot());
    }

    @Test void targetNoticeAndMonthEndHorizonUseCurrentPolicyAndInclusiveBoundaries() {
        f.now=target.minus(Duration.ofHours(24)).plusNanos(1000);f.reset(f.confirmed(target.plus(Duration.ofDays(2))));
        unchanged(Code.MINIMUM_NOTICE,target);f.now=f.now.minusNanos(1000);assertTrue(move(target).success());
        f.now=Instant.parse("2026-01-31T10:00:00Z").minusNanos(1000);f.reset(f.confirmed(Instant.parse("2026-02-02T10:00:00Z")));
        Instant monthEnd=Instant.parse("2026-02-28T10:00:00Z");unchanged(Code.OUTSIDE_HORIZON,monthEnd);
        f.now=f.now.plusNanos(1000);assertTrue(move(monthEnd).success());
    }

    @Test void dstTransitionUsesElapsedReservationAndTheShortBrusselsBusinessDay() {
        f.now=Instant.parse("2026-03-27T00:00:00Z");f.reset(f.confirmed(Instant.parse("2026-03-30T08:00:00Z")));alwaysOpen();
        Instant start=Instant.parse("2026-03-29T00:30:00Z");f.bookings=List.of(f.confirmed(start.plusSeconds(5400)));
        unchanged(Code.SLOT_UNAVAILABLE,start);assertEquals(Duration.ofHours(23),Duration.between(f.loaded.start(),f.loaded.end()));
        f.bookings=List.of(f.confirmed(start.plusSeconds(7200)));assertTrue(move(start).success());
        assertEquals(4,f.current.reservedEnd().atZone(SchedulingPolicy.BUSINESS_ZONE).getHour());
    }

    @Test void customerCannotOverrideCurrentCalendarOrFullReservationBlackouts() {
        unchanged(Code.START_NOT_AVAILABLE,target.plusSeconds(60));
        var blackout=new AvailabilityCalendar.Blackout(UUID.randomUUID(),new ReservationInterval(target.plusSeconds(5400),target.plusSeconds(7200)),"Synthetic blackout");
        f.calendar=new AvailabilityCalendar(AvailabilityCalendar.defaults().weekly(),Map.of(),List.of(blackout),2);unchanged(Code.HARD_BLACKOUT,target);
        f.calendar=new AvailabilityCalendar(Map.of(),Map.of(),List.of(),3);unchanged(Code.START_NOT_AVAILABLE,target);
        unchanged(Code.START_NOT_FUTURE,f.now);
    }

    @Test void unrelatedExpiryReclamationPrecedesFailedMoveWithoutTouchingOriginalReservation() {
        Booking due=f.pending(f.current.start().plus(Duration.ofDays(1)));f.bookings=List.of(due);f.now=due.expiresAt();
        Booking original=f.current;unchanged(Code.START_NOT_AVAILABLE,target.plusSeconds(60));
        assertSame(original,f.current);assertEquals(Booking.State.EXPIRED,f.bookings.getFirst().state());assertEquals(1,f.expiryEffects);
        assertTrue(f.steps.indexOf("expiry-effects")<f.steps.indexOf("attempt"));
    }

    @Test void fingerprintsBindVersionPolicyAndTargetAndReplayCannotRepeatMutation() {
        f.replay=new Receipt(Code.OK,f.current.id(),7);assertSame(f.replay,move(1,1,target));String original=f.command.fingerprint();
        assertEquals(Operation.RESCHEDULE,f.command.operation());assertEquals("synthetic-change",f.command.key());
        for(var request:List.of(new CustomerBookingChanges.Reschedule(2,1,target),new CustomerBookingChanges.Reschedule(1,2,target),new CustomerBookingChanges.Reschedule(1,1,target.plusSeconds(1800)))) {
            changes.reschedule(session,f.current.id(),"synthetic-change",request);assertNotEquals(original,f.command.fingerprint());
        }
        move(1,1,Instant.parse("2026-01-04T08:00:00.000000Z"));assertEquals(original,f.command.fingerprint());
        assertSame(f.replay,cancel(1));String cancelled=f.command.fingerprint();cancel(2);assertNotEquals(cancelled,f.command.fingerprint());
        assertEquals(Operation.CANCEL,f.command.operation());assertTrue(f.steps.isEmpty());assertEquals(0,f.mutationSaves);
        assertEquals("BookingChangeCommand[REDACTED]",f.command.toString());
        assertEquals("CustomerCancellation[REDACTED]",new CustomerBookingChanges.Cancellation(1).toString());
        assertEquals("CustomerReschedule[REDACTED]",new CustomerBookingChanges.Reschedule(1,1,target).toString());
    }

    @Test void malformedRequestsAndReferencesNeverReachTheTransaction() {
        assertThrows(NullPointerException.class,()->new CustomerBookingChanges(null));
        assertThrows(DomainFailure.class,()->new CustomerBookingChanges.Cancellation(0));
        assertThrows(DomainFailure.class,()->new CustomerBookingChanges.Reschedule(0,1,target));
        assertThrows(DomainFailure.class,()->new CustomerBookingChanges.Reschedule(1,0,target));
        assertThrows(DomainFailure.class,()->new CustomerBookingChanges.Reschedule(1,1,null));
        assertThrows(DomainFailure.class,()->new CustomerBookingChanges.Reschedule(1,1,target.plusNanos(1)));
        for(Instant invalid:List.of(Instant.MIN,Instant.MAX,Instant.MAX.truncatedTo(java.time.temporal.ChronoUnit.MICROS)))
            assertThrows(DomainFailure.class,()->new CustomerBookingChanges.Reschedule(1,1,invalid));
        var request=new CustomerBookingChanges.Cancellation(1);
        assertThrows(NullPointerException.class,()->changes.cancel(null,f.current.id(),"key",request));
        assertThrows(DomainFailure.class,()->changes.cancel(session,null,"key",request));
        assertThrows(DomainFailure.class,()->changes.cancel(session,f.current.id()," ",request));
        assertThrows(NullPointerException.class,()->changes.cancel(session,f.current.id(),"key",null));
        assertThrows(NullPointerException.class,()->changes.reschedule(session,f.current.id(),"key",null));
        assertThrows(DomainFailure.class,()->new Command(session,f.current.id(),null,"key","b".repeat(64)));
        assertThrows(DomainFailure.class,()->new Command(session,f.current.id(),Operation.CANCEL,"key","invalid"));assertNull(f.command);
    }

    @Test void unexpectedEffectFailuresPropagateToTheTransactionInsteadOfReturningSuccess() {
        f.effectFailure=new IllegalStateException("SYNTHETIC_EFFECT_FAILURE");Booking original=f.current;
        assertSame(f.effectFailure,assertThrows(IllegalStateException.class,()->cancel(original.version())));
        assertEquals(1,f.mutationSaves);assertSame(original,f.effectOriginal);
        f.reset(original);assertSame(f.effectFailure,assertThrows(IllegalStateException.class,()->move(target)));
        assertEquals(1,f.mutationSaves);assertSame(original,f.effectOriginal);
    }

    static class Fixture extends PublicSchedulingTest.Fixture implements BookingChangesUnitOfWork,Change {
        Booking current,effectOriginal,effectChanged;Command command;Receipt replay;RuntimeException effectFailure;
        final List<String> steps=new ArrayList<>();int active=1,requests,mutationSaves,effects,expiryEffects,policyReads,calendarReads,quotaReads;
        boolean attempting;UUID quotaCustomer;
        Fixture(){policy=SchedulingPolicy.defaults();current=confirmed(Instant.parse("2026-01-03T08:00:00Z"));}
        Booking confirmed(Instant start){Booking value=pending(start.isAfter(now)?start:now.plusSeconds(7200));Instant created=now.minus(Duration.ofDays(1));if(!created.isBefore(start))created=start.minus(Duration.ofDays(1));return Booking.confirmed(value.id(),value.reference(),value.customerId(),value.snapshot(),value.origin(),created,start,Meeting.parse("https://zoom.us/j/123456789",Set.of("zoom.us")));}
        void reset(Booking value){current=value;steps.clear();mutationSaves=effects=expiryEffects=policyReads=calendarReads=quotaReads=0;attempting=false;}
        public Receipt change(Command supplied,Function<Change,Receipt> work){command=supplied;return replay==null?work.apply(this):replay;}
        public Booking authorizedBooking(){steps.add("authorized");assertTrue(attempting);return current;}
        public SchedulingPolicy policy(){policyReads++;return policy;}public AvailabilityCalendar calendar(){calendarReads++;return calendar;}
        public List<Booking> bookings(ReservationInterval interval){loaded=interval;var all=new ArrayList<>(bookings);all.add(current);return all.stream().filter(b->b.interval().intersects(interval)).toList();}
        public List<Booking> duePending(){steps.add("expiry");var all=new ArrayList<>(bookings);all.add(current);return all.stream().filter(b->b.state()==Booking.State.PENDING_CONFIRMATION&&!now.isBefore(b.expiresAt())).toList();}
        public Receipt attempt(Supplier<Receipt> mutation){steps.add("attempt");attempting=true;try{return mutation.get();}finally{attempting=false;}}
        public void save(Booking value){steps.add(attempting?"save":"reclaim");if(attempting)mutationSaves++;if(value.id().equals(current.id()))current=value;else bookings=bookings.stream().map(b->b.id().equals(value.id())?value:b).toList();}
        public void expiryEffects(UUID id,long version){steps.add("expiry-effects");expiryEffects++;}
        public CustomerQuota quota(UUID customer){steps.add("quota");quotaReads++;quotaCustomer=customer;return new CustomerQuota(active,requests,CustomerQuota.dayAt(now));}
        public boolean chargeCreated(Booking value){throw new AssertionError("Customer changes must never charge a submission");}
        public void cancelledEffects(Booking original,Booking changed){steps.add("cancel-effects");effect(original,changed);}
        public void rescheduledEffects(Booking original,Booking changed){steps.add("reschedule-effects");effect(original,changed);}
        void effect(Booking original,Booking changed){assertSame(current,changed);effectOriginal=original;effectChanged=changed;effects++;if(effectFailure!=null)throw effectFailure;}
    }
}
