package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.AdministratorBookingQuery.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import com.purpuragenda.application.port.out.SessionStore.*;
import com.purpuragenda.application.scheduling.AdministratorBookingView;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.customer.Intake;
import com.purpuragenda.domain.payment.PaymentLedger;
import com.purpuragenda.domain.scheduling.SchedulingPolicy;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class AdministratorBookingHttpTest {
    static final Instant NOW=Instant.parse("2026-11-01T10:00:00Z"),START=NOW.plusSeconds(3*86400);
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String PATH="/api/v1/admin/bookings",COOKIE="a".repeat(43);
    final SessionReference reference=new SessionReference(UUID.randomUUID(),"b".repeat(64));
    SessionStore.Kind kind=SessionStore.Kind.ADMIN;boolean inspectFails,queryFails,missing;Failure.Kind denial;
    int inspections,agendas,details;Cursor next;char[] borrowed;
    final Booking booking=Booking.pending(UUID.fromString("11111111-1111-4111-8111-abcdefabcdef"),"synthetic-reference",UUID.randomUUID(),new Booking.Snapshot(
            new ServiceDefinition(UUID.randomUUID(),1,"Synthetic <service>",45,new Money(1500,"EUR"),true),SchedulingPolicy.defaults(),
            new Intake("Synthetic","Customer","+32400123456",LocalDate.of(1990,1,1),LocalTime.of(12,30,15),"Synthetic city","Synthetic <message>"),
            "original@example.test","Europe/Brussels","local-v1"),Booking.Origin.CUSTOMER,NOW,START);
    PaymentLedger ledger=PaymentLedger.empty();
    final SessionStore sessions=new SessionStore() {
        public Optional<Snapshot> inspect(char[] token) { inspections++;borrowed=token;try { if(inspectFails)throw new IllegalStateException("SYNTHETIC_PRIVATE");return token==null?Optional.empty():Optional.of(new Snapshot(reference,kind,null,null,null,UUID.randomUUID(),0L,NOW,NOW,NOW.plusSeconds(28800),NOW)); } finally { if(token!=null)Arrays.fill(token,'\0'); } }
        public Optional<Snapshot> resolve(char[] token) { throw new AssertionError("READ_MUST_NOT_TOUCH_SESSION"); }
        public Issued anonymous() { throw new AssertionError(); } public Optional<CsrfRotation> rotateCsrf(char[] token) { throw new AssertionError(); }
        public boolean csrf(SessionReference session,char[] token) { throw new AssertionError(); } public Optional<Issued> exchangeAdmin(SessionReference previous,String request,char[] proof) { throw new AssertionError(); }
        public Optional<Issued> exchangeGuest(SessionReference previous,GuestAccessStore.Redemption proof) { throw new AssertionError(); } public boolean revoke(SessionReference session) { throw new AssertionError(); }
        public Optional<Issued> exchangeAccount(SessionReference previous,String request,char[] proof) { throw new AssertionError("READ_MUST_NOT_EXCHANGE_SESSION"); }
    };
    final AdministratorBookingQuery query=new AdministratorBookingQuery() {
        void check(SessionBinding supplied) { assertEquals(reference.id(),supplied.sessionId());if(queryFails)throw new IllegalStateException("SYNTHETIC_PRIVATE");if(denial!=null)throw new Failure(denial); }
        public Page agenda(SessionBinding supplied,Window window,Cursor after,int limit) { agendas++;check(supplied);assertTrue(limit<=100);return new Page(List.of(new Entry(booking.id(),booking.reference(),booking.version(),booking.state(),booking.start(),booking.sessionEnd(),booking.snapshot().service().name())),next,NOW); }
        public Optional<Detail> detail(SessionBinding supplied,UUID id) { details++;check(supplied);return missing || !id.equals(booking.id())?Optional.empty():Optional.of(new Detail(booking,ledger,NOW)); }
    };
    final AdministratorBookingView view=new AdministratorBookingView(query);
    MockMvc mvc;
    @BeforeEach void setup() { mvc=MockMvcBuilders.standaloneSetup(new AdministratorBookingController(view,new SessionAccess(sessions),SessionCookies.operational())).build(); }
    MockHttpServletRequestBuilder agenda() { return get(PATH).param("from","2026-11-01").param("through","2026-11-30").cookie(new Cookie("__Host-purpuragenda",COOKIE)); }
    MockHttpServletRequestBuilder detail() { return get(PATH+"/"+booking.id()).cookie(new Cookie("__Host-purpuragenda",COOKIE)); }
    org.springframework.mock.web.MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception { return mvc.perform(request).andReturn().getResponse(); }
    void problem(org.springframework.mock.web.MockHttpServletResponse response,int status,String code) throws Exception {
        assertEquals(status,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(response.getHeader("Set-Cookie"));
        var body=JSON.readTree(response.getContentAsString());assertEquals(code,body.get("code").textValue());assertFalse(response.getContentAsString().contains("SYNTHETIC_PRIVATE"));
    }
    @Test void privateDetailExplicitlyMapsSnapshotsAndSeparateMoneyWithoutGenericInternalIdentifiers() throws Exception {
        UUID receipt=UUID.randomUUID();ledger=PaymentLedger.empty().append(new PaymentLedger.Entry(receipt,booking.id(),PaymentLedger.Kind.RECEIPT,new Money(2000,"USD"),"SYNTHETIC","synthetic-reference",NOW,null)).requireRefunds("SYNTHETIC");
        var response=send(detail());assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("\"1\"",response.getHeader("ETag"));assertNull(response.getHeader("Set-Cookie"));
        JsonNode body=JSON.readTree(response.getContentAsString());assertFalse(body.has("customerId") || body.has("adminId"));
        assertTrue("original@example.test".equals(body.get("snapshot").get("creationEmail").textValue()));
        assertTrue("12:30:15".equals(body.get("snapshot").get("intake").get("birthTime").textValue()));
        assertTrue("Synthetic <message>".equals(body.get("snapshot").get("intake").get("message").textValue()));
        assertTrue(body.get("meetingUrl").isNull());assertTrue(body.get("confirmationBasis").isNull());
        assertEquals(1,body.get("payment").get("entries").size());assertEquals(1,body.get("payment").get("refundObligations").size());assertEquals(2000,body.get("payment").get("refundDueByCurrency").get("USD").longValue());
        assertTrue(Arrays.equals(new char[43],borrowed),"Cookie buffer must be erased");assertEquals(1,inspections);assertEquals(1,details);
    }
    @Test void minimalAgendaReturnsOnlyItsContractAndOpaqueCursorWithoutIntakeOrContact() throws Exception {
        next=new Cursor(booking.start(),booking.id());var response=send(agenda().param("limit","1"));assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(response.getHeader("ETag"));
        var body=JSON.readTree(response.getContentAsString());var entry=body.get("entries").get(0);assertFalse(entry.has("intake") || entry.has("deliveryEmail") || entry.has("payment") || entry.has("meetingUrl"));
        assertEquals(AdministratorBookingHttp.cursor(next),body.get("nextCursor").textValue());assertEquals(next,AdministratorBookingHttp.cursor(body.get("nextCursor").textValue()));
        assertEquals(200,send(agenda().param("cursor",body.get("nextCursor").textValue())).getStatus());
        next=null;assertTrue(JSON.readTree(send(agenda()).getContentAsString()).get("nextCursor").isNull());
    }
    @Test void unknownExpiredOrWrongRoleSessionsNeverReachPrivateQuery() throws Exception {
        problem(send(get(PATH)),401,"SESSION_REQUIRED");
        for(var role:List.of(SessionStore.Kind.ANONYMOUS,SessionStore.Kind.GUEST,SessionStore.Kind.ACCOUNT)) { kind=role;problem(send(agenda()),403,"ADMIN_ACCESS_REQUIRED");problem(send(detail()),403,"ADMIN_ACCESS_REQUIRED"); }
        assertEquals(0,agendas);assertEquals(0,details);
    }
    @Test void queryRechecksPersistedAuthorityAfterCookieInspectionAndProviderErrorsStaySafe() throws Exception {
        for(var failure:List.of(Failure.Kind.AUTHENTICATION_REQUIRED,Failure.Kind.FORBIDDEN,Failure.Kind.STORAGE_FAILURE)) {
            denial=failure;problem(send(detail()),failure==Failure.Kind.AUTHENTICATION_REQUIRED?401:failure==Failure.Kind.FORBIDDEN?403:503,failure==Failure.Kind.AUTHENTICATION_REQUIRED?"SESSION_REQUIRED":failure==Failure.Kind.FORBIDDEN?"ADMIN_ACCESS_REQUIRED":"TEMPORARILY_UNAVAILABLE");
        }
        denial=null;queryFails=true;var unavailable=send(agenda());problem(unavailable,503,"TEMPORARILY_UNAVAILABLE");assertEquals("1",unavailable.getHeader("Retry-After"));
        queryFails=false;inspectFails=true;problem(send(detail()),503,"TEMPORARILY_UNAVAILABLE");
    }
    @Test void malformedDuplicateUnknownAndUnboundedAgendaParametersFailBeforeAnyQuery() throws Exception {
        for(String limit:List.of("0","01","101","1000","-1","1.5"," 1",""))problem(send(agenda().param("limit",limit)),400,"INVALID_REQUEST");
        problem(send(agenda().param("limit","1","2")),400,"INVALID_REQUEST");problem(send(agenda().param("unexpected","1")),400,"INVALID_REQUEST");
        problem(send(get(PATH).param("from","2026-11-01").cookie(new Cookie("__Host-purpuragenda",COOKIE))),400,"INVALID_REQUEST");
        for(String date:List.of("2026-1-01","2026-02-30","2026-11-01","2026-12-02"))problem(send(get(PATH).param("from",date).param("through","2026-10-31").cookie(new Cookie("__Host-purpuragenda",COOKIE))),400,"INVALID_REQUEST");
        problem(send(get(PATH).param("from","2026-11-01").param("through","2026-12-02").cookie(new Cookie("__Host-purpuragenda",COOKIE))),400,"INVALID_REQUEST");
        assertEquals(0,agendas);
    }
    @Test void cursorEncodingIsCanonicalBoundedAndNeverAnAuthorizationCapability() throws Exception {
        String uppercase=Base64.getUrlEncoder().withoutPadding().encodeToString((START+"|"+booking.id().toString().toUpperCase(Locale.ROOT)).getBytes(StandardCharsets.US_ASCII));
        for(String cursor:List.of("=","x".repeat(201),"a",uppercase,Base64.getUrlEncoder().withoutPadding().encodeToString("not-time|not-id".getBytes(StandardCharsets.US_ASCII)),AdministratorBookingHttp.cursor(new Cursor(NOW.minusSeconds(86400),booking.id()))))
            problem(send(agenda().param("cursor",cursor)),400,"INVALID_REQUEST");
        var request=new MockHttpServletRequest();request.addParameter("from","2026-11-01");request.addParameter("through","2026-11-30");assertEquals("AdministratorAgendaParameters[REDACTED]",AdministratorBookingHttp.parameters(request).toString());
        missing=true;problem(send(detail()),404,"BOOKING_NOT_FOUND");problem(send(get(PATH+"/1-1-1-1-1").cookie(new Cookie("__Host-purpuragenda",COOKIE))),400,"INVALID_REQUEST");problem(send(detail().param("unexpected","1")),400,"INVALID_REQUEST");
    }
    @Test void confirmedHistoryAndRefundEntriesRetainTheirIndependentWireFacts() throws Exception {
        var confirmed=Booking.confirmed(booking.id(),booking.reference(),booking.customerId(),booking.snapshot(),Booking.Origin.ADMINISTRATOR,NOW,START,Meeting.parse("https://zoom.us/j/123456789",Set.of("zoom.us")));
        UUID receipt=UUID.randomUUID();var money=PaymentLedger.empty().append(new PaymentLedger.Entry(receipt,booking.id(),PaymentLedger.Kind.RECEIPT,new Money(1500,"EUR"),"SYNTHETIC","synthetic-paid",NOW,null))
                .append(new PaymentLedger.Entry(UUID.randomUUID(),booking.id(),PaymentLedger.Kind.REFUND,new Money(500,"EUR"),"SYNTHETIC","synthetic-refund",NOW,receipt)).requireRefunds("SYNTHETIC");
        var body=JSON.readTree(AdministratorBookingHttp.detail(new Detail(confirmed,money,NOW)));
        assertTrue(body.get("pendingExpiresAt").isNull());assertFalse(body.get("pendingExpired").booleanValue());assertFalse(body.get("meetingUrl").isNull());assertEquals("MANUAL_APPROVAL",body.get("confirmationBasis").textValue());
        assertEquals(receipt.toString(),body.get("payment").get("entries").get(1).get("receiptId").textValue());assertEquals("REFUND",body.get("payment").get("entries").get(1).get("kind").textValue());assertEquals(1000,body.get("payment").get("refundDueByCurrency").get("EUR").longValue());
    }
    @Test void constructorsRejectMissingDependencies() {
        assertThrows(NullPointerException.class,()->new AdministratorBookingController(null,new SessionAccess(sessions),SessionCookies.local()));
        assertThrows(NullPointerException.class,()->new AdministratorBookingController(view,null,SessionCookies.local()));
        assertThrows(NullPointerException.class,()->new AdministratorBookingController(view,new SessionAccess(sessions),null));
    }
}
