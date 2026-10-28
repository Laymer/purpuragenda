package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.AdministratorHistoryQuery.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.port.out.SessionStore.*;
import com.purpuragenda.application.scheduling.AdministratorNotes;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class AdministratorHistoryHttpTest {
    static final JsonMapper JSON=JsonMapper.builder().build();static final Instant NOW=Instant.parse("2026-10-28T12:00:00Z");
    final AdministratorBookingHttpTest auth=new AdministratorBookingHttpTest();final UUID booking=UUID.randomUUID(),note=UUID.randomUUID();
    final String path="/api/v1/admin/bookings/"+booking+"/notes";Cursor next;Request received;UUID filter;RuntimeException failure;Code code=Code.OK;int writes;
    final AdministratorHistoryQuery query=new AdministratorHistoryQuery(){
        void check(UUID id,Request request){if(failure!=null)throw failure;filter=id;received=request;}
        public NotePage notes(SessionBinding session,UUID id,Request request){check(id,request);return new NotePage(List.of(new Note(note,"Synthetic <private>\ntext",NOW)),next,NOW);}
        public AuditPage audit(SessionBinding session,UUID id,Request request){check(id,request);return new AuditPage(List.of(new Audit(note,id,"ADMIN","NOTE_ADDED","",NOW,null)),next,NOW);}
    };
    final AdministratorNotes notes=new AdministratorNotes((command,work)->{writes++;if(failure!=null)throw failure;return code==Code.OK?new Receipt(Code.OK,note,1):Receipt.failure(code);});
    MockMvc mvc;
    @BeforeEach void setup(){mvc=MockMvcBuilders.standaloneSetup(new AdministratorHistoryController(query,new SessionAccess(auth.sessions),SessionCookies.operational()),new AdministratorNoteController(notes)).build();}
    MockHttpServletRequestBuilder read(String path){return get(path).cookie(new Cookie("__Host-purpuragenda",AdministratorBookingHttpTest.COOKIE));}
    MockHttpServletRequestBuilder write(String body){return post(path).requestAttr(ApiGuardFilter.SESSION_ATTRIBUTE,new AdministratorFinanceHttpTest().session(Kind.ADMIN)).header("Idempotency-Key","synthetic-note").contentType("application/json").content(body);}
    MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception{return mvc.perform(request).andReturn().getResponse();}
    void problem(MockHttpServletResponse response,int status,String code) throws Exception {assertEquals(status,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals(code,JSON.readTree(response.getContentAsString()).get("code").textValue());assertFalse(response.getContentAsString().contains("Synthetic"));}
    @Test void explicitlyMapsPrivatePagesAndNeverReturnsNotModified() throws Exception {
        next=new Cursor(NOW,note);var response=send(read(path).header("If-None-Match","*").header("If-Modified-Since","Wed, 28 Oct 2037 12:00:00 GMT"));assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(response.getHeader("ETag"));
        var body=JSON.readTree(response.getContentAsString());assertEquals(3,body.size());assertEquals("Synthetic <private>\ntext",body.get("entries").get(0).get("text").textValue());assertEquals(50,received.limit());assertEquals(booking,filter);
        String cursor=body.get("nextCursor").textValue();send(read(path).param("cursor",cursor).param("limit","100"));assertEquals(next,received.before());assertEquals(100,received.limit());
        var audit=JSON.readTree(send(read("/api/v1/admin/audit")).getContentAsString());assertTrue(audit.get("entries").get(0).get("bookingId").isNull());assertFalse(audit.get("entries").get(0).has("metadata"));assertTrue(audit.get("entries").get(0).get("version").isNull());
        next=null;send(read("/api/v1/admin/audit").param("bookingId",booking.toString()));assertEquals(booking,filter);
    }
    @Test void rejectsUnknownDuplicateEmptyOverlongAndNoncanonicalQueryValues() throws Exception {
        for(String limit:List.of("0","101","1000","01","-1","1.0",""))problem(send(read(path).param("limit",limit)),400,"INVALID_REQUEST");
        problem(send(read(path).param("limit","1","2")),400,"INVALID_REQUEST");problem(send(read(path).param("bookingId",booking.toString())),400,"INVALID_REQUEST");problem(send(read("/api/v1/admin/audit").param("bookingId","bad")),400,"INVALID_REQUEST");
        for(String cursor:List.of("!","a".repeat(201),"a",Base64.getUrlEncoder().withoutPadding().encodeToString("bad|bad".getBytes(StandardCharsets.US_ASCII)),Base64.getUrlEncoder().withoutPadding().encodeToString((NOW+"|"+note+"|extra").getBytes(StandardCharsets.US_ASCII)),Base64.getUrlEncoder().withoutPadding().encodeToString(("2026-10-28T12:00:00.000Z|"+note).getBytes(StandardCharsets.US_ASCII))))problem(send(read(path).param("cursor",cursor)),400,"INVALID_REQUEST");
        assertThrows(BookingHttp.Invalid.class,()->AdministratorHistoryHttp.cursor("!"));assertThrows(BookingHttp.Invalid.class,()->AdministratorHistoryHttp.cursor("a".repeat(201)));
    }
    @Test void requiresAdministratorAuthorityForBothKindsOfBoundary() throws Exception {
        problem(send(get(path)),401,"SESSION_REQUIRED");problem(send(post(path)),401,"SESSION_REQUIRED");
        for(Kind kind:List.of(Kind.ANONYMOUS,Kind.GUEST,Kind.ACCOUNT)){auth.kind=kind;problem(send(read(path)),403,"ADMIN_ACCESS_REQUIRED");problem(send(write("{\"text\":\"Synthetic note\"}").requestAttr(ApiGuardFilter.SESSION_ATTRIBUTE,new AdministratorFinanceHttpTest().session(kind))),403,"ADMIN_ACCESS_REQUIRED");}
        assertEquals(0,writes);assertThrows(NullPointerException.class,()->new AdministratorNoteController(null));
    }
    @Test void noteAppendReturnsItsOwnReceiptAndRejectsUnsupportedBookingPreconditions() throws Exception {
        var response=send(write("{\"text\":\"Synthetic note\"}"));assertEquals(201,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(response.getHeader("ETag"));var body=JSON.readTree(response.getContentAsString());assertEquals(note.toString(),body.get("noteId").textValue());assertEquals(booking.toString(),body.get("bookingId").textValue());assertEquals(1,body.get("version").asLong());assertEquals(3,body.size());
        problem(send(write("{\"text\":\"Synthetic note\"}").header("If-Match","\"1\"")),400,"INVALID_REQUEST");problem(send(write("{\"text\":\"Synthetic note\"}").param("extra","1")),400,"INVALID_REQUEST");
        for(String invalid:List.of("{}","{\"text\":null}","{\"text\":1}","{\"text\":\"\"}","{\"text\":\"one\",\"text\":\"two\"}","{\"text\":\"one\",\"actorId\":\"bad\"}","{\"text\":\"one\"}{}","{\"text\":\""+"x".repeat(4001)+"\"}"))problem(send(write(invalid)),400,"INVALID_REQUEST");assertEquals(1,writes);
        code=Code.IDEMPOTENCY_KEY_REUSED;problem(send(write("{\"text\":\"Synthetic note\"}")),409,"IDEMPOTENCY_KEY_REUSED");
    }
    @Test void mapsAuthorityStorageAndUnexpectedFailuresWithoutProviderDetails() throws Exception {
        for(var kind:Failure.Kind.values()) {
            failure=new Failure(kind);int status=switch(kind){case AUTHENTICATION_REQUIRED->401;case FORBIDDEN,FRESH_AUTHENTICATION_REQUIRED->403;case NOT_FOUND->404;default->503;};
            String code=switch(kind){case AUTHENTICATION_REQUIRED->"SESSION_REQUIRED";case FORBIDDEN->"ADMIN_ACCESS_REQUIRED";case FRESH_AUTHENTICATION_REQUIRED->"AUTHENTICATION_REQUIRED";case NOT_FOUND->"BOOKING_NOT_FOUND";default->"TEMPORARILY_UNAVAILABLE";};
            problem(send(read(path)),status,code);problem(send(write("{\"text\":\"Synthetic note\"}")),status,code);
        }
        failure=new IllegalStateException("Synthetic private provider");problem(send(read(path)),503,"TEMPORARILY_UNAVAILABLE");problem(send(write("{\"text\":\"Synthetic note\"}")),503,"TEMPORARILY_UNAVAILABLE");
    }
}
