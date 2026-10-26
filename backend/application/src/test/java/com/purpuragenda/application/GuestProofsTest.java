package com.purpuragenda.application;

import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.RateLimitStore.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuestProofsTest {
    static final String BROWSER="b".repeat(64);
    final List<String> events=new ArrayList<>();
    final List<AuthDeliveryStore.Issue> issued=new ArrayList<>();
    Decision decision=Decision.allow(List.of(new Reservation(Kind.EMAIL_IP,"a".repeat(64),Instant.EPOCH)));
    Optional<UUID> found=Optional.empty();boolean deliveryFails;
    final RateLimitStore limits=new RateLimitStore() {
        public Decision admit(List<Charge> charges) { events.add("throttle:"+charges.stream().map(c->c.kind().name()).toList());return decision; }
        public boolean release(Reservation reservation) { return false; }
    };
    final AuthDeliveryStore delivery=new AuthDeliveryStore() {
        public Receipt issue(Issue issue) { events.add("issue");if(deliveryFails)throw new IllegalStateException("synthetic-private-detail");issued.add(issue);return new Receipt(UUID.randomUUID(),UUID.randomUUID(),Instant.EPOCH); }
        public int eraseExpired() { return 0; }
        public Optional<MailDelivery.Message> prepare(NotificationQueue.Lease lease) { throw new AssertionError(); }
    };
    final GuestRecoveryLookup lookup=request -> { events.add("lookup");return found; };
    final GuestProofs proofs=new GuestProofs(new RequestThrottle(limits),delivery,new GuestRecovery(lookup,delivery));

    @Test void createChargesAllEmailLimitsBeforeIssuingABrowserBoundProof() {
        var outcome=proofs.requestCreate("Guest@Example.TEST","203.0.113.9",BROWSER);
        assertTrue(outcome.accepted());
        assertEquals(List.of("throttle:[EMAIL_COOLDOWN, EMAIL_ADDRESS, EMAIL_IP]","issue"),events);
        assertEquals(AuthDeliveryStore.Purpose.CREATE,issued.getFirst().purpose());
        assertEquals(BROWSER,issued.getFirst().browserHash());
    }
    @Test void throttledRequestsNeverReachDeliveryOrLookup() {
        decision=Decision.deny(42);
        var create=proofs.requestCreate("guest@example.test","203.0.113.9",BROWSER);
        var manage=proofs.requestManage("SYNTHETIC1","guest@example.test","203.0.113.9");
        assertFalse(create.accepted());assertEquals(42,create.retryAfterSeconds());assertEquals(42,manage.retryAfterSeconds());
        assertTrue(issued.isEmpty());assertFalse(events.contains("lookup"));assertFalse(events.contains("issue"));
    }
    @Test void knownAndUnknownManagementRequestsAreIndistinguishableToTheCaller() {
        var unknown=proofs.requestManage("SYNTHETIC1","guest@example.test","203.0.113.9");
        found=Optional.of(UUID.randomUUID());
        var known=proofs.requestManage("SYNTHETIC1","guest@example.test","203.0.113.9");
        assertEquals(unknown,known);assertTrue(known.accepted());
        assertEquals(1,issued.size());assertEquals(AuthDeliveryStore.Purpose.MANAGE,issued.getFirst().purpose());
        assertEquals(List.of("throttle:[EMAIL_COOLDOWN, EMAIL_ADDRESS, EMAIL_IP]","lookup","throttle:[EMAIL_COOLDOWN, EMAIL_ADDRESS, EMAIL_IP]","lookup","issue"),events);
    }
    @Test void invalidInputFailsBeforeAnyChargeAndStorageFailurePropagatesWithoutDetailLeak() {
        assertThrows(RuntimeException.class,()->proofs.requestCreate("not-an-email","203.0.113.9",BROWSER));
        assertThrows(IllegalArgumentException.class,()->proofs.requestCreate("guest@example.test","203.0.113.9","short"));
        assertThrows(RuntimeException.class,()->proofs.requestManage("x","guest@example.test","203.0.113.9"));
        assertTrue(events.isEmpty());
        deliveryFails=true;
        assertThrows(IllegalStateException.class,()->proofs.requestCreate("guest@example.test","203.0.113.9",BROWSER));
        assertThrows(NullPointerException.class,()->new GuestProofs(null,delivery,new GuestRecovery(lookup,delivery)));
        assertThrows(NullPointerException.class,()->new GuestProofs(new RequestThrottle(limits),null,null));
        assertEquals(new GuestProofs.Outcome(true,0),proofs.requestManage("SYNTHETIC1","guest@example.test","203.0.113.9"));
    }
}
