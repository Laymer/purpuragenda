package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.application.scheduling.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class BookingContextHttpTest {
    final PublicHttpTest.Fixture fixture=new PublicHttpTest.Fixture();
    final BookingNotice notice=new BookingNotice("local-v1","Synthetic \"notice\"\nPlain <text>",true);
    final MockMvc mvc=MockMvcBuilders.standaloneSetup(new BookingContextController(new PublicBookingContext(fixture,notice))).build();
    final String path="/api/v1/booking-context?serviceId="+PublicHttpTest.SERVICE;
    @Test void explicitWireShapeEscapesTextAndReturnsCurrentVersionsWithoutCaching() throws Exception {
        var response=mvc.perform(get(path)).andReturn().getResponse();assertEquals(200,response.getStatus());
        assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(response.getHeader("ETag"));
        var body=JsonMapper.builder().build().readTree(response.getContentAsString());
        assertEquals(7,body.size());assertEquals("Europe/Brussels",body.get("businessZone").asText());assertEquals(120,body.get("reservationMinutes").asInt());
        assertEquals(3,body.at("/service/version").asLong());assertEquals(1500,body.at("/service/priceMinor").asLong());
        assertEquals(60,body.at("/service/durationMinutes").asInt());assertEquals("EUR",body.at("/service/currency").asText());
        assertEquals(7,body.at("/policy/version").asLong());assertEquals(86400,body.at("/policy/pendingLifetimeSeconds").asLong());
        assertEquals(notice.text(),body.at("/notice/text").asText());assertEquals(notice.version(),body.at("/notice/version").asText());assertTrue(body.at("/notice/synthetic").asBoolean());
        assertEquals(fixture.now.toString(),body.get("serverNow").asText());assertEquals(1,fixture.reads);
        assertFalse(response.getContentAsString().contains("customerId"));
    }
    @Test void malformedDuplicateAndUnexpectedParametersNeverReachTheDatabase() throws Exception {
        for(String suffix:List.of("","?serviceId=bad","?serviceId=1-1-1-1-1","?serviceId="+PublicHttpTest.SERVICE+"&serviceId="+PublicHttpTest.SERVICE,
                "?serviceId="+PublicHttpTest.SERVICE+"&customerId=anything")) {
            var response=mvc.perform(get("/api/v1/booking-context"+suffix)).andReturn().getResponse();
            assertEquals(400,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));
        }
        assertEquals(0,fixture.reads);assertThrows(NullPointerException.class,()->new BookingContextController(null));
    }
    @Test void absentArchivedAndStorageFailuresHaveSafeNoncacheableProblems() throws Exception {
        assertEquals(404,mvc.perform(get("/api/v1/booking-context?serviceId="+UUID.randomUUID())).andReturn().getResponse().getStatus());
        assertEquals(404,mvc.perform(get("/api/v1/booking-context?serviceId="+fixture.services.get(1).id())).andReturn().getResponse().getStatus());
        fixture.failing=true;var response=mvc.perform(get(path)).andReturn().getResponse();
        assertEquals(503,response.getStatus());assertEquals("1",response.getHeader("Retry-After"));assertEquals("no-store",response.getHeader("Cache-Control"));
        assertTrue(response.getContentAsString().contains("TEMPORARILY_UNAVAILABLE"));assertFalse(response.getContentAsString().contains("synthetic-private-detail"));
    }
}
