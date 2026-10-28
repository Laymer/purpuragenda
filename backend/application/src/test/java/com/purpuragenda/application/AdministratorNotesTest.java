package com.purpuragenda.application;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.NotesUnitOfWork;
import com.purpuragenda.application.port.out.NotesUnitOfWork.*;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.AdministratorNotes;
import com.purpuragenda.application.scheduling.AdministratorNotes.Addition;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.Booking;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdministratorNotesTest {
    final Fixture f=new Fixture();
    final SessionBinding session=new SessionBinding(UUID.randomUUID(),"b".repeat(64));
    final AdministratorNotes notes=new AdministratorNotes(f);
    Receipt append(String text){return notes.append(session,f.current.id(),"synthetic-note",new Addition(text));}

    @Test void appendsOnePrivateFactWithoutChangingTheBookingOrSchedulingFacts() {
        Booking original=f.current;Receipt result=append(" Synthetic private note ");
        assertEquals(new Receipt(Code.OK,f.noteId,1),result);assertEquals("Synthetic private note",f.text);
        assertSame(original,f.current);assertEquals(1,f.notes);assertEquals(0,f.mutationSaves);assertEquals(0,f.effects);
        assertEquals(0,f.policyReads);assertEquals(0,f.calendarReads);assertEquals(0,f.quotaReads);
        assertEquals(List.of("expiry","attempt","authorized","note"),f.steps);
        assertSame(session,f.noteCommand.session());assertEquals(original.id(),f.noteCommand.bookingId());assertEquals("synthetic-note",f.noteCommand.key());
    }
    @Test void normalizesMultilineUnicodeAndAllowsTheFullCodePointBound() {
        append("  Synthetic cafe\u0301\nsecond\tline\r\n  ");assertEquals("Synthetic café\nsecond\tline",f.text);
        append("😀".repeat(4000));assertEquals(4000,f.text.codePointCount(0,f.text.length()));
        append("x");assertEquals("x",f.text);
    }
    @Test void rejectsEmptyOversizedAndControlBearingNotesBeforePersistence() {
        for(String text:Arrays.asList(null,""," \n\t ","x".repeat(4001),"😀".repeat(4001),"synthetic\u0000note","synthetic\u007fnote","synthetic\ud800note","synthetic\udfffnote"))
            assertThrows(DomainFailure.class,()->append(text));
        assertNull(f.noteCommand);assertEquals(0,f.notes);
    }
    @Test void fingerprintBindsNormalizedTextAndNeverCarriesPlaintext() {
        f.replay=new Receipt(Code.OK,UUID.randomUUID(),1);append("Synthetic cafe\u0301");String first=f.noteCommand.fingerprint();
        append(" Synthetic café ");assertEquals(first,f.noteCommand.fingerprint());
        append("Synthetic other");assertNotEquals(first,f.noteCommand.fingerprint());assertTrue(first.matches("[a-f0-9]{64}"));
        assertEquals("NoteCommand[REDACTED]",f.noteCommand.toString());assertEquals("NoteAddition[REDACTED]",new Addition("Synthetic private").toString());
    }
    @Test void replayDoesNotCreateAnotherNoteAndAuthorityStillPrecedesReplay() {
        Receipt result=append("Synthetic private");f.replay=result;f.steps.clear();assertSame(result,append("Synthetic private"));
        assertEquals(1,f.notes);assertTrue(f.steps.isEmpty());
        for(var kind:List.of(Failure.Kind.AUTHENTICATION_REQUIRED,Failure.Kind.FRESH_AUTHENTICATION_REQUIRED,Failure.Kind.FORBIDDEN)) {
            f.authorityFailure=new Failure(kind);assertSame(f.authorityFailure,assertThrows(Failure.class,()->append("Synthetic private")));
        }
        assertEquals(1,f.notes);
    }
    @Test void dueHoldsAreReclaimedBeforeAppendingEvenOnTheTargetBooking() {
        f.current=f.pending(f.current.start());Booking original=f.current;f.now=original.expiresAt();
        Receipt result=append("Synthetic expired booking note");assertTrue(result.success());assertEquals(1,result.version());
        assertEquals(original.version()+1,f.current.version());assertEquals(Booking.State.EXPIRED,f.current.state());
        assertEquals(1,f.expiryEffects);assertEquals(0,f.mutationSaves);
        assertEquals(List.of("expiry","reclaim","expiry-effects","attempt","authorized","note"),f.steps);
    }
    @Test void notesRemainAvailableAfterTheSessionWithoutChangingOutcome() {
        f.current=f.current.outcome(f.current.sessionEnd(),Booking.State.COMPLETED,"");f.now=f.current.sessionEnd().plusSeconds(1);
        Booking original=f.current;assertTrue(append("Synthetic follow-up").success());assertSame(original,f.current);
    }
    @Test void unknownBookingAndUnexpectedPersistenceFailuresCannotReturnSuccess() {
        f.lookupFailure=new Failure(Failure.Kind.NOT_FOUND);assertSame(f.lookupFailure,assertThrows(Failure.class,()->append("Synthetic note")));assertEquals(0,f.notes);
        f.lookupFailure=null;f.noteFailure=new Failure(Failure.Kind.STORAGE_FAILURE);assertSame(f.noteFailure,assertThrows(Failure.class,()->append("Synthetic note")));
        assertEquals(0,f.mutationSaves);
    }
    @Test void invalidCommandInputsNeverReachTheUnit() {
        Addition request=new Addition("Synthetic note");
        assertThrows(NullPointerException.class,()->new AdministratorNotes(null));
        assertThrows(NullPointerException.class,()->notes.append(session,f.current.id(),"key",null));
        assertThrows(NullPointerException.class,()->notes.append(null,f.current.id(),"key",request));
        assertThrows(DomainFailure.class,()->notes.append(session,null,"key",request));
        assertThrows(DomainFailure.class,()->notes.append(session,f.current.id()," ",request));
        assertThrows(DomainFailure.class,()->new Command(session,f.current.id(),"key","invalid"));assertNull(f.noteCommand);
    }
    static final class Fixture extends CustomerBookingChangesTest.Fixture implements NotesUnitOfWork,Notes {
        NotesUnitOfWork.Command noteCommand;UUID noteId;String text;int notes;RuntimeException authorityFailure,lookupFailure,noteFailure;
        public Receipt append(NotesUnitOfWork.Command command,Function<Notes,Receipt> work) {
            if(authorityFailure!=null)throw authorityFailure;noteCommand=command;return replay==null?work.apply(this):replay;
        }
        @Override public Booking authorizedBooking(){if(lookupFailure!=null)throw lookupFailure;return super.authorizedBooking();}
        public void noteAdded(UUID id,String value){assertTrue(attempting);steps.add("note");if(noteFailure!=null)throw noteFailure;notes++;noteId=id;text=value;}
    }
}
