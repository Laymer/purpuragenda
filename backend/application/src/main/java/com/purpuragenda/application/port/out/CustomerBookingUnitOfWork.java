package com.purpuragenda.application.port.out;

import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.customer.Input;
import java.util.*;
import java.util.function.Function;
import static com.purpuragenda.domain.DomainFailure.require;
import static com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;

/** One scheduling transaction, including persisted session/grant checks before receipt lookup. */
public interface CustomerBookingUnitOfWork {
    /** The adapter derives the idempotency actor from current persisted authority, never request fields. */
    Receipt create(Command command,Function<Creation,Receipt> work);
    record SessionBinding(UUID sessionId,String browserHash) {
        public SessionBinding {
            require(sessionId!=null && browserHash!=null && browserHash.matches("[a-f0-9]{64}"),"INVALID_SESSION_REFERENCE");
        }
        @Override public String toString() { return "SessionBinding[REDACTED]"; }
    }
    record Command(SessionBinding session,String key,String fingerprint) {
        public Command {
            Objects.requireNonNull(session);
            // Validation only. The persisted scope must use the locked grant/account identity.
            new CommandKey(new Scope(session.sessionId(),"CREATE_BOOKING",null),key,fingerprint);
        }
        @Override public String toString() { return "CustomerCreationCommand[REDACTED]"; }
    }
    record Customer(UUID id,String verifiedEmail) {
        public Customer { require(id!=null,"INVALID_CUSTOMER_AUTHORITY");verifiedEmail=Input.email(verifiedEmail); }
        @Override public String toString() { return "CustomerAuthority[REDACTED]"; }
    }
    interface Creation extends Transaction {
        Customer customer();
        /** Consume the original creation grant and bind its derived exact-booking scope; accounts need no grant. */
        void bindCreation(Booking booking);
        /** Persist the authenticated customer's audit and outbox event in this transaction. */
        void createdEffects(Booking booking);
    }
}
