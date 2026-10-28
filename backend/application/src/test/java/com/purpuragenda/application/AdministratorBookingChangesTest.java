package com.purpuragenda.application;

import com.purpuragenda.application.port.out.AdminChangesUnitOfWork;
import com.purpuragenda.application.port.out.AdminChangesUnitOfWork.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.*;
import com.purpuragenda.application.scheduling.AdministratorBookingChanges.*;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.scheduling.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdministratorBookingChangesTest {
    static final String REASON="Synthetic administrative reason";
    final Fixture f=new Fixture();
    final AdministratorBookingChanges changes=new AdministratorBookingChanges(f);
    final SessionBinding session=new SessionBinding(UUID.randomUUID(),"e".repeat(64));
    Booking pending(Instant start) { f.target=f.pending(start);return f.target; }
    Booking confirmed(Instant start) {
        Booking value=f.pending(start).approve(f.now,Meeting.parse("https://zoom.us/j/123456789",Set.of("zoom.us")),Booking.ConfirmationBasis.MANUAL_APPROVAL);
        f.target=value;return value;
    }
    Reschedule move(long version,Instant start,SchedulingRules.Rule... rules) {
        var override=rules.length==0?SchedulingRules.Override.none():new SchedulingRules.Override(Set.of(rules),REASON,Set.of());
        return new Reschedule(version,f.policy.version(),f.calendar.version(),start,REASON,override);
    }

    @Test void rejectionNeedsTheReviewedPendingVersionAndRecordsItsReason() {
        Booking original=pending(Instant.parse("2026-01-05T09:00:00Z"));UUID id=original.id();
        assertEquals(Receipt.failure(Code.STALE_VERSION),changes.reject(session,id,"stale",new Rejection(9,REASON)));
        var receipt=changes.reject(session,id,"reject",new Rejection(1,REASON));
        assertEquals(new Receipt(Code.OK,id,2),receipt);assertEquals(Booking.State.REJECTED,f.saved.state());assertEquals(List.of("expiry","attempt","target","save","rejected"),f.steps);
        assertEquals(REASON,f.reason);assertEquals(Operation.REJECT,f.command.operation());assertEquals(id,f.command.bookingId());assertEquals("AdminRejection[REDACTED]",new Rejection(1,REASON).toString());
        confirmed(Instant.parse("2026-01-05T09:00:00Z"));
        assertEquals(Receipt.failure(Code.INVALID_TRANSITION),changes.reject(session,f.target.id(),"confirmed",new Rejection(2,REASON)));
        assertThrows(DomainFailure.class,()->new Rejection(1,"short"));assertThrows(DomainFailure.class,()->new Rejection(0,REASON));
    }

    @Test void customerCancellationsAfterTheCutoffAreClassifiedLateWhileProviderCancellationsStayProvider() {
        f.policy=new SchedulingPolicy(1,30,20,Duration.ZERO,Duration.ofDays(30),Duration.ZERO,Duration.ofHours(24));
        Booking early=confirmed(Instant.parse("2026-01-05T09:00:00Z"));
        assertTrue(changes.cancel(session,early.id(),"customer",new Cancellation(2,Classification.CUSTOMER,REASON)).success());
        assertEquals(Classification.CUSTOMER,f.classification);assertEquals(Booking.State.CANCELLED,f.saved.state());
        Booking late=confirmed(Instant.parse("2026-01-01T10:00:00Z"));
        assertTrue(changes.cancel(session,late.id(),"late",new Cancellation(2,Classification.CUSTOMER,REASON)).success());assertEquals(Classification.LATE_CUSTOMER,f.classification);
        confirmed(Instant.parse("2026-01-01T10:00:00Z"));
        assertTrue(changes.cancel(session,f.target.id(),"provider",new Cancellation(2,Classification.PROVIDER,REASON)).success());assertEquals(Classification.PROVIDER,f.classification);
        f.target=f.saved;assertEquals(Receipt.failure(Code.INVALID_TRANSITION),changes.cancel(session,f.target.id(),"again",new Cancellation(3,Classification.PROVIDER,REASON)));
        assertThrows(DomainFailure.class,()->new Cancellation(1,Classification.LATE_CUSTOMER,REASON));assertThrows(DomainFailure.class,()->new Cancellation(1,null,REASON));
    }

    @Test void rescheduleRevalidatesTheTargetAndAcceptsOnlyUsedExplicitOverrides() {
        Booking original=confirmed(Instant.parse("2026-01-05T09:00:00Z"));UUID id=original.id();
        var receipt=changes.reschedule(session,id,"move",move(2,Instant.parse("2026-01-06T09:00:00Z")));
        assertEquals(new Receipt(Code.OK,id,3),receipt);assertEquals(Instant.parse("2026-01-06T09:00:00Z"),f.saved.start());assertTrue(f.override.rules().isEmpty());
        // 07:00 Brussels is outside the default 09:00-20:00 windows.
        Instant early=Instant.parse("2026-01-06T06:00:00Z");
        assertEquals(Receipt.failure(Code.START_NOT_AVAILABLE),changes.reschedule(session,id,"early",move(2,early)));
        assertEquals(Receipt.failure(Code.OVERRIDE_UNUSED),changes.reschedule(session,id,"unused",move(2,Instant.parse("2026-01-07T09:00:00Z"),SchedulingRules.Rule.START_RULES)));
        assertTrue(changes.reschedule(session,id,"override",move(2,early,SchedulingRules.Rule.START_RULES)).success());assertEquals(Set.of(SchedulingRules.Rule.START_RULES),f.override.rules());
        f.bookings=List.of(f.pending(Instant.parse("2026-01-08T09:00:00Z")));
        assertEquals(Receipt.failure(Code.SLOT_UNAVAILABLE),changes.reschedule(session,id,"overlap",move(2,Instant.parse("2026-01-08T10:00:00Z"),SchedulingRules.Rule.START_RULES)));
        assertEquals(Receipt.failure(Code.POLICY_CHANGED),changes.reschedule(session,id,"policy",new Reschedule(2,9,1,Instant.parse("2026-01-09T09:00:00Z"),REASON,SchedulingRules.Override.none())));
        assertEquals(Receipt.failure(Code.CALENDAR_CHANGED),changes.reschedule(session,id,"calendar",new Reschedule(2,1,9,Instant.parse("2026-01-09T09:00:00Z"),REASON,SchedulingRules.Override.none())));
        assertThrows(DomainFailure.class,()->changes.reschedule(session,id,"same",move(2,original.start())));
        assertThrows(DomainFailure.class,()->new Reschedule(2,1,1,Instant.parse("2026-01-09T09:00:00.0000001Z"),REASON,SchedulingRules.Override.none()));
        assertThrows(DomainFailure.class,()->new Reschedule(2,1,1,early,REASON,new SchedulingRules.Override(Set.of(SchedulingRules.Rule.START_RULES),REASON,Set.of(UUID.randomUUID()))));
        f.target=original.cancel(f.now,false);
        assertEquals(Receipt.failure(Code.INVALID_TRANSITION),changes.reschedule(session,id,"terminal",move(3,Instant.parse("2026-01-09T09:00:00Z"))));
    }

    @Test void outcomesNeedAStartedConfirmedSessionAndAReasonOnlyWhenEarly() {
        Booking original=confirmed(Instant.parse("2026-01-02T09:00:00Z"));UUID id=original.id();
        assertEquals(Receipt.failure(Code.SESSION_NOT_STARTED),changes.outcome(session,id,"before",new Outcome(2,Booking.State.COMPLETED,null)));
        f.now=Instant.parse("2026-01-02T09:30:00Z");
        assertThrows(DomainFailure.class,()->changes.outcome(session,id,"early",new Outcome(2,Booking.State.NO_SHOW,null)));
        assertTrue(changes.outcome(session,id,"early-reason",new Outcome(2,Booking.State.NO_SHOW,REASON)).success());
        assertEquals(Booking.State.NO_SHOW,f.saved.state());assertEquals(REASON,f.reason);assertEquals(List.of("expiry","attempt","target","save","outcome"),f.steps);
        f.now=Instant.parse("2026-01-02T12:00:00Z");
        assertTrue(changes.outcome(session,id,"after",new Outcome(2,Booking.State.COMPLETED,null)).success());assertNull(f.reason);
        assertThrows(DomainFailure.class,()->new Outcome(2,Booking.State.CANCELLED,null));
        pending(Instant.parse("2026-01-02T13:00:00Z"));
        assertEquals(Receipt.failure(Code.INVALID_TRANSITION),changes.outcome(session,f.target.id(),"pending",new Outcome(1,Booking.State.COMPLETED,null)));
    }

    static class Fixture extends PublicSchedulingTest.Fixture implements AdminChangesUnitOfWork,Change {
        Booking target,saved;Command command;String reason;Classification classification;SchedulingRules.Override override;
        final List<String> steps=new ArrayList<>();
        Fixture(){policy=new SchedulingPolicy(1,30,20,Duration.ZERO,Duration.ofDays(30),Duration.ZERO,Duration.ZERO);}
        public Receipt change(Command supplied,Function<Change,Receipt> work){command=supplied;steps.clear();reason=null;return work.apply(this);}
        public List<Booking> duePending(){steps.add("expiry");return List.of();}
        public void expiryEffects(UUID id,long version){throw new AssertionError("nothing is due");}
        public Receipt attempt(Supplier<Receipt> mutation){steps.add("attempt");return mutation.get();}
        public CustomerQuota quota(UUID customer){return new CustomerQuota(1,0,CustomerQuota.dayAt(now));}
        public boolean chargeCreated(Booking value){throw new AssertionError("administrator changes never charge");}
        public Booking target(){steps.add("target");return target;}
        public void save(Booking value){steps.add("save");saved=value;}
        public void rejectedEffects(Booking original,Booking rejected,String supplied){steps.add("rejected");assertSame(saved,rejected);reason=supplied;}
        public void cancelledEffects(Booking original,Booking cancelled,Classification supplied,String text){steps.add("cancelled");assertSame(saved,cancelled);classification=supplied;reason=text;}
        public void rescheduledEffects(Booking original,Booking moved,String text,SchedulingRules.Override supplied){steps.add("rescheduled");assertSame(saved,moved);reason=text;override=supplied;}
        public void outcomeEffects(Booking original,Booking recorded,String text){steps.add("outcome");assertSame(saved,recorded);reason=text;}
    }
}
