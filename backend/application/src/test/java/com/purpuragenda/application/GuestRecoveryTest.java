package com.purpuragenda.application;

import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.GuestRecoveryLookup.Request;
import com.purpuragenda.domain.DomainFailure;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuestRecoveryTest {
    final Fixture fixture=new Fixture();
    final GuestRecovery recovery=new GuestRecovery(fixture,fixture);
    Request request() { return new Request(" B-SYNTHETIC-REFERENCE "," SYNTHETIC@EXAMPLE.TEST "); }
    @Test void matchedReferenceQueuesOnlyExactManagementProofWithoutReturningAuthority() {
        recovery.request(request());
        assertEquals("B-SYNTHETIC-REFERENCE",fixture.lookup.reference());assertEquals("synthetic@example.test",fixture.lookup.email());
        assertEquals(new AuthDeliveryStore.Issue("synthetic@example.test",AuthDeliveryStore.Purpose.MANAGE,fixture.booking,null),fixture.issue);
        assertEquals("GuestRecoveryRequest[REDACTED]",request().toString());
    }
    @Test void unknownReferenceOrContactReturnsNormallyAndQueuesNothing() {
        fixture.booking=null;assertDoesNotThrow(()->recovery.request(request()));assertNotNull(fixture.lookup);assertNull(fixture.issue);
    }
    @Test void malformedReferenceOrContactCannotReachLookupOrDelivery() {
        for(String reference:Arrays.asList(null,"","short","x".repeat(81),"B-SYNTHETIC\nREFERENCE"))
            assertThrows(DomainFailure.class,()->new Request(reference,"synthetic@example.test"));
        for(String email:Arrays.asList(null,"invalid","synthetic@localhost"))
            assertThrows(DomainFailure.class,()->new Request("B-SYNTHETIC-REFERENCE",email));
        assertThrows(NullPointerException.class,()->recovery.request(null));assertNull(fixture.lookup);assertNull(fixture.issue);
    }
    @Test void missingDependenciesAndLookupOrQueueFailuresCannotCreateSuccessfulSideEffects() {
        assertThrows(NullPointerException.class,()->new GuestRecovery(null,fixture));
        assertThrows(NullPointerException.class,()->new GuestRecovery(fixture,null));
        var unavailable=new GuestRecovery(r->{throw new IllegalStateException("SYNTHETIC_LOOKUP_UNAVAILABLE");},fixture);
        assertThrows(IllegalStateException.class,()->unavailable.request(request()));assertNull(fixture.issue);
        fixture.fail=true;assertThrows(IllegalStateException.class,()->recovery.request(request()));assertNull(fixture.issue);
    }
    static final class Fixture implements GuestRecoveryLookup,AuthDeliveryStore {
        UUID booking=UUID.randomUUID();Request lookup;Issue issue;boolean fail;
        public Optional<UUID> find(Request request) { lookup=request;return Optional.ofNullable(booking); }
        public Receipt issue(Issue request) {
            if(fail)throw new IllegalStateException("SYNTHETIC_QUEUE_UNAVAILABLE");issue=request;
            return new Receipt(UUID.randomUUID(),UUID.randomUUID(),Instant.parse("2026-10-25T15:00:00Z"));
        }
        public int eraseExpired() { throw new AssertionError("Unexpected cleanup"); }
        public Optional<MailDelivery.Message> prepare(NotificationQueue.Lease lease) { throw new AssertionError("Unexpected delivery"); }
    }
}
