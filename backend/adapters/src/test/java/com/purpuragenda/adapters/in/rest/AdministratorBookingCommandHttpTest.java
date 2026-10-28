package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.purpuragenda.application.port.out.AdminBookingUnitOfWork;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.port.out.SessionStore.*;
import com.purpuragenda.application.scheduling.AdminBooking;
import com.purpuragenda.domain.DomainFailure;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Wire mapping and strict override parsing; real authority, rules and persistence are in the PostgreSQL HTTP suite. */
class AdministratorBookingCommandHttpTest {
    static final UUID BOOKING=UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final String PATH="/api/v1/admin/bookings";
    static final JsonMapper JSON=JsonMapper.builder().build();
    final List<AdminBookingUnitOfWork.Command> commands=new ArrayList<>();Code code=Code.OK;RuntimeException failure;
    final AdminBookingUnitOfWork unit=(command,work)->{
        commands.add(command);if(failure!=null)throw failure;return code==Code.OK?new Receipt(code,BOOKING,1):Receipt.failure(code);
    };
    MockMvc mvc;
    @BeforeEach void setup() { mvc=MockMvcBuilders.standaloneSetup(new AdministratorBookingCommandController(new AdminBooking(unit,"local-v1",Set.of("zoom.us")))).build(); }
    Snapshot session(Kind kind) { return new Snapshot(new SessionReference(UUID.randomUUID(),"a".repeat(64)),kind,null,null,null,UUID.randomUUID(),0L,Instant.EPOCH,Instant.EPOCH,Instant.EPOCH.plusSeconds(28800),Instant.EPOCH); }
    ObjectNode body(String meeting,String reason,String... rules) {
        var body=JSON.createObjectNode().put("serviceId",BOOKING.toString()).put("serviceVersion",1).put("policyVersion",1).put("calendarVersion",1)
                .put("startInstant","2026-11-10T09:00:00Z").put("customerEmail","customer@example.test").put("displayZone","Europe/Brussels").put("noticeVersion","local-v1");
        body.putObject("intake").put("firstName","Synthetic").put("lastName","Customer").put("telephone","+32400123456").put("birthDate","1990-01-01")
                .put("birthTime","12:00").put("birthplace","Synthetic city").put("message","");
        body.put("meetingUrl",meeting);
        var override=body.putObject("override");var named=override.putArray("rules");for(String rule:rules)named.add(rule);
        override.put("reason",reason);override.putArray("blackoutIds");return body;
    }
    MockHttpServletRequestBuilder request(ObjectNode body,Kind kind) {
        return post(PATH).requestAttr(ApiGuardFilter.SESSION_ATTRIBUTE,session(kind)).header("Idempotency-Key","synthetic-key").contentType("application/json").content(body.toString());
    }
    MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception { return mvc.perform(request).andReturn().getResponse(); }
    void problem(MockHttpServletResponse response,int status,String expected) throws Exception {
        assertEquals(status,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals(expected,JSON.readTree(response.getContentAsString()).get("code").textValue());
        assertFalse(response.getContentAsString().contains("SYNTHETIC_PRIVATE"));
    }

    @Test void createdReceiptsAndBusinessFailuresMapToExplicitStatuses() throws Exception {
        var created=send(request(body(null,null),Kind.ADMIN));assertEquals(201,created.getStatus());assertEquals("\"1\"",created.getHeader("ETag"));
        assertEquals(PATH+"/"+BOOKING,created.getHeader("Location"));assertEquals(BOOKING.toString(),JSON.readTree(created.getContentAsString()).get("bookingId").textValue());
        assertEquals(201,send(request(body("https://zoom.us/j/123456789","Synthetic owner exception reason","START_RULES","NOTICE"),Kind.ADMIN)).getStatus());
        for(var expected:Map.of(Code.SLOT_UNAVAILABLE,409,Code.OVERRIDE_UNUSED,409,Code.INVALID_BIRTH_DATE,400,Code.CALENDAR_CHANGED,409).entrySet()) {
            code=expected.getKey();problem(send(request(body(null,null),Kind.ADMIN)),expected.getValue(),code.name());
        }
        assertEquals(6,commands.size());
    }
    @Test void sessionsMeetingsAndAdapterFailuresBecomeSafeProblems() throws Exception {
        problem(send(post(PATH).contentType("application/json").content(body(null,null).toString())),401,"SESSION_REQUIRED");
        problem(send(request(body(null,null),Kind.ACCOUNT)),403,"ADMIN_ACCESS_REQUIRED");
        problem(send(request(body("https://example.test/j/123456789",null),Kind.ADMIN)),400,"INVALID_MEETING");assertTrue(commands.isEmpty());
        var kinds=Map.of(Failure.Kind.AUTHENTICATION_REQUIRED,401,Failure.Kind.FRESH_AUTHENTICATION_REQUIRED,403,Failure.Kind.FORBIDDEN,403,Failure.Kind.MAINTENANCE,503);
        for(var kind:kinds.entrySet()) { failure=new Failure(kind.getKey());assertEquals(kind.getValue(),send(request(body(null,null),Kind.ADMIN)).getStatus()); }
        failure=new DomainFailure("INVALID_BOOKING_REQUEST");problem(send(request(body(null,null),Kind.ADMIN)),400,"INVALID_REQUEST");
        failure=new IllegalStateException("SYNTHETIC_PRIVATE_PROVIDER");problem(send(request(body(null,null),Kind.ADMIN)),503,"TEMPORARILY_UNAVAILABLE");
    }
    @Test void overridesAndBodiesAreStrictBeforeAnyCommand() throws Exception {
        var duplicateBlackout=body(null,"Synthetic owner exception reason","HARD_BLACKOUT");
        ((com.fasterxml.jackson.databind.node.ArrayNode)duplicateBlackout.get("override").get("blackoutIds")).add(BOOKING.toString()).add(BOOKING.toString());
        var blackoutWithoutRule=body(null,null);((com.fasterxml.jackson.databind.node.ArrayNode)blackoutWithoutRule.get("override").get("blackoutIds")).add(BOOKING.toString());
        var rulesNotArray=body(null,null);((ObjectNode)rulesNotArray.get("override")).put("rules","START_RULES");
        var extra=body(null,null);extra.put("customerId",BOOKING.toString());
        for(var invalid:List.of(body(null,null,"START_RULES"),body(null,"Synthetic owner exception reason"),body(null,"Synthetic owner exception reason","START_RULES","START_RULES"),
                body(null,"Synthetic owner exception reason","OVERLAP"),body(null,"short","START_RULES"),duplicateBlackout,blackoutWithoutRule,rulesNotArray,extra))
            problem(send(request(invalid,Kind.ADMIN)),400,"INVALID_REQUEST");
        problem(send(request(body(null,null),Kind.ADMIN).header("If-Match","\"1\"")),400,"INVALID_REQUEST");
        assertTrue(commands.isEmpty());
    }
}
