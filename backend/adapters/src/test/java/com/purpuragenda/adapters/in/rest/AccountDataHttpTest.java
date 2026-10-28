package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.*;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.AccountDataStore.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.port.out.SessionStore.Kind;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.*;
import jakarta.servlet.http.Cookie;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class AccountDataHttpTest {
    static final String BASE="/api/v1/account",BODY="{\"firstName\":\"Synthetic\",\"lastName\":\"Customer\",\"telephone\":\"+32400123456\"}";
    final UUID id=UUID.randomUUID();final Instant now=Instant.parse("2026-11-01T10:00:00Z");Kind kind=Kind.ACCOUNT;
    boolean missing,more=true;RuntimeException failure;Code code=Code.OK;int calls,limit;Cursor cursor;Fields fields;
    final AccountDataStore store=new AccountDataStore() {
        void called() { calls++;if(failure!=null)throw failure; }
        public Snapshot profile(SessionBinding session) { called();return new Snapshot("synthetic@example.test",Profile.empty(),now); }
        public History history(SessionBinding session,Cursor before,int count) { called();cursor=before;limit=count;return new History(List.of(new BookingSummary(id,"SYNTHETIC",Booking.State.CONFIRMED,now,now.plusSeconds(3600),false,"Synthetic service",new Money(1000,"EUR"))),more?new Cursor(now,id):null,now); }
        public Receipt change(Command command,UnaryOperator<Profile> update) { called();if(code!=Code.OK)return Receipt.failure(code);var value=update.apply(Profile.empty());fields=value.fields();return new Receipt(Code.OK,id,value.version()); }
    };
    MockMvc mvc;
    SessionStore.Snapshot session() { return new SessionStore.Snapshot(new SessionStore.SessionReference(id,"a".repeat(64)),kind,null,id,id,null,null,now,now,now.plusSeconds(43200),now); }
    @BeforeEach void setup() {
        var sessions=(SessionStore)Proxy.newProxyInstance(SessionStore.class.getClassLoader(),new Class<?>[]{SessionStore.class},(p,m,a)->{
            assertEquals("inspect",m.getName());return missing?Optional.empty():Optional.of(session());
        });
        var data=new AccountData(store);mvc=MockMvcBuilders.standaloneSetup(new AccountDataController(data,new SessionAccess(sessions),SessionCookies.operational()),new AccountProfileController(data)).build();
    }
    MockHttpServletRequestBuilder read(String path) { return get(BASE+path).cookie(new Cookie("__Host-purpuragenda","a".repeat(43))); }
    MockHttpServletRequestBuilder write(String body) { return post(BASE+"/profile").requestAttr(ApiGuardFilter.SESSION_ATTRIBUTE,session()).header("Idempotency-Key","synthetic-key").header("If-Match","\"1\"").contentType("application/json").content(body); }
    MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception { return mvc.perform(request).andReturn().getResponse(); }
    void problem(MockHttpServletResponse response,int status,String code) throws Exception {
        assertEquals(status,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertTrue(response.getContentAsString().contains("\"code\":\""+code+"\""));assertFalse(response.getContentAsString().contains("synthetic"));
    }
    @Test void profileAndHistoryReturnExplicitPrivateDocumentsAndIgnoreCacheValidators() throws Exception {
        var profile=send(read("/profile").header("If-None-Match","\"1\""));assertEquals(200,profile.getStatus());assertEquals("\"1\"",profile.getHeader("ETag"));assertEquals("no-store",profile.getHeader("Cache-Control"));assertTrue(profile.getContentAsString().contains("synthetic@example.test"));assertFalse(profile.getContentAsString().contains("customerId"));
        var history=send(read("/history"));assertEquals(200,history.getStatus());assertEquals(20,limit);assertNull(cursor);assertNull(history.getHeader("ETag"));assertTrue(history.getContentAsString().contains("beforeId"));assertFalse(history.getContentAsString().contains("telephone"));
        more=false;history=send(read("/history").param("limit","100").param("beforeStart",now.toString()).param("beforeId",id.toString()));assertEquals(100,limit);assertEquals(new Cursor(now,id),cursor);assertTrue(history.getContentAsString().contains("\"next\":null"));
    }
    @Test void profileWritesReturnOnlyVersionAndNormalizeOptionalFields() throws Exception {
        var response=send(write(BODY));assertEquals(200,response.getStatus());assertEquals("{\"version\":2}",response.getContentAsString());assertEquals("\"2\"",response.getHeader("ETag"));assertEquals("Synthetic",fields.firstName());
        assertEquals(200,send(write("{\"firstName\":\"\",\"lastName\":\"\",\"telephone\":\"\"}")).getStatus());
        for(var value:Map.of(Code.STALE_VERSION,412,Code.IDEMPOTENCY_KEY_REUSED,409).entrySet()) { code=value.getKey();problem(send(write(BODY)),value.getValue(),code.name()); }
    }
    @Test void missingAndWrongKindsNeverReachAccountData() throws Exception {
        missing=true;for(String path:List.of("/profile","/history"))problem(send(read(path)),401,"SESSION_REQUIRED");missing=false;
        problem(send(post(BASE+"/profile")),401,"SESSION_REQUIRED");
        for(Kind wrong:List.of(Kind.ANONYMOUS,Kind.GUEST,Kind.ADMIN)) { kind=wrong;problem(send(write(BODY)),403,"ACCOUNT_ACCESS_REQUIRED");for(String path:List.of("/profile","/history"))problem(send(read(path)),403,"ACCOUNT_ACCESS_REQUIRED"); }
        assertEquals(0,calls);
    }
    @Test void queriesAreStrictBoundedAndRequireBothCursorParts() throws Exception {
        problem(send(read("/profile").param("unexpected","1")),400,"INVALID_REQUEST");
        for(String value:List.of("0","101","1000","-1","01","","NaN"))problem(send(read("/history").param("limit",value)),400,"INVALID_REQUEST");
        for(var request:List.of(read("/history").param("limit","1","2"),read("/history").param("unknown","1"),read("/history").param("beforeId",id.toString()),read("/history").param("beforeStart",now.toString()),read("/history").param("beforeStart","bad").param("beforeId",id.toString()),read("/history").param("beforeStart",now.toString()).param("beforeId","bad"),read("/history").param("beforeStart","2026-11-01T10:00:00.000000001Z").param("beforeId",id.toString())))problem(send(request),400,"INVALID_REQUEST");
        assertEquals(0,calls);
    }
    @Test void commandBodiesAndPreconditionsAreStrict() throws Exception {
        problem(send(post(BASE+"/profile").requestAttr(ApiGuardFilter.SESSION_ATTRIBUTE,session())),428,"VERSION_REQUIRED");
        for(String body:List.of("{}",BODY+"{}",BODY.replace("\"Synthetic\"","null"),BODY.replace("+32400123456","bad"),BODY.replace("\"firstName\":","\"extra\":0,\"firstName\":"),BODY.replace("\"firstName\":","\"firstName\":\"other\",\"firstName\":"),"x".repeat(16385)))problem(send(write(body)),400,"INVALID_REQUEST");
        problem(send(write(BODY).param("unexpected","1")),400,"INVALID_REQUEST");
        problem(send(write(BODY).contentType("text/plain")),400,"INVALID_REQUEST");
        for(String match:List.of("W/\"1\"","*","\"0\"","\"9223372036854775808\""))problem(send(write(BODY).with(r->{r.removeHeader("If-Match");r.addHeader("If-Match",match);return r;})),400,"INVALID_REQUEST");
        problem(send(write(BODY).with(r->{r.removeHeader("Idempotency-Key");return r;})),400,"INVALID_REQUEST");assertEquals(0,calls);
    }
    @Test void authorityAndUnexpectedErrorsAreMappedWithoutProviderDetails() throws Exception {
        for(var type:Failure.Kind.values()) {
            failure=new Failure(type);int status=type==Failure.Kind.AUTHENTICATION_REQUIRED?401:type==Failure.Kind.FORBIDDEN?403:503;String code=status==401?"SESSION_REQUIRED":status==403?"ACCOUNT_ACCESS_REQUIRED":"TEMPORARILY_UNAVAILABLE";
            problem(send(write(BODY)),status,code);for(String path:List.of("/profile","/history"))problem(send(read(path)),status,code);
        }
        for(var error:List.of(new DomainFailure("synthetic"),new IllegalArgumentException("synthetic"),new DateTimeException("synthetic"),new ArithmeticException("synthetic"),new IllegalStateException("synthetic"))) {
            failure=error;int status=error instanceof IllegalStateException?503:400;for(String path:List.of("/profile","/history"))problem(send(read(path)),status,status==400?"INVALID_REQUEST":"TEMPORARILY_UNAVAILABLE");problem(send(write(BODY)),status,status==400?"INVALID_REQUEST":"TEMPORARILY_UNAVAILABLE");
        }
    }
}
