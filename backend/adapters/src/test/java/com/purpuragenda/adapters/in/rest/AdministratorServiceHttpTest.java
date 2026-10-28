package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.port.out.ConfigurationUnitOfWork;
import com.purpuragenda.application.port.out.ConfigurationUnitOfWork.Operation;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.port.out.SessionStore.Kind;
import com.purpuragenda.application.scheduling.AdministratorConfiguration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class AdministratorServiceHttpTest {
    final AdministratorBookingChangeHttpTest f=new AdministratorBookingChangeHttpTest();
    static final String BASE="/api/v1/admin/configuration/services",ID="11111111-1111-4111-8111-111111111111";
    static final String BODY="{\"name\":\"Synthetic service\",\"durationMinutes\":30,\"priceMinor\":1500,\"reason\":\"Synthetic service change\"}",REASON="{\"reason\":\"Synthetic service change\"}";
    final List<ConfigurationUnitOfWork.Command> commands=new ArrayList<>();Code code=Code.OK;RuntimeException failure;
    @BeforeEach void setup() {
        f.mvc=MockMvcBuilders.standaloneSetup(new AdministratorServiceController(new AdministratorConfiguration((command,work)->{
            commands.add(command);if(failure!=null)throw failure;return code==Code.OK?new Receipt(Code.OK,UUID.fromString(ID),2):Receipt.failure(code);
        }))).build();
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String suffix,String body,boolean version) {
        var request=post(BASE+suffix).requestAttr(ApiGuardFilter.SESSION_ATTRIBUTE,f.session(Kind.ADMIN)).header("Idempotency-Key","synthetic-key").contentType("application/json").content(body);
        if(version)request.header("If-Match","\"1\"");return request;
    }
    @Test void everyOperationReturnsItsServiceIdAndVersion() throws Exception {
        for(var entry:Map.of("",BODY,"/"+ID,BODY,"/"+ID+"/archive",REASON,"/"+ID+"/restore",REASON).entrySet()) {
            var response=f.send(request(entry.getKey(),entry.getValue(),!entry.getKey().isEmpty()));assertEquals(200,response.getStatus());
            assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("\"2\"",response.getHeader("ETag"));
            var json=f.JSON.readTree(response.getContentAsString());assertEquals(ID,json.get("id").asText());assertEquals(2,json.get("version").asInt());
        }
        assertEquals(Set.of(Operation.CREATE_SERVICE,Operation.UPDATE_SERVICE,Operation.ARCHIVE_SERVICE,Operation.RESTORE_SERVICE),commands.stream().map(ConfigurationUnitOfWork.Command::operation).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void versionsAuthorityAndFailuresMapSafely() throws Exception {
        f.problem(f.send(post(BASE).contentType("application/json").content(BODY)),401,"SESSION_REQUIRED");
        f.problem(f.send(request("",BODY,false).requestAttr(ApiGuardFilter.SESSION_ATTRIBUTE,f.session(Kind.GUEST))),403,"ADMIN_ACCESS_REQUIRED");
        f.problem(f.send(request("/"+ID,BODY,false)),428,"VERSION_REQUIRED");
        for(var entry:Map.of(Code.STALE_VERSION,412,Code.INVALID_CONFIGURATION,409,Code.IDEMPOTENCY_KEY_REUSED,409).entrySet()) {
            code=entry.getKey();f.problem(f.send(request("/"+ID,BODY,true)),entry.getValue(),code.name());
        }
        code=Code.OK;
        for(var kind:Map.of(Failure.Kind.AUTHENTICATION_REQUIRED,401,Failure.Kind.FRESH_AUTHENTICATION_REQUIRED,403,Failure.Kind.FORBIDDEN,403,Failure.Kind.NOT_FOUND,404,Failure.Kind.STORAGE_FAILURE,503).entrySet()) {
            failure=new Failure(kind.getKey());assertEquals(kind.getValue(),f.send(request("",BODY,false)).getStatus());
        }
        failure=new Failure(Failure.Kind.NOT_FOUND);f.problem(f.send(request("/"+ID,BODY,true)),404,"SERVICE_NOT_FOUND");
        failure=new IllegalStateException("SYNTHETIC_PRIVATE");f.problem(f.send(request("",BODY,false)),503,"TEMPORARILY_UNAVAILABLE");
        failure=new ArithmeticException();f.problem(f.send(request("",BODY,false)),400,"INVALID_REQUEST");
    }
    @Test void strictBodiesAndHeadersRejectAmbiguityBeforeTheUnitOfWork() throws Exception {
        for(String body:List.of("{}",BODY+"{}",BODY.replace("30","30.5"),BODY.replace("30","2147483648"),BODY.replace("1500","1500.5"),
                BODY.replace("1500","9223372036854775808"),BODY.replace("1500","-1"),BODY.replace("30","121"),BODY.replace("Synthetic service change","short"),
                BODY.replace("\"name\":","\"extra\":1,\"name\":"),BODY.replace("\"name\":","\"name\":\"duplicate\",\"name\":")))
            f.problem(f.send(request("",body,false)),400,"INVALID_REQUEST");
        f.problem(f.send(request("",BODY,true)),400,"INVALID_REQUEST");
        f.problem(f.send(request("/"+ID,BODY,true).header("If-Match","W/\"1\"")),400,"INVALID_REQUEST");
        f.problem(f.send(request("/not-an-id",BODY,true)),400,"INVALID_REQUEST");
        f.problem(f.send(post(BASE).requestAttr(ApiGuardFilter.SESSION_ATTRIBUTE,f.session(Kind.ADMIN)).contentType("application/json").content(BODY)),400,"INVALID_REQUEST");
        assertTrue(commands.isEmpty());
    }
}
