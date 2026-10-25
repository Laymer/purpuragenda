package com.purpuragenda.application.port.out;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.domain.booking.Booking;
import java.util.*;
import java.util.function.Function;
import static com.purpuragenda.domain.DomainFailure.require;
import static com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;

/** One resource-locked transaction; persisted ownership must be checked before receipt replay. */
public interface BookingChangesUnitOfWork {
    Receipt change(Command command,Function<Change,Receipt> work);
    enum Operation { CANCEL, RESCHEDULE }
    record Command(SessionBinding session,UUID bookingId,Operation operation,String key,String fingerprint) {
        public Command {
            Objects.requireNonNull(session);
            require(bookingId!=null && operation!=null,"INVALID_BOOKING_CHANGE");
            // Validation only; the adapter derives the actor from locked persisted authority.
            new CommandKey(new Scope(session.sessionId(),operation.name()+"_BOOKING",bookingId),key,fingerprint);
        }
        @Override public String toString() { return "BookingChangeCommand[REDACTED]"; }
    }
    interface Change extends Transaction {
        /** Current locked target, refreshed after synchronous expiry reclamation. */
        Booking authorizedBooking();
        /** Record customer cancellation refund obligations and audit/outbox; never alter payment facts. */
        void cancelledEffects(Booking original,Booking cancelled);
        /** Record audit/outbox for both audiences, preserving payment facts and the submission charge. */
        void rescheduledEffects(Booking original,Booking moved);
    }
}
