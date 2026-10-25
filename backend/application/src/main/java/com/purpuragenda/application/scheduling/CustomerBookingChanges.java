package com.purpuragenda.application.scheduling;

import com.purpuragenda.application.port.out.BookingChangesUnitOfWork;
import com.purpuragenda.application.port.out.BookingChangesUnitOfWork.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.scheduling.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.purpuragenda.domain.DomainFailure.require;
import static com.purpuragenda.domain.scheduling.SchedulingPolicy.BUSINESS_ZONE;

/** Customer changes only; identity, ownership and receipt replay belong to the authorized transaction. */
public final class CustomerBookingChanges {
    private final BookingChangesUnitOfWork unit;
    public CustomerBookingChanges(BookingChangesUnitOfWork unit) { this.unit=Objects.requireNonNull(unit); }
    public record Cancellation(long expectedVersion) {
        public Cancellation { require(expectedVersion>0,"INVALID_BOOKING_CHANGE"); }
        @Override public String toString() { return "CustomerCancellation[REDACTED]"; }
    }
    public record Reschedule(long expectedVersion,long policyVersion,Instant start) {
        public Reschedule {
            require(expectedVersion>0 && policyVersion>0 && start!=null,"INVALID_BOOKING_CHANGE");
            require(start.equals(start.truncatedTo(ChronoUnit.MICROS)),"INVALID_BOOKING_CHANGE");
            try { start.atZone(BUSINESS_ZONE).toLocalDate().plusDays(1);ReservationInterval.reserve(start); }
            catch(DateTimeException | ArithmeticException invalid) { throw new DomainFailure("INVALID_BOOKING_CHANGE"); }
        }
        @Override public String toString() { return "CustomerReschedule[REDACTED]"; }
    }
    public Receipt cancel(SessionBinding session,UUID bookingId,String key,Cancellation request) {
        Objects.requireNonNull(request);
        String fingerprint=CommandKey.fingerprint(List.of(Long.toString(request.expectedVersion())));
        return unit.change(new Command(session,bookingId,Operation.CANCEL,key,fingerprint),
                tx -> SchedulingCommands.within(tx,() -> {
                    Booking original=current(tx,request.expectedVersion());
                    Booking cancelled=original.cancel(tx.now(),true);
                    tx.save(cancelled);tx.cancelledEffects(original,cancelled);
                    return receipt(cancelled);
                }));
    }
    public Receipt reschedule(SessionBinding session,UUID bookingId,String key,Reschedule request) {
        Objects.requireNonNull(request);
        String fingerprint=CommandKey.fingerprint(List.of(Long.toString(request.expectedVersion()),
                Long.toString(request.policyVersion()),request.start().toString()));
        return unit.change(new Command(session,bookingId,Operation.RESCHEDULE,key,fingerprint),
                tx -> SchedulingCommands.within(tx,() -> reschedule(tx,request)));
    }
    private Receipt reschedule(Change tx,Reschedule request) {
        Booking original=current(tx,request.expectedVersion());Instant now=tx.now();
        require(original.livePending(now) || original.state()==Booking.State.CONFIRMED,"INVALID_TRANSITION");
        require(original.customerMayChange(now),"CUTOFF_PASSED");
        // An unchanged reservation uses only its original terms; no new policy review or effects are needed.
        if(original.start().equals(request.start()))return receipt(original);
        Booking moved=original.reschedule(now,request.start(),true);
        SchedulingPolicy policy=tx.policy();require(policy.version()==request.policyVersion(),"POLICY_CHANGED");
        LocalDate day=request.start().atZone(BUSINESS_ZONE).toLocalDate();
        Instant end=day.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        if(moved.reservedEnd().isAfter(end))end=moved.reservedEnd();
        List<Booking> occupied=tx.bookings(new ReservationInterval(day.atStartOfDay(BUSINESS_ZONE).toInstant(),end))
                .stream().filter(b -> !b.id().equals(original.id()) && b.occupies(now)).toList();
        int daily=(int)occupied.stream().filter(b -> b.businessDate().equals(day)).count();
        // The adapter's active count includes this live booking, which is being moved; the clamp guards a stale zero.
        int otherActive=Math.max(0,tx.quota(original.customerId()).active()-1);
        SchedulingRules.validate(request.start(),now,policy,tx.calendar(),daily,otherActive,0,
                occupied.stream().map(Booking::interval).toList(),SchedulingRules.Override.none());
        tx.save(moved);tx.rescheduledEffects(original,moved);
        return receipt(moved);
    }
    private static Booking current(Change tx,long version) {
        Booking original=tx.authorizedBooking();require(original.version()==version,"STALE_VERSION");return original;
    }
    private static Receipt receipt(Booking booking) { return new Receipt(Code.OK,booking.id(),booking.version()); }
}
