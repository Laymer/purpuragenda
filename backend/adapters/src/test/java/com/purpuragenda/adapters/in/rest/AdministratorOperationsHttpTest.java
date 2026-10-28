package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.AdministratorOperationsQuery.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.port.out.SessionStore.Kind;
import com.purpuragenda.application.scheduling.AdministratorNotificationRetry;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class AdministratorOperationsHttpTest {
    static final JsonMapper JSON=JsonMapper.builder().build();static final Instant NOW=Instant.parse("2026-10-28T12:00:00Z");static final String BASE="/api/v1/admin",BODY="{\"reason\":\"Synthetic retry reason\"}";
    final AdministratorBookingHttpTest auth=new AdministratorBookingHttpTest();final UUID booking=UUID.randomUUID(),id=UUID.randomUUID(),audit=UUID.randomUUID();final String path=BASE+"/notifications/"+id+"/retry";
    UUID next,filter;NotificationState state;Request received;RuntimeException failure;Code code=Code.OK;int writes;
    final AdministratorOperationsQuery query=new AdministratorOperationsQuery(){
        void check(UUID target,Request request){if(failure!=null)throw failure;filter=target;received=request;}
        public Page<Refund> reconciliation(SessionBinding session,UUID target,Request request){check(target,request);return new Page<>(List.of(new Refund(id,booking,"SYNTHETIC","CANCELLED","EUR",1000,400,1000,600,"LATE_RECEIPT",NOW)),next,NOW);}
        public Page<Notification> notifications(SessionBinding session,UUID target,NotificationState requested,Request request){check(target,request);state=requested;return new Page<>(List.of(new Notification(id,booking,"SYNTHETIC","CUSTOMER","BOOKING_CREATED",1,NotificationState.FAILED,7,NOW,null)),next,NOW);}
    };
    final AdministratorNotificationRetry retry=new AdministratorNotificationRetry((command,work)->{writes++;if(failure!=null)throw failure;assertEquals(id,command.jobId());assertEquals(CommandKey.fingerprint(List.of("7","Synthetic retry reason")),command.fingerprint());return code==Code.OK?new Receipt(Code.OK,audit,1):Receipt.failure(code);});
    MockMvc mvc;
    @BeforeEach void setup(){mvc=MockMvcBuilders.standaloneSetup(new AdministratorOperationsController(query,new SessionAccess(auth.sessions),SessionCookies.operational()),new AdministratorNotificationRetryController(retry)).build();}
    MockHttpServletRequestBuilder read(String suffix){return get(BASE+suffix).cookie(new Cookie("__Host-purpuragenda",AdministratorBookingHttpTest.COOKIE));}
    MockHttpServletRequestBuilder write(){return post(path).requestAttr(ApiGuardFilter.SESSION_ATTRIBUTE,new AdministratorFinanceHttpTest().session(Kind.ADMIN)).header("Idempotency-Key","synthetic-retry").header("If-Match","\"7\"").contentType("application/json").content(BODY);}
    MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception{return mvc.perform(request).andReturn().getResponse();}
    void problem(MockHttpServletResponse response,int status,String code) throws Exception{assertEquals(status,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals(code,JSON.readTree(response.getContentAsString()).get("code").textValue());assertFalse(response.getContentAsString().contains("Synthetic"));}
    @Test void mapsOnlyPrivateOperationalFieldsWithBoundedFiltersAndNoConditionalCaching() throws Exception {
        next=id;var response=send(read("/reconciliation").header("If-None-Match","*").header("If-Modified-Since","Wed, 28 Oct 2037 12:00:00 GMT"));assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(response.getHeader("ETag"));var body=JSON.readTree(response.getContentAsString());assertEquals(3,body.size());assertEquals(11,body.at("/entries/0").size());assertEquals(1000,body.at("/entries/0/dueMinor").asLong());assertEquals(600,body.at("/entries/0/outstandingMinor").asLong());assertEquals(50,received.limit());assertNull(filter);
        send(read("/reconciliation").param("bookingId",booking.toString()).param("limit","100").param("cursor",id.toString()));assertEquals(booking,filter);assertEquals(new Request(id,100),received);
        next=null;body=JSON.readTree(send(read("/notifications")).getContentAsString());assertEquals(10,body.at("/entries/0").size());assertTrue(body.at("/entries/0/safeCode").isNull());assertTrue(body.get("nextCursor").isNull());assertNull(state);
        send(read("/notifications").param("state","FAILED").param("bookingId",booking.toString()));assertEquals(NotificationState.FAILED,state);assertEquals(booking,filter);
    }
    @Test void rejectsUnknownDuplicateEmptyOversizedAndMalformedReadParameters() throws Exception {
        for(String suffix:List.of("/reconciliation","/notifications")){
            for(String value:List.of("0","101","01","1000",""))problem(send(read(suffix).param("limit",value)),400,"INVALID_REQUEST");
            problem(send(read(suffix).param("limit","1","2")),400,"INVALID_REQUEST");problem(send(read(suffix).param("extra","x")),400,"INVALID_REQUEST");problem(send(read(suffix).param("bookingId","bad")),400,"INVALID_REQUEST");problem(send(read(suffix).param("cursor","x".repeat(201))),400,"INVALID_REQUEST");problem(send(read(suffix).param("cursor","bad")),400,"INVALID_REQUEST");
        }
        problem(send(read("/reconciliation").param("state","FAILED")),400,"INVALID_REQUEST");problem(send(read("/notifications").param("state","failed")),400,"INVALID_REQUEST");
    }
    @Test void retryRequiresStrictStrongAttemptPreconditionsReasonAndIdempotency() throws Exception {
        var response=send(write());assertEquals(202,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(response.getHeader("ETag"));var body=JSON.readTree(response.getContentAsString());assertEquals(3,body.size());assertEquals(id.toString(),body.get("jobId").asText());assertEquals(audit.toString(),body.get("auditId").asText());assertEquals(1,body.get("version").asLong());
        problem(send(post(path).requestAttr(ApiGuardFilter.SESSION_ATTRIBUTE,new AdministratorFinanceHttpTest().session(Kind.ADMIN))),428,"VERSION_REQUIRED");
        for(String match:List.of("W/\"7\"","7","*","\"0\"","\"2147483647\"","\"9999999999999999999\""))problem(send(post(path).requestAttr(ApiGuardFilter.SESSION_ATTRIBUTE,new AdministratorFinanceHttpTest().session(Kind.ADMIN)).header("If-Match",match).header("Idempotency-Key","key").contentType("application/json").content(BODY)),400,"INVALID_REQUEST");
        problem(send(write().header("If-Match","\"8\"")),400,"INVALID_REQUEST");problem(send(write().header("Idempotency-Key","other")),400,"INVALID_REQUEST");problem(send(write().param("extra","x")),400,"INVALID_REQUEST");
        for(String invalid:List.of("{}","{\"reason\":null}","{\"reason\":1}","{\"reason\":\"short\"}",BODY+"{}","{\"reason\":\"Synthetic one\",\"reason\":\"Synthetic two\"}","{\"reason\":\"Synthetic reason\",\"attempts\":7}","{\"reason\":\""+"x".repeat(17000)+"\"}"))problem(send(write().content(invalid)),400,"INVALID_REQUEST");assertEquals(1,writes);
        for(Code value:List.of(Code.STALE_VERSION,Code.DELIVERY_SUPPRESSED,Code.NOTIFICATION_NOT_RETRYABLE,Code.IDEMPOTENCY_KEY_REUSED)){code=value;problem(send(write()),value==Code.STALE_VERSION?412:409,value.name());}
    }
    @Test void bothBoundariesRequireAdministratorAuthorityAndMapPrivateFailuresSafely() throws Exception {
        problem(send(get(BASE+"/notifications")),401,"SESSION_REQUIRED");problem(send(post(path)),401,"SESSION_REQUIRED");
        for(Kind kind:List.of(Kind.ANONYMOUS,Kind.GUEST,Kind.ACCOUNT)){auth.kind=kind;problem(send(read("/reconciliation")),403,"ADMIN_ACCESS_REQUIRED");problem(send(write().requestAttr(ApiGuardFilter.SESSION_ATTRIBUTE,new AdministratorFinanceHttpTest().session(kind))),403,"ADMIN_ACCESS_REQUIRED");}assertEquals(0,writes);auth.kind=Kind.ADMIN;
        for(var kind:Failure.Kind.values()){
            failure=new Failure(kind);int status=switch(kind){case AUTHENTICATION_REQUIRED->401;case FORBIDDEN,FRESH_AUTHENTICATION_REQUIRED->403;case NOT_FOUND->404;default->503;};String code=switch(kind){case AUTHENTICATION_REQUIRED->"SESSION_REQUIRED";case FORBIDDEN->"ADMIN_ACCESS_REQUIRED";case FRESH_AUTHENTICATION_REQUIRED->"AUTHENTICATION_REQUIRED";case NOT_FOUND->"BOOKING_NOT_FOUND";default->"TEMPORARILY_UNAVAILABLE";};problem(send(read("/notifications")),status,code);problem(send(write()),status,kind==Failure.Kind.NOT_FOUND?"NOTIFICATION_NOT_FOUND":code);
        }
        failure=new IllegalStateException("Synthetic private provider");problem(send(read("/notifications")),503,"TEMPORARILY_UNAVAILABLE");problem(send(write()),503,"TEMPORARILY_UNAVAILABLE");
    }
}
