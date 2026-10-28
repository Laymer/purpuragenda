package com.purpuragenda.application.scheduling;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.NotesUnitOfWork;
import com.purpuragenda.application.port.out.NotesUnitOfWork.Command;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.domain.customer.Input;
import java.util.*;
import static com.purpuragenda.domain.DomainFailure.require;

/** Private notes are independent administrator facts, never booking snapshots or notification content. */
public final class AdministratorNotes {
    private final NotesUnitOfWork unit;
    public AdministratorNotes(NotesUnitOfWork unit) { this.unit=Objects.requireNonNull(unit); }
    public record Addition(String text) {
        public Addition { text=Input.multiline(text,4000);require(!text.isEmpty() && text.codePoints().noneMatch(c -> c>=0xd800 && c<=0xdfff),"INVALID_NOTE"); }
        @Override public String toString() { return "NoteAddition[REDACTED]"; }
    }
    /** The receipt names the note at version one; adding a note does not change the booking version. */
    public Receipt append(SessionBinding session,UUID bookingId,String key,Addition request) {
        Objects.requireNonNull(request);
        String fingerprint=CommandKey.fingerprint(List.of(request.text()));
        return unit.append(new Command(session,bookingId,key,fingerprint),tx -> SchedulingCommands.within(tx,() -> {
            tx.authorizedBooking();UUID id=UUID.randomUUID();tx.noteAdded(id,request.text());return new Receipt(Code.OK,id,1);
        }));
    }
}
