package com.purpuragenda.application.scheduling;

import com.purpuragenda.application.port.out.AdminChangesUnitOfWork;
import com.purpuragenda.application.port.out.AdminChangesUnitOfWork.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.customer.Input;
import com.purpuragenda.domain.scheduling.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import static com.purpuragenda.domain.DomainFailure.require;
import static com.purpuragenda.domain.scheduling.SchedulingPolicy.BUSINESS_ZONE;

/**
 * Administrator rejection, cancellation, rescheduling and outcomes. Every change names the reviewed booking version and
 * an audited reason; terminal bookings never revive, money facts are never altered and overlap is never overridable.
 */
public final class AdministratorBookingChanges {
    private final AdminChangesUnitOfWork unit;
    public AdministratorBookingChanges(AdminChangesUnitOfWork unit) { this.unit=Objects.requireNonNull(unit); }

    public record Rejection(long expectedVersion,String reason) {
        public Rejection { require(expectedVersion>0,"INVALID_BOOKING_CHANGE");reason=Input.reason(reason); }
        @Override public String toString() { return "AdminRejection[REDACTED]"; }
    }
    /** CUSTOMER means the customer asked; it is recorded as LATE_CUSTOMER without a policy refund after the cutoff. */
    public record Cancellation(long expectedVersion,Classification requested,String reason) {
        public Cancellation {
            require(expectedVersion>0 && (requested==Classification.PROVIDER || requested==Classification.CUSTOMER),"INVALID_BOOKING_CHANGE");
            reason=Input.reason(reason);
        }
        @Override public String toString() { return "AdminCancellation[REDACTED]"; }
    }
    public record Reschedule(long expectedVersion,long policyVersion,long calendarVersion,Instant start,String reason,SchedulingRules.Override override) {
        public Reschedule {
            require(expectedVersion>0 && policyVersion>0 && calendarVersion>0 && start!=null && override!=null,"INVALID_BOOKING_CHANGE");
            require(start.equals(start.truncatedTo(ChronoUnit.MICROS)),"INVALID_BOOKING_CHANGE");
            try { start.atZone(BUSINESS_ZONE).toLocalDate().plusDays(1);ReservationInterval.reserve(start); }
            catch(DateTimeException | ArithmeticException invalid) { throw new DomainFailure("INVALID_BOOKING_CHANGE"); }
            require(override.affectedBlackoutIds().isEmpty() || override.rules().contains(SchedulingRules.Rule.HARD_BLACKOUT),"INVALID_BOOKING_CHANGE");
            reason=Input.reason(reason);
        }
        @Override public String toString() { return "AdminReschedule[REDACTED]"; }
    }
    /** A reason is required only when the outcome is recorded before the session has ended. */
    public record Outcome(long expectedVersion,Booking.State outcome,String reason) {
        public Outcome {
            require(expectedVersion>0 && (outcome==Booking.State.COMPLETED || outcome==Booking.State.NO_SHOW),"INVALID_BOOKING_CHANGE");
            if(reason!=null)reason=Input.reason(reason);
        }
        @Override public String toString() { return "AdminOutcome[REDACTED]"; }
    }

