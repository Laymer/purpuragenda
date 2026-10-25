package com.purpuragenda.application;

import com.purpuragenda.application.port.out.AuthDeliveryStore.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuthDeliveryStoreTest {
    @Test void purposesRequireExplicitScopeAndRedactPrivateInputs() {
        for(var purpose:Purpose.values()) {
            boolean browser=purpose==Purpose.CREATE || purpose==Purpose.ACCOUNT;
            var issue=new Issue(" Synthetic@Example.test ",purpose,browser?null:UUID.randomUUID(),browser?"a".repeat(64):null);
            assertTrue(issue.email().equals("synthetic@example.test")); assertTrue(issue.toString().contains("REDACTED"));
            assertFalse(issue.toString().contains(issue.email())); assertEquals(purpose,issue.purpose());
        }
        var id=UUID.randomUUID(); var expiry=Instant.EPOCH; var receipt=new Receipt(id,id,expiry);
        assertEquals(id,receipt.challengeId()); assertEquals(id,receipt.jobId()); assertEquals(expiry,receipt.expiresAt());
    }
    @Test void missingMixedOrMalformedBindingsAreRejected() {
        for(var purpose:Purpose.values()) {
            assertThrows(IllegalArgumentException.class,()->new Issue("synthetic@example.test",purpose,null,null));
            assertThrows(IllegalArgumentException.class,()->new Issue("synthetic@example.test",purpose,UUID.randomUUID(),"a".repeat(64)));
        }
        for(String hash:new String[]{"", "a".repeat(63),"A".repeat(64),"g".repeat(64)})
            assertThrows(IllegalArgumentException.class,()->new Issue("synthetic@example.test",Purpose.ACCOUNT,null,hash));
        assertThrows(NullPointerException.class,()->new Issue("synthetic@example.test",null,null,null));
    }
}
