package com.purpuragenda.application;

import com.purpuragenda.application.port.out.NotificationQueue.Lease;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NotificationQueueTest {
    @Test void leaseExpiryIsInclusiveAndTokenNeverAppearsInDiagnostics() {
        UUID token = UUID.randomUUID(); Instant expiry = Instant.parse("2026-11-01T10:00:00Z");
        var lease = new Lease(UUID.randomUUID(), token, expiry);
        assertTrue(lease.liveAt(expiry.minusNanos(1)));
        assertFalse(lease.liveAt(expiry)); assertFalse(lease.liveAt(expiry.plusNanos(1)));
        assertTrue(lease.toString().contains("REDACTED")); assertFalse(lease.toString().contains(token.toString()));
        assertNotNull(lease.id()); assertTrue(lease.token().equals(token)); assertEquals(expiry, lease.expiresAt());
    }
    @Test void incompleteLeasesAreRejected() {
        UUID id = UUID.randomUUID(); Instant now = Instant.EPOCH;
        assertThrows(NullPointerException.class, () -> new Lease(null, id, now));
        assertThrows(NullPointerException.class, () -> new Lease(id, null, now));
        assertThrows(NullPointerException.class, () -> new Lease(id, id, null));
    }
}
