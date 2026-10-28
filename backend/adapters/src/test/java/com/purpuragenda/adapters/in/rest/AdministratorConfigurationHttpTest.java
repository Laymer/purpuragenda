package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.port.out.SessionStore.*;
import com.purpuragenda.application.scheduling.*;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Status and wire mapping; real authority, conflicts and persistence are exercised by the PostgreSQL HTTP suite. */
class AdministratorConfigurationHttpTest {
    static final String BASE="/api/v1/admin/configuration",REVIEW="\"reason\":\"Synthetic configuration reason\",\"acknowledgedBookingIds\":[]";
    static final JsonMapper JSON=JsonMapper.builder().build();
    final List<ConfigurationUnitOfWork.Command> commands=new ArrayList<>();Code code=Code.OK;RuntimeException failure;
    final ConfigurationUnitOfWork unit=(command,work)->{
        commands.add(command);if(failure!=null)throw failure;
        return code==Code.OK?new Receipt(code,command.operation()==ConfigurationUnitOfWork.Operation.UPDATE_POLICY?AdministratorConfiguration.POLICY_ID:AdministratorConfiguration.CALENDAR_ID,4):Receipt.failure(code);
    };
    final ConfigurationQuery query=new ConfigurationQuery() {
        @Override public <T> T read(SessionBinding session,Function<View,T> work) { if(failure!=null)throw failure;throw new AssertionError("unit preview not expected"); }
    };
    MockMvc mvc;
    @BeforeEach void setup() {
        var sessions=(SessionStore)Proxy.newProxyInstance(SessionStore.class.getClassLoader(),new Class<?>[]{SessionStore.class},(p,m,a)->{throw new AssertionError("GET is covered by PostgreSQL");});
        mvc=MockMvcBuilders.standaloneSetup(new AdministratorConfigurationController(new AdministratorConfiguration(unit),new AdministratorConfigurationView(query),
                new SessionAccess(sessions),SessionCookies.operational())).build();
    }
    Snapshot session(Kind kind) { return new Snapshot(new SessionReference(UUID.randomUUID(),"a".repeat(64)),kind,null,null,null,UUID.randomUUID(),0L,Instant.EPOCH,Instant.EPOCH,Instant.EPOCH.plusSeconds(28800),Instant.EPOCH); }
    MockHttpServletRequestBuilder request(String action,String body,Kind kind) {
        return post(BASE+action).requestAttr(ApiGuardFilter.SESSION_ATTRIBUTE,session(kind)).header("Idempotency-Key","synthetic-key").header("If-Match","\"3\"").contentType("application/json").content(body);
    }
    MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception { return mvc.perform(request).andReturn().getResponse(); }
    void problem(MockHttpServletResponse response,int status,String expected) throws Exception {
        assertEquals(status,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals(expected,JSON.readTree(response.getContentAsString()).get("code").textValue());
        assertFalse(response.getContentAsString().contains("SYNTHETIC_PRIVATE"));
    }
    String policy() { return "{\"policy\":{\"gridMinutes\":30,\"dailyCapacity\":0,\"minimumNotice\":\"PT0S\",\"pendingLifetime\":\"PT1H30M\",\"expiryBuffer\":\"PT1H\",\"customerCutoff\":\"PT24H\"},"+REVIEW+"}"; }

    @Test void receiptsMapToTheNamedVersionAndExpectedBusinessFailures() throws Exception {
        var response=send(request("/policy",policy(),Kind.ADMIN));assertEquals(200,response.getStatus());assertEquals("\"4\"",response.getHeader("ETag"));
        assertEquals("{\"policyVersion\":4}",response.getContentAsString());assertEquals(ConfigurationUnitOfWork.Operation.UPDATE_POLICY,commands.getFirst().operation());
        var removal=send(request("/blackouts/11111111-1111-4111-8111-111111111111/removal","{"+REVIEW+"}",Kind.ADMIN));assertEquals("{\"calendarVersion\":4}",removal.getContentAsString());
        for(var expected:Map.of(Code.CONFLICT_REVIEW_REQUIRED,409,Code.INVALID_CONFIGURATION,400,Code.POLICY_CHANGED,412,Code.CALENDAR_CHANGED,412,Code.IDEMPOTENCY_KEY_REUSED,409).entrySet()) {
            code=expected.getKey();problem(send(request("/policy",policy(),Kind.ADMIN)),expected.getValue(),code.name());
        }
    }
    @Test void sessionKindsAndAdapterFailuresBecomeSafeProblems() throws Exception {
        problem(send(post(BASE+"/policy").contentType("application/json").content(policy())),401,"SESSION_REQUIRED");
        problem(send(request("/policy",policy(),Kind.ANONYMOUS)),403,"ADMIN_ACCESS_REQUIRED");
        var kinds=Map.of(Failure.Kind.AUTHENTICATION_REQUIRED,"SESSION_REQUIRED",Failure.Kind.FRESH_AUTHENTICATION_REQUIRED,"AUTHENTICATION_REQUIRED",
                Failure.Kind.FORBIDDEN,"ADMIN_ACCESS_REQUIRED",Failure.Kind.STORAGE_FAILURE,"TEMPORARILY_UNAVAILABLE");
        for(var kind:kinds.entrySet()) {
            failure=new Failure(kind.getKey());var response=send(request("/policy",policy(),Kind.ADMIN));
            problem(response,Map.of("SESSION_REQUIRED",401,"TEMPORARILY_UNAVAILABLE",503).getOrDefault(kind.getValue(),403),kind.getValue());
        }
        failure=new IllegalStateException("SYNTHETIC_PRIVATE_PROVIDER");problem(send(request("/policy",policy(),Kind.ADMIN)),503,"TEMPORARILY_UNAVAILABLE");
        problem(send(request("/conflicts","{}",Kind.ADMIN)),400,"INVALID_REQUEST");
    }
    @Test void strictBodiesRejectAmbiguousOrOutOfRangeValuesBeforeAnyCommand() throws Exception {
        for(String body:List.of("{"+REVIEW+"}","{\"policy\":{},"+REVIEW+"}",policy().replace("\"PT0S\"","\"PT\""),policy().replace("\"dailyCapacity\":0","\"dailyCapacity\":1.5"),
                policy().replace("[]","[\"11111111-1111-4111-8111-111111111111\",\"11111111-1111-4111-8111-111111111111\"]"),policy().replace("[]","{}"),policy().replace("\"gridMinutes\":30","\"gridMinutes\":0")))
            problem(send(request("/policy",body,Kind.ADMIN)),400,"INVALID_REQUEST");
        String week="{\"MONDAY\":[],\"TUESDAY\":[],\"WEDNESDAY\":[],\"THURSDAY\":[],\"FRIDAY\":[],\"SATURDAY\":[],\"SUNDAY\":[%s]}";
        for(String window:List.of("{\"start\":\"18:00\",\"end\":\"09:00\"}","{\"start\":\"9:00\",\"end\":\"10:00\"}","{\"start\":\"09:00\"}","1"))
            problem(send(request("/weekly","{\"weekly\":"+week.formatted(window)+","+REVIEW+"}",Kind.ADMIN)),400,"INVALID_REQUEST");
        assertTrue(commands.isEmpty());
        assertEquals(200,send(request("/weekly","{\"weekly\":"+week.formatted("{\"start\":\"00:00\",\"end\":\"24:00\"}")+","+REVIEW+"}",Kind.ADMIN)).getStatus());
        for(String exception:List.of("{\"date\":\"2026-11-01\",\"mode\":\"OPEN\",\"windows\":[]}","{\"date\":\"01/11/2026\",\"mode\":\"CLOSED\",\"windows\":[]}","{\"date\":\"2026-11-01\",\"mode\":\"CLOSED\",\"windows\":[{\"start\":\"09:00\",\"end\":\"10:00\"}]}"))
            problem(send(request("/date-exceptions","{\"dateException\":"+exception+","+REVIEW+"}",Kind.ADMIN)),400,"INVALID_REQUEST");
        problem(send(request("/blackouts","{\"blackout\":{\"id\":\"x\",\"startInstant\":\"2026-11-01T10:00:00Z\",\"endInstant\":\"2026-11-01T11:00:00Z\",\"reason\":\"Synthetic closure reason\"},"+REVIEW+"}",Kind.ADMIN)),400,"INVALID_REQUEST");
        problem(send(request("/blackouts/not-a-uuid/removal","{"+REVIEW+"}",Kind.ADMIN)),400,"INVALID_REQUEST");
        assertEquals(1,commands.size());
    }
}
