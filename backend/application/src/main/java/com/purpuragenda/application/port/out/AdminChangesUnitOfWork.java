package com.purpuragenda.application.port.out;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.scheduling.SchedulingRules;
import java.util.*;
import java.util.function.Function;
import static com.purpuragenda.domain.DomainFailure.require;
import static com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;

/**
 * One resource-locked administrator change of one booking. The adapter derives the administrator from the persisted
 * enabled, enrolled, current-epoch session with fresh MFA and locks the booking before any receipt replay.
 */
public interface AdminChangesUnitOfWork {
    Receipt change(Command command,Function<Change,Receipt> work);
    enum Operation { REJECT, CANCEL, RESCHEDULE, OUTCOME, MEETING }
    /** PROVIDER and eligible CUSTOMER cancellations refund existing receipts; a late customer cancellation does not. */
    enum Classification { PROVIDER, CUSTOMER, LATE_CUSTOMER }
    record Command(SessionBinding session,UUID bookingId,Operation operation,String key,String fingerprint) {
        public Command {
            Objects.requireNonNull(session);
            require(bookingId!=null && operation!=null,"INVALID_BOOKING_CHANGE");
            // Validation only; the persisted scope uses the locked administrator identity.
            new CommandKey(new Scope(session.sessionId(),"ADMIN_"+operation.name()+"_BOOKING",bookingId),key,fingerprint);
        }
        @Override public String toString() { return "AdminChangeCommand[REDACTED]"; }
    }
    interface Change extends Transaction {
        /** The locked target, read after synchronous expiry reclamation. */
        Booking target();
        /** Refund follow-up for every unrefunded receipt, audit with the reason and both-audience outbox. */
        void rejectedEffects(Booking original,Booking rejected,String reason);
        /** Refund follow-up unless LATE_CUSTOMER, audit with reason and classification, both-audience outbox. */
        void cancelledEffects(Booking original,Booking cancelled,Classification classification,String reason);
        /** Audit with reason, the immutable override record when used, and both-audience outbox; money is untouched. */
        void rescheduledEffects(Booking original,Booking moved,String reason,SchedulingRules.Override override);
        /** True when another booking already holds this participant meeting id. */
        boolean meetingInUse(String meetingId);
        /** Audit with reason and both-audience outbox; a confirmed booking's calendar revision has already advanced. */
        void meetingEffects(Booking original,Booking updated,String reason);
        /** Audit only: outcomes never notify the customer automatically. */
        void outcomeEffects(Booking original,Booking recorded,String reason);
    }
}
