package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.application.port.out.AdminChangesUnitOfWork;
import com.purpuragenda.application.port.out.AdminChangesUnitOfWork.Operation;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.port.out.SessionStore.*;
import com.purpuragenda.application.scheduling.AdministratorBookingChanges;
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

/** Wire mapping and strict bodies; real authority, transitions and effects are in the PostgreSQL HTTP suite. */
class AdministratorBookingChangeHttpTest {
    static final UUID BOOKING=UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final String BASE="/api/v1/admin/bookings/"+BOOKING,REASON="\"reason\":\"Synthetic administrative reason\"";
    static final String OVERRIDE="\"override\":{\"rules\":[],\"reason\":null,\"blackoutIds\":[]}";
    static final JsonMapper JSON=JsonMapper.builder().build();
    final List<AdminChangesUnitOfWork.Command> commands=new ArrayList<>();Code code=Code.OK;RuntimeException failure;
    final AdminChangesUnitOfWork unit=(command,work)->{
        commands.add(command);if(failure!=null)throw failure;return code==Code.OK?new Receipt(code,BOOKING,5):Receipt.failure(code);
    };
    MockMvc mvc;
    @BeforeEach void setup() { mvc=MockMvcBuilders.standaloneSetup(new AdministratorBookingChangeController(new AdministratorBookingChanges(unit))).build(); }
    Snapshot session(Kind kind) { return new Snapshot(new SessionReference(UUID.randomUUID(),"a".repeat(64)),kind,null,null,null,UUID.randomUUID(),0L,Instant.EPOCH,Instant.EPOCH,Instant.EPOCH.plusSeconds(28800),Instant.EPOCH); }
    MockHttpServletRequestBuilder request(String action,String body) {
        return post(BASE+action).requestAttr(ApiGuardFilter.SESSION_ATTRIBUTE,session(Kind.ADMIN)).header("Idempotency-Key","synthetic-key").header("If-Match","\"4\"").contentType("application/json").content(body);
    }
    MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception { return mvc.perform(request).andReturn().getResponse(); }
    void problem(MockHttpServletResponse response,int status,String expected) throws Exception {
        assertEquals(status,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals(expected,JSON.readTree(response.getContentAsString()).get("code").textValue());
        assertFalse(response.getContentAsString().contains("SYNTHETIC_PRIVATE"));
    }
    String reschedule() { return "{\"policyVersion\":1,\"calendarVersion\":1,\"startInstant\":\"2026-11-10T09:00:00Z\","+REASON+","+OVERRIDE+"}"; }

    @Test void eachCommandNamesItsOperationAndReturnsTheNewVersion() throws Exception {
        var bodies=Map.of("/reject","{"+REASON+"}","/cancel","{\"classification\":\"PROVIDER\","+REASON+"}","/reschedule",reschedule(),
                "/outcome","{\"outcome\":\"NO_SHOW\",\"reason\":null}");
        for(var entry:bodies.entrySet()) {
            var response=send(request(entry.getKey(),entry.getValue()));assertEquals(200,response.getStatus(),entry.getKey());
            assertEquals("\"5\"",response.getHeader("ETag"));assertEquals("no-store",response.getHeader("Cache-Control"));
            assertEquals(5,JSON.readTree(response.getContentAsString()).get("version").asLong());
        }
        assertEquals(Set.of(Operation.REJECT,Operation.CANCEL,Operation.RESCHEDULE,Operation.OUTCOME),commands.stream().map(AdminChangesUnitOfWork.Command::operation).collect(java.util.stream.Collectors.toSet()));
        assertTrue(commands.stream().allMatch(c -> c.bookingId().equals(BOOKING)));
        assertEquals(200,send(request("/outcome","{\"outcome\":\"COMPLETED\","+REASON+"}")).getStatus());
    }
    @Test void businessFailuresVersionsAndAdapterFailuresMapToSafeProblems() throws Exception {
        for(var expected:Map.of(Code.STALE_VERSION,412,Code.INVALID_TRANSITION,409,Code.SLOT_UNAVAILABLE,409,Code.SESSION_NOT_STARTED,409).entrySet()) {
            code=expected.getKey();problem(send(request("/reject","{"+REASON+"}")),expected.getValue(),code.name());
        }
        code=Code.OK;
        problem(send(post(BASE+"/reject").contentType("application/json").content("{"+REASON+"}")),401,"SESSION_REQUIRED");
        problem(send(request("/reject","{"+REASON+"}").requestAttr(ApiGuardFilter.SESSION_ATTRIBUTE,session(Kind.GUEST))),403,"ADMIN_ACCESS_REQUIRED");
        problem(send(post(BASE+"/reject").requestAttr(ApiGuardFilter.SESSION_ATTRIBUTE,session(Kind.ADMIN)).header("Idempotency-Key","k").contentType("application/json").content("{"+REASON+"}")),428,"VERSION_REQUIRED");
        var kinds=Map.of(Failure.Kind.AUTHENTICATION_REQUIRED,401,Failure.Kind.FRESH_AUTHENTICATION_REQUIRED,403,Failure.Kind.FORBIDDEN,403,Failure.Kind.NOT_FOUND,404,Failure.Kind.STORAGE_FAILURE,503);
        for(var kind:kinds.entrySet()) { failure=new Failure(kind.getKey());assertEquals(kind.getValue(),send(request("/reject","{"+REASON+"}")).getStatus()); }
        failure=new Failure(Failure.Kind.NOT_FOUND);problem(send(request("/reject","{"+REASON+"}")),404,"BOOKING_NOT_FOUND");
        failure=new DomainFailure("INVALID_BOOKING_CHANGE");problem(send(request("/reschedule",reschedule())),400,"INVALID_REQUEST");
        failure=new IllegalStateException("SYNTHETIC_PRIVATE_PROVIDER");problem(send(request("/reject","{"+REASON+"}")),503,"TEMPORARILY_UNAVAILABLE");
    }
    @Test void strictBodiesAndHeadersAreRejectedBeforeAnyCommand() throws Exception {
        var invalid=List.of(
            new String[]{"/reject","{\"reason\":\"short\"}"},new String[]{"/reject","{"+REASON+",\"extra\":1}"},
            new String[]{"/cancel","{\"classification\":\"LATE_CUSTOMER\","+REASON+"}"},new String[]{"/cancel","{\"classification\":\"provider\","+REASON+"}"},
            new String[]{"/outcome","{\"outcome\":\"CANCELLED\",\"reason\":null}"},new String[]{"/outcome","{\"outcome\":\"COMPLETED\"}"},
            new String[]{"/outcome","{\"outcome\":\"COMPLETED\",\"reason\":5}"},
            new String[]{"/reschedule",reschedule().replace("\"rules\":[]","\"rules\":[\"START_RULES\"]")},
            new String[]{"/reschedule",reschedule().replace("2026-11-10T09:00:00Z","2026-11-10T09:00:00")},
            new String[]{"/reschedule",reschedule().replace("\"calendarVersion\":1","\"calendarVersion\":0")});
        for(String[] item:invalid)problem(send(request(item[0],item[1])),400,"INVALID_REQUEST");
        problem(send(request("/reject","{"+REASON+"}").header("If-Match","W/\"4\"")),400,"INVALID_REQUEST");
        problem(send(post("/api/v1/admin/bookings/not-a-uuid/reject").requestAttr(ApiGuardFilter.SESSION_ATTRIBUTE,session(Kind.ADMIN)).header("Idempotency-Key","k").header("If-Match","\"4\"").contentType("application/json").content("{"+REASON+"}")),400,"INVALID_REQUEST");
        assertTrue(commands.isEmpty());
    }
}
