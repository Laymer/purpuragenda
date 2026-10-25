package com.purpuragenda.application;

import com.purpuragenda.application.port.out.RateLimitStore;
import com.purpuragenda.application.port.out.RateLimitStore.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestThrottleTest {
    static final class Recorder implements RateLimitStore {
        final List<List<Charge>> admitted = new ArrayList<>();
        final List<Reservation> released = new ArrayList<>();
        Decision next = Decision.allow(List.of());
        boolean releaseResult = true;
        @Override public Decision admit(List<Charge> charges) { admitted.add(charges); return next; }
        @Override public boolean release(Reservation reservation) { released.add(reservation); return releaseResult; }
    }
    static Reservation reservation() { return new Reservation(Kind.ADMIN_ATTEMPT, "a".repeat(64), Instant.parse("2026-11-01T10:00:00Z")); }

    @Test void policyLimitsAndWindowsAreTheApprovedOnes() {
        assertEquals(1, Kind.EMAIL_COOLDOWN.limit());
        assertEquals(60, Kind.EMAIL_COOLDOWN.window().toSeconds());
        assertEquals(5, Kind.EMAIL_ADDRESS.limit());
        assertEquals(3600, Kind.EMAIL_ADDRESS.window().toSeconds());
        assertEquals(30, Kind.EMAIL_IP.limit());
        assertEquals(3600, Kind.EMAIL_IP.window().toSeconds());
        assertEquals(5, Kind.ADMIN_ATTEMPT.limit());
        assertEquals(900, Kind.ADMIN_ATTEMPT.window().toSeconds());
        assertEquals(120, Kind.AVAILABILITY_IP.limit());
        assertEquals(60, Kind.AVAILABILITY_IP.window().toSeconds());
    }

    @Test void emailIssuanceChargesCooldownAddressAndNetworkTogetherWithNormalizedSubjects() {
        var store = new Recorder();
        var admission = new RequestThrottle(store).emailIssuance("  Guest.One@Example.TEST ", "203.0.113.9");
        assertTrue(admission.allowed());
        assertNull(admission.attempt());
        var charges = store.admitted.getFirst();
        assertEquals(List.of(Kind.EMAIL_COOLDOWN, Kind.EMAIL_ADDRESS, Kind.EMAIL_IP), charges.stream().map(Charge::kind).toList());
        assertEquals("guest.one@example.test", charges.get(0).subject());
        assertEquals("guest.one@example.test", charges.get(1).subject());
        assertEquals("203.0.113.9/32", charges.get(2).subject());
        assertEquals("ThrottleCharge[REDACTED]", charges.get(0).toString());
    }

    @Test void deniedDecisionsCarryRetryAfterAndNeverAnAttempt() {
        var store = new Recorder();
        store.next = Decision.deny(37);
        var admission = new RequestThrottle(store).availability("203.0.113.9");
        assertFalse(admission.allowed());
        assertEquals(37, admission.retryAfterSeconds());
        assertNull(admission.attempt());
        assertEquals("Admission[allowed=false]", admission.toString());
        assertEquals(List.of(Kind.AVAILABILITY_IP), store.admitted.getFirst().stream().map(Charge::kind).toList());
    }

    @Test void ipv4IsExactAndIpv6SharesOneSlashSixtyFourBucket() {
        assertEquals("203.0.113.9/32", RequestThrottle.network("203.0.113.9"));
        assertEquals(RequestThrottle.network("2001:db8:1:2::1"), RequestThrottle.network("2001:db8:1:2:ffff:ffff:ffff:ffff"));
        assertNotEquals(RequestThrottle.network("2001:db8:1:2::1"), RequestThrottle.network("2001:db8:1:3::1"));
        assertEquals("20010db800010002/64", RequestThrottle.network("2001:DB8:1:2::1"));
        assertEquals("198.51.100.7/32", RequestThrottle.network("::ffff:198.51.100.7"));
    }

    @Test void malformedOrNonLiteralSubjectsFailBeforeAnyStorageAccessWithoutEchoingInput() {
        var store = new Recorder();
        var throttle = new RequestThrottle(store);
        for (String ip : new String[]{null, "", "example.com", "fe80::1%eth0", "999.1.1.1", "1.2.3", "010.0.0.1", "1.2.3.4.5", "0x7f.0.0.1", "1".repeat(46), "203.0.113.9, 198.51.100.1"}) {
            var failure = assertThrows(IllegalArgumentException.class, () -> throttle.availability(ip));
            assertEquals("INVALID_THROTTLE_SUBJECT", failure.getMessage());
        }
        for (String email : new String[]{null, "", "no-at", "@x.test", "a@", "a@@b.test", "a b@x.test", "a@b\u0001.test", "a".repeat(250) + "@x.test"}) {
            var failure = assertThrows(IllegalArgumentException.class, () -> throttle.emailIssuance(email, "203.0.113.9"));
            assertEquals("INVALID_THROTTLE_SUBJECT", failure.getMessage());
        }
        assertTrue(store.admitted.isEmpty());
        assertThrows(NullPointerException.class, () -> new RequestThrottle(null));
    }

    @Test void adminAttemptReservesAccountAndNetworkAndSuccessReleasesOnce() {
        var store = new Recorder();
        store.next = Decision.allow(List.of(reservation()));
        var admission = new RequestThrottle(store).adminAttempt("Owner@Example.test", "203.0.113.9");
        assertTrue(admission.allowed());
        assertEquals("owner@example.test\n203.0.113.9/32", store.admitted.getFirst().getFirst().subject());
        assertEquals(Kind.ADMIN_ATTEMPT, store.admitted.getFirst().getFirst().kind());
        assertTrue(admission.attempt().succeeded());
        assertFalse(admission.attempt().succeeded());
        assertEquals(1, store.released.size());
        assertEquals("AdminAttempt[REDACTED]", admission.attempt().toString());
    }

    @Test void failedAttemptKeepsItsSlotAndCannotBeReleasedLater() {
        var store = new Recorder();
        store.next = Decision.allow(List.of(reservation()));
        var attempt = new RequestThrottle(store).adminAttempt("owner@example.test", "203.0.113.9").attempt();
        attempt.failed();
        assertFalse(attempt.succeeded());
        assertTrue(store.released.isEmpty());
    }

    @Test void successReportsWhenTheWindowHadAlreadyReset() {
        var store = new Recorder();
        store.next = Decision.allow(List.of(reservation()));
        store.releaseResult = false;
        assertFalse(new RequestThrottle(store).adminAttempt("owner@example.test", "203.0.113.9").attempt().succeeded());
    }

    @Test void storeValueObjectsRejectInconsistentInputAndHideDigests() {
        assertThrows(NullPointerException.class, () -> new Charge(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> new Charge(Kind.EMAIL_IP, null));
        assertThrows(IllegalArgumentException.class, () -> new Charge(Kind.EMAIL_IP, ""));
        assertThrows(IllegalArgumentException.class, () -> new Charge(Kind.EMAIL_IP, "x".repeat(601)));
        assertThrows(NullPointerException.class, () -> new Reservation(null, "a".repeat(64), Instant.EPOCH));
        assertThrows(NullPointerException.class, () -> new Reservation(Kind.EMAIL_IP, "a".repeat(64), null));
        assertThrows(IllegalArgumentException.class, () -> new Reservation(Kind.EMAIL_IP, "A".repeat(64), Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> new Reservation(Kind.EMAIL_IP, null, Instant.EPOCH));
        assertEquals("ThrottleReservation[REDACTED]", reservation().toString());
        assertThrows(IllegalArgumentException.class, () -> new Decision(true, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Decision(false, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Decision(false, 5, List.of(reservation())));
        assertThrows(UnsupportedOperationException.class, () -> Decision.allow(List.of()).reservations().add(reservation()));
    }
}
