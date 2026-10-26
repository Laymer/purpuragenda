package com.purpuragenda.adapters.in.rest;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestPathsTest {
    @Test void everySpellingOfTheAvailabilityRouteNormalizesToOneValue() {
        for (String raw : new String[]{"/api/v1/availability", "/api/v1/%61vailability", "/api/v1/%61%76ailability", "/api/v1/availability;x=1",
                "/api/v1/availability;x=1;y=2", "/api/v1/availability/", "/api/v1//availability", "//api//v1//availability//", "/API/V1/Availability",
                "/api/v1/availability?from=1", "/api/v1/%41vailability", "/api/v1;jsessionid=x/availability", "/api/v1/availability%3Bx=1",
                "/api/v1/availability%2F", "/api%2Fv1/availability"})
            assertEquals("/api/v1/availability", RequestPaths.normalize(raw), raw);
    }

    @Test void unrelatedPathsKeepTheirIdentityAndEdgeInputsAreSafe() {
        assertEquals("/api/v1/services", RequestPaths.normalize("/api/v1/services"));
        assertEquals("/api/v1/availability/extra", RequestPaths.normalize("/api/v1/availability/extra"));
        assertEquals("/api/v1/availabilityx", RequestPaths.normalize("/api/v1/availabilityx"));
        assertEquals("/", RequestPaths.normalize("/"));
        assertEquals("/", RequestPaths.normalize(""));
        assertEquals("/café", RequestPaths.normalize("/caf%C3%A9"));
        assertNull(RequestPaths.normalize(null));
        for (String malformed : new String[]{"/api/v1/%", "/api/v1/%6", "/api/v1/%zz", "/api/v1/%g1vailability"}) assertNull(RequestPaths.normalize(malformed), malformed);
        // Decoding happens exactly once: a double-encoded name is a different, unroutable spelling.
        assertEquals("/api/v1/%61vailability", RequestPaths.normalize("/api/v1/%2561vailability"));
    }
}