    public Receipt reject(SessionBinding session,UUID bookingId,String key,Rejection request) {
        Objects.requireNonNull(request);
        return run(session,bookingId,Operation.REJECT,key,List.of(Long.toString(request.expectedVersion()),request.reason()),tx -> {
            Booking original=current(tx,request.expectedVersion());Booking rejected=original.reject();
            tx.save(rejected);tx.rejectedEffects(original,rejected,request.reason());return receipt(rejected);
        });
    }
    public Receipt cancel(SessionBinding session,UUID bookingId,String key,Cancellation request) {
        Objects.requireNonNull(request);
        return run(session,bookingId,Operation.CANCEL,key,List.of(Long.toString(request.expectedVersion()),request.requested().name(),request.reason()),tx -> {
            Booking original=current(tx,request.expectedVersion());Instant now=tx.now();
            Classification classification=request.requested()==Classification.CUSTOMER && !original.customerMayChange(now)?Classification.LATE_CUSTOMER:request.requested();
            Booking cancelled=original.cancel(now,false);
            tx.save(cancelled);tx.cancelledEffects(original,cancelled,classification,request.reason());return receipt(cancelled);
        });
    }
    public Receipt reschedule(SessionBinding session,UUID bookingId,String key,Reschedule request) {
        Objects.requireNonNull(request);
        List<String> fields=new ArrayList<>(List.of(Long.toString(request.expectedVersion()),Long.toString(request.policyVersion()),
                Long.toString(request.calendarVersion()),request.start().toString(),request.reason()));
        fields.add(request.override().rules().stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
        fields.add(request.override().rules().isEmpty()?"":request.override().reason());
        fields.add(request.override().affectedBlackoutIds().stream().map(UUID::toString).sorted().collect(Collectors.joining(",")));
        return run(session,bookingId,Operation.RESCHEDULE,key,fields,tx -> reschedule(tx,request));
    }
    public Receipt outcome(SessionBinding session,UUID bookingId,String key,Outcome request) {
        Objects.requireNonNull(request);
        return run(session,bookingId,Operation.OUTCOME,key,List.of(Long.toString(request.expectedVersion()),request.outcome().name(),
                request.reason()==null?"-":request.reason()),tx -> {
            Booking original=current(tx,request.expectedVersion());
            Booking recorded=original.outcome(tx.now(),request.outcome(),request.reason());
            tx.save(recorded);tx.outcomeEffects(original,recorded,request.reason());return receipt(recorded);
        });
    }

    private interface Work { Receipt apply(Change tx); }
    private Receipt run(SessionBinding session,UUID bookingId,Operation operation,String key,List<String> fields,Work work) {
        return unit.change(new Command(session,bookingId,operation,key,CommandKey.fingerprint(fields)),
                tx -> SchedulingCommands.within(tx,() -> work.apply(tx)));
    }
    private Receipt reschedule(Change tx,Reschedule request) {
        Booking original=current(tx,request.expectedVersion());Instant now=tx.now();
        require(original.livePending(now) || original.state()==Booking.State.CONFIRMED,"INVALID_TRANSITION");
        require(!original.start().equals(request.start()),"INVALID_BOOKING_CHANGE");
        Booking moved=original.reschedule(now,request.start(),false);
        SchedulingPolicy policy=tx.policy();require(policy.version()==request.policyVersion(),"POLICY_CHANGED");
        AvailabilityCalendar calendar=tx.calendar();require(calendar.version()==request.calendarVersion(),"CALENDAR_CHANGED");
        LocalDate day=request.start().atZone(BUSINESS_ZONE).toLocalDate();
        Instant end=day.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        if(moved.reservedEnd().isAfter(end))end=moved.reservedEnd();
        List<Booking> occupied=tx.bookings(new ReservationInterval(day.atStartOfDay(BUSINESS_ZONE).toInstant(),end))
                .stream().filter(b -> !b.id().equals(original.id()) && b.occupies(now)).toList();
        int daily=(int)occupied.stream().filter(b -> b.businessDate().equals(day)).count();
        // The active count includes this live booking, which is being moved.
        int otherActive=Math.max(0,tx.quota(original.customerId()).active()-1);
        SchedulingRules.validate(request.start(),now,policy,calendar,daily,otherActive,0,occupied.stream().map(Booking::interval).toList(),request.override());
        // Only violated rules and intersecting blackouts may be named, exactly as for administrator creation.
        Set<SchedulingRules.Rule> violated=SchedulingRules.violations(request.start(),now,policy,calendar,daily,otherActive,0);
        Set<UUID> intersecting=calendar.blackouts().stream().filter(b -> b.interval().intersects(ReservationInterval.reserve(request.start())))
                .map(AvailabilityCalendar.Blackout::id).collect(Collectors.toSet());
        require(violated.containsAll(request.override().rules()) && intersecting.containsAll(request.override().affectedBlackoutIds()),"OVERRIDE_UNUSED");
        tx.save(moved);tx.rescheduledEffects(original,moved,request.reason(),request.override());
        return receipt(moved);
    }
    private static Booking current(Change tx,long version) {
        Booking original=tx.target();require(original.version()==version,"STALE_VERSION");return original;
    }
    private static Receipt receipt(Booking booking) { return new Receipt(Code.OK,booking.id(),booking.version()); }
}
