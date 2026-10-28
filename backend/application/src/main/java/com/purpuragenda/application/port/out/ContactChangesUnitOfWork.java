package com.purpuragenda.application.port.out;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.customer.Input;
import java.util.*;
import java.util.function.Function;
import static com.purpuragenda.domain.DomainFailure.require;
import static com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;

/** Exact-booking authority is checked under lock before proof issuance and before receipt replay. */
public interface ContactChangesUnitOfWork {
    /** Atomically authorize the current session and persist a booking-scoped CONTACT challenge with encrypted delivery. */
    void request(ProofRequest request);
    /** One resource lock, captured database time and transaction, including challenge consumption and effects. */
    Receipt change(Command command,Function<Change,Receipt> work);
    record ProofRequest(SessionBinding session,UUID bookingId,String email) {
        public ProofRequest { Objects.requireNonNull(session);require(bookingId!=null,"INVALID_CONTACT_CHANGE");email=Input.email(email); }
        @Override public String toString() { return "ContactProofRequest[REDACTED]"; }
    }
    record Command(SessionBinding session,UUID bookingId,String token,String key,String fingerprint) {
        public Command {
            Objects.requireNonNull(session);require(bookingId!=null && token!=null && token.matches("[A-Za-z0-9_-]{43}"),"INVALID_CONTACT_CHANGE");
            new CommandKey(new Scope(session.sessionId(),"CHANGE_CONTACT",bookingId),key,fingerprint);
        }
        @Override public String toString() { return "ContactChangeCommand[REDACTED]"; }
    }
    interface Change extends Transaction {
        Booking authorizedBooking();
        /** New address from the locked, unconsumed, unexpired CONTACT proof for this exact booking; never request text. */
        String verifiedEmail();
        /** Consume the proof, invalidate older contact proofs and persist audit plus durable old/new-contact notices atomically. */
        void contactChangedEffects(Booking original,Booking changed);
    }
}
