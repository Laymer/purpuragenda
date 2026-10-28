package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.AdministratorCustomerQuery.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import com.purpuragenda.application.port.out.SessionStore.Kind;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.booking.Money;
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

class AdministratorCustomerHttpTest {
    static final JsonMapper JSON=JsonMapper.builder().build();static final Instant NOW=Instant.parse("2026-10-28T12:00:00Z");static final String PATH="/api/v1/admin/customers";
    final AdministratorBookingHttpTest auth=new AdministratorBookingHttpTest();final UUID id=UUID.randomUUID(),booking=UUID.randomUUID();final Customer customer=new Customer(id,"synthetic@example.test",true);
    Search received;AdministratorHistoryQuery.Request history;UUID next;RuntimeException failure;int reads;
    final AdministratorCustomerQuery query=new AdministratorCustomerQuery(){
        void check(){reads++;if(failure!=null)throw failure;}
        public Page search(SessionBinding session,Search request){check();received=request;return new Page(List.of(customer),next,NOW);}
        public Bookings bookings(SessionBinding session,UUID target,AdministratorHistoryQuery.Request request){check();assertEquals(id,target);history=request;return new Bookings(customer,new AccountDataStore.History(List.of(new AccountDataStore.BookingSummary(booking,"SYNTHETIC",Booking.State.CONFIRMED,NOW,NOW.plusSeconds(3600),false,"Synthetic service",new Money(100,"EUR"))),next==null?null:new AccountDataStore.Cursor(NOW,next),NOW));}
    };
    MockMvc mvc;
    @BeforeEach void setup(){mvc=MockMvcBuilders.standaloneSetup(new AdministratorCustomerController(query,new SessionAccess(auth.sessions),SessionCookies.operational())).build();}
    MockHttpServletRequestBuilder read(String path){return get(path).cookie(new Cookie("__Host-purpuragenda",AdministratorBookingHttpTest.COOKIE));}
    MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception{return mvc.perform(request).andReturn().getResponse();}
    void problem(MockHttpServletResponse response,int status,String code) throws Exception{assertEquals(status,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals(code,JSON.readTree(response.getContentAsString()).get("code").textValue());assertFalse(response.getContentAsString().contains("synthetic"));}
    @Test void mapsOnlyMinimalCustomerFieldsAndCanonicalPaginationWithoutConditionalCaching() throws Exception {
        next=id;var response=send(read(PATH).param("query","  Synthetic  ").header("If-None-Match","*").header("If-Modified-Since","Wed, 28 Oct 2037 12:00:00 GMT"));assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(response.getHeader("ETag"));
        var body=JSON.readTree(response.getContentAsString());assertEquals(3,body.size());assertEquals(3,body.at("/entries/0").size());assertEquals(customer.email(),body.at("/entries/0/email").asText());assertTrue(body.at("/entries/0/enrolled").asBoolean());assertEquals("Synthetic",received.query());assertEquals(50,received.limit());assertNull(received.after());
        send(read(PATH).param("query","😀".repeat(254)).param("limit","100").param("cursor",body.get("nextCursor").asText()));assertEquals(id,received.after());assertEquals(100,received.limit());
        next=null;assertTrue(JSON.readTree(send(read(PATH).param("query","ok")).getContentAsString()).get("nextCursor").isNull());
    }
    @Test void mapsImmutableBookingSummariesAndRoundTripsTimeAndIdCursor() throws Exception {
        next=booking;String path=PATH+"/"+id+"/bookings";var response=send(read(path));assertEquals(200,response.getStatus());var body=JSON.readTree(response.getContentAsString());assertEquals(4,body.size());assertEquals(id.toString(),body.at("/customer/id").asText());assertEquals(9,body.at("/bookings/0").size());assertEquals(100,body.at("/bookings/0/priceMinor").asInt());assertEquals("CONFIRMED",body.at("/bookings/0/state").asText());
        send(read(path).param("cursor",body.get("nextCursor").asText()).param("limit","1"));assertEquals(new AdministratorHistoryQuery.Cursor(NOW,booking),history.before());assertEquals(1,history.limit());
        next=null;assertTrue(JSON.readTree(send(read(path)).getContentAsString()).get("nextCursor").isNull());
    }
    @Test void rejectsMissingUnknownDuplicateMalformedAndOversizedParametersBeforeQuerying() throws Exception {
        problem(send(read(PATH)),400,"INVALID_REQUEST");
        for(String value:List.of("","x"," ","x".repeat(255),"x".repeat(509),"bad\nquery"))problem(send(read(PATH).param("query",value)),400,"INVALID_REQUEST");
        for(String value:List.of("0","101","01","1000",""))problem(send(read(PATH).param("query","ok").param("limit",value)),400,"INVALID_REQUEST");
        problem(send(read(PATH).param("query","ok","two")),400,"INVALID_REQUEST");problem(send(read(PATH).param("query","ok").param("unknown","x")),400,"INVALID_REQUEST");
        for(String value:List.of("bad","x".repeat(201),""))problem(send(read(PATH).param("query","ok").param("cursor",value)),400,"INVALID_REQUEST");
        problem(send(read(PATH+"/bad/bookings")),400,"INVALID_REQUEST");problem(send(read(PATH+"/"+id+"/bookings").param("query","ok")),400,"INVALID_REQUEST");assertEquals(0,reads);
    }
    @Test void requiresAdministratorAndMapsStorageAndAuthorityFailuresWithoutDetails() throws Exception {
        problem(send(get(PATH).param("query","ok")),401,"SESSION_REQUIRED");
        for(Kind kind:List.of(Kind.ACCOUNT,Kind.GUEST,Kind.ANONYMOUS)){auth.kind=kind;problem(send(read(PATH).param("query","ok")),403,"ADMIN_ACCESS_REQUIRED");}assertEquals(0,reads);auth.kind=Kind.ADMIN;
        for(var kind:Failure.Kind.values()) {
            failure=new Failure(kind);int status=switch(kind){case AUTHENTICATION_REQUIRED->401;case FORBIDDEN,FRESH_AUTHENTICATION_REQUIRED->403;case NOT_FOUND->404;default->503;};
            String code=switch(kind){case AUTHENTICATION_REQUIRED->"SESSION_REQUIRED";case FORBIDDEN->"ADMIN_ACCESS_REQUIRED";case FRESH_AUTHENTICATION_REQUIRED->"AUTHENTICATION_REQUIRED";case NOT_FOUND->"CUSTOMER_NOT_FOUND";default->"TEMPORARILY_UNAVAILABLE";};
            problem(send(read(PATH).param("query","ok")),status,code);problem(send(read(PATH+"/"+id+"/bookings")),status,code);
        }
        failure=new IllegalStateException("synthetic provider secret");problem(send(read(PATH).param("query","ok")),503,"TEMPORARILY_UNAVAILABLE");
    }
}
