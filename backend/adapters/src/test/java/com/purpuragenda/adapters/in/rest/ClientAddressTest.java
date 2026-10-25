package com.purpuragenda.adapters.in.rest;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class ClientAddressTest {
    static MockHttpServletRequest request(String peer,String... headers) {
        var request=new MockHttpServletRequest();request.setRemoteAddr(peer);
        for(String header:headers)request.addHeader(ClientAddress.HEADER,header);return request;
    }
    final ClientAddress proxy=new ClientAddress(List.of("172.20.0.5","::1"));
    @Test void defaultAllowlistIsEmptyAndNeverTrustsAnyHeader() {
        var none=new ClientAddress(List.of());
        assertEquals("172.20.0.5",none.resolve(request("172.20.0.5","203.0.113.7")));
        assertEquals("203.0.113.8",none.resolve(request("203.0.113.8")));
    }
    @Test void trustedPeerSuppliesOneValidLiteralIp() {
        assertEquals("203.0.113.7",proxy.resolve(request("172.20.0.5","203.0.113.7")));
        assertEquals("2001:db8:0:0:0:0:0:1",proxy.resolve(request("172.20.0.5","2001:db8::1")));
        assertEquals("203.0.113.7",proxy.resolve(request("0:0:0:0:0:0:0:1","203.0.113.7")));
        assertEquals("1.2.3.4",proxy.resolve(request("172.20.0.5","::ffff:1.2.3.4")));
    }
    @Test void untrustedPeerCannotSpoofAndLookalikePeersAreNotTrusted() {
        for(String peer:List.of("172.20.0.6","172.20.0.50","203.0.113.7","127.0.0.1"))
            assertEquals(peer,proxy.resolve(request(peer,"198.51.100.1")));
    }
    @Test void invalidOrAmbiguousHeadersFallBackToTheSocketPeer() {
        for(String bad:List.of("","203.0.113","203.0.113.7.1","256.1.1.1","01.2.3.4","1.2.3","localhost","example.com","203.0.113.7,198.51.100.1","203.0.113.7 ",
                "[2001:db8::1]","fe80::1%eth0","::g","1:2:3:4:5:6:7:8:9","203.0.113.7/32","0x7f.1.1.1","2130706433","a:b","::::::::::::::::::::::::::::::::::::::::::::::"))
            assertEquals("172.20.0.5",proxy.resolve(request("172.20.0.5",bad)),bad);
        assertEquals("172.20.0.5",proxy.resolve(request("172.20.0.5","203.0.113.7","198.51.100.1")));
        assertEquals("172.20.0.5",proxy.resolve(request("172.20.0.5")));
    }
    @Test void otherForwardingHeadersAreIgnoredAndBadPeersAreSafe() {
        var request=request("172.20.0.5");request.addHeader("X-Forwarded-For","203.0.113.7");request.addHeader("Forwarded","for=203.0.113.7");
        assertEquals("172.20.0.5",proxy.resolve(request));
        assertEquals("0.0.0.0",proxy.resolve(request("not-an-ip")));
        assertEquals("ClientAddress[REDACTED]",proxy.toString());
    }
    @Test void allowlistRejectsNamesRangesAndBlanks() {
        for(String bad:List.of("localhost","10.0.0.0/8","","10.0.0.1 ","*","fe80::1%1"))
            assertThrows(IllegalArgumentException.class,()->new ClientAddress(List.of(bad)),bad);
        assertTrue(ClientAddress.literal(null).isEmpty());
    }
}
