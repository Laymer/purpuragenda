package com.purpuragenda.application;

import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.ExpiryReclamation;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.Booking;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExpiryReclamationTest {
    final Fake tx=new Fake();
    @Test void exactExpirySavesTransitionBeforeEffectsWithOneCapturedTime() {
        Booking pending=tx.pending(Instant.parse("2026-01-02T08:00:00Z"));
        tx.now=pending.expiresAt();tx.due=List.of(pending);
        assertEquals(1,ExpiryReclamation.reclaim(tx));assertEquals(1,tx.timeReads);
        assertEquals(List.of("save","effects"),tx.steps);
        Booking expired=tx.saved.getFirst();
        assertEquals(Booking.State.EXPIRED,expired.state());assertEquals(pending.version()+1,expired.version());
        assertSame(pending.snapshot(),expired.snapshot());assertFalse(expired.occupies(tx.now));
        assertEquals(List.of(expired.id()+"/"+expired.version()),tx.effects);
    }
    @Test void emptyDueListDoesNotCreateEffectsAndOrderedListIsPreserved() {
        assertEquals(0,ExpiryReclamation.reclaim(tx));assertTrue(tx.saved.isEmpty());assertTrue(tx.effects.isEmpty());
        Booking first=tx.pending(Instant.parse("2026-01-02T08:00:00Z"));
        Booking second=tx.pending(Instant.parse("2026-01-02T10:00:00Z"));
        tx.due=List.of(first,second);tx.now=first.expiresAt();
        assertEquals(2,ExpiryReclamation.reclaim(tx));
        assertEquals(List.of(first.id(),second.id()),tx.saved.stream().map(Booking::id).toList());
    }
    @Test void prematureOrCorruptProviderResultAndEffectFailurePropagateToAbortOuterTransaction() {
        Booking pending=tx.pending(Instant.parse("2026-01-02T08:00:00Z"));
        tx.due=List.of(pending);tx.now=pending.expiresAt().minusNanos(1000);
        assertEquals("INVALID_TRANSITION",assertThrows(DomainFailure.class,()->ExpiryReclamation.reclaim(tx)).code());
        assertTrue(tx.saved.isEmpty());
        tx.now=pending.expiresAt();tx.failEffects=true;
        assertEquals(Failure.Kind.STORAGE_FAILURE,assertThrows(Failure.class,()->ExpiryReclamation.reclaim(tx)).kind());
        assertThrows(NullPointerException.class,()->ExpiryReclamation.reclaim(null));
    }
    static class Fake extends PublicSchedulingTest.Fixture implements Transaction {
        List<Booking> due=List.of();List<Booking> saved=new ArrayList<>();List<String> effects=new ArrayList<>(),steps=new ArrayList<>();
        int timeReads;boolean failEffects;
        @Override public Instant now(){timeReads++;return now;}
        public List<Booking> duePending(){return due;}
        public com.purpuragenda.application.scheduling.CustomerQuota quota(UUID customer){throw new AssertionError("Expiry never changes quota");}
        public boolean chargeCreated(Booking booking){throw new AssertionError("Expiry never charges");}
        public void save(Booking booking){steps.add("save");saved.add(booking);}
        public void expiryEffects(UUID id,long version){
            if(failEffects)throw new Failure(Failure.Kind.STORAGE_FAILURE);
            steps.add("effects");effects.add(id+"/"+version);
        }
        public Receipt attempt(Supplier<Receipt> mutation){throw new AssertionError("Reclamation must precede the attempt savepoint");}
    }
}
