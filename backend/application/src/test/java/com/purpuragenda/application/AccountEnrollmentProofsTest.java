package com.purpuragenda.application;

import com.purpuragenda.application.port.out.AuthDeliveryStore;
import com.purpuragenda.application.port.out.RateLimitStore.Decision;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccountEnrollmentProofsTest {
    final GuestProofsTest fixture=new GuestProofsTest();
    final AccountEnrollmentProofs proofs=new AccountEnrollmentProofs(new RequestThrottle(fixture.limits),fixture.delivery);
    @Test void accountProofIsBrowserBoundAndChargesCanonicalEmailLimitsBeforeDelivery() {
        var result=proofs.request("Synthetic@Example.TEST","203.0.113.9",GuestProofsTest.BROWSER);assertTrue(result.accepted());assertEquals(0,result.retryAfterSeconds());
        assertEquals(List.of("throttle:[EMAIL_COOLDOWN, EMAIL_ADDRESS, EMAIL_IP]","issue"),fixture.events);
        var issue=fixture.issued.getFirst();assertEquals(AuthDeliveryStore.Purpose.ACCOUNT,issue.purpose());assertEquals("synthetic@example.test",issue.email());assertEquals(GuestProofsTest.BROWSER,issue.browserHash());assertNull(issue.bookingId());
    }
    @Test void denialRetainsRetryAndNeverIssuesOrLooksUpMembership() {
        fixture.decision=Decision.deny(42);var result=proofs.request("synthetic@example.test","203.0.113.9",GuestProofsTest.BROWSER);
        assertFalse(result.accepted());assertEquals(42,result.retryAfterSeconds());assertTrue(fixture.issued.isEmpty());assertEquals(1,fixture.events.size());
    }
    @Test void invalidFieldsNeverChargeAndProviderFailurePropagatesToTheSafeBoundary() {
        assertThrows(RuntimeException.class,()->proofs.request("invalid","203.0.113.9",GuestProofsTest.BROWSER));assertThrows(IllegalArgumentException.class,()->proofs.request("synthetic@example.test","203.0.113.9","invalid"));assertTrue(fixture.events.isEmpty());
        fixture.deliveryFails=true;assertThrows(IllegalStateException.class,()->proofs.request("synthetic@example.test","203.0.113.9",GuestProofsTest.BROWSER));
        assertThrows(NullPointerException.class,()->new AccountEnrollmentProofs(null,fixture.delivery));assertThrows(NullPointerException.class,()->new AccountEnrollmentProofs(new RequestThrottle(fixture.limits),null));
    }
}
