package com.purpuragenda.application.port.out;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.Customer;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.scheduling.SchedulingRules;
import java.util.*;
import java.util.function.Function;
import static com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;

/** One resource-locked transaction; persisted administrator authority is rechecked before receipt lookup. */
public interface AdminBookingUnitOfWork {
    /** The adapter derives the actor from the current enabled, enrolled, freshly MFA-authenticated administrator session. */
    Receipt create(Command command,Function<Creation,Receipt> work);
    record Command(SessionBinding session,String key,String fingerprint) {
        public Command {
            Objects.requireNonNull(session);
            // Validation only. The persisted scope uses the locked administrator identity.
            new CommandKey(new Scope(session.sessionId(),"ADMIN_CREATE_BOOKING",null),key,fingerprint);
        }
        @Override public String toString() { return "AdminCreationCommand[REDACTED]"; }
    }
    interface Creation extends Transaction {
        /** Find or create the stable customer identity for a normalized email; rolls back with a failed attempt. */
        Customer customer(String email);
        /** True when another booking already holds this participant meeting id (unique across all bookings). */
        boolean meetingInUse(String meetingId);
        /** Persist audit, outbox and (when an override was used) the immutable override record for the saved booking. */
        void createdEffects(Booking booking,SchedulingRules.Override override);
    }
}
