package com.purpuragenda.application.port.out;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.domain.booking.Booking;
import java.util.*;
import java.util.function.Function;
import static com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import static com.purpuragenda.domain.DomainFailure.require;

/** Resource-locked note append. Persisted administrator authority and fresh MFA precede receipt replay. */
public interface NotesUnitOfWork {
    Receipt append(Command command,Function<Notes,Receipt> work);
    record Command(SessionBinding session,UUID bookingId,String key,String fingerprint) {
        public Command {
            Objects.requireNonNull(session);require(bookingId!=null,"INVALID_NOTE_COMMAND");
            new CommandKey(new Scope(session.sessionId(),"ADD_NOTE",bookingId),key,fingerprint);
        }
        @Override public String toString() { return "NoteCommand[REDACTED]"; }
    }
    interface Notes extends Transaction {
        /** Lock and load the target after expiry reclamation; missing targets fail NOT_FOUND. */
        Booking authorizedBooking();
        /** Append exactly one note and NOTE_ADDED audit using the persisted actor and captured time, without outbox. */
        void noteAdded(UUID id,String text);
    }
}
