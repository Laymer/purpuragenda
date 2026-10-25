package com.purpuragenda.application;

import com.purpuragenda.application.port.out.GuestAccessStore.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuestAccessStoreTest {
    @Test void boundedInputsRejectMissingMalformedAndCrossPurposeProofs() {
        String token="a".repeat(43),browser="b".repeat(64);
        for(String value:new String[]{null,"",token.substring(1),token+"a","!".repeat(43)})
            assertThrows(IllegalArgumentException.class,()->new Redemption(value,Kind.CREATE,browser));
        for(String value:new String[]{null,"",browser.substring(1),browser+"b","B".repeat(64)})
            assertThrows(IllegalArgumentException.class,()->new Redemption(token,Kind.MANAGE,value));
        assertThrows(IllegalArgumentException.class,()->new Redemption(token,null,browser));
        for(Kind kind:Kind.values()) {
            var request=new Redemption(token,kind,browser);
            assertTrue(request.token().equals(token)); assertTrue(request.browserHash().equals(browser)); assertEquals(kind,request.purpose());
            assertEquals("GuestRedemption[REDACTED]",request.toString());
        }
    }
    @Test void grantMetadataDoesNotRenderInternalAuthority() {
        var grant=new Grant(UUID.randomUUID(),UUID.randomUUID(),Kind.MANAGE,UUID.randomUUID(),Instant.EPOCH,Instant.EPOCH.plusSeconds(7200));
        assertEquals("GuestGrant[REDACTED]",grant.toString());
    }
}
