package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.FinanceUnitOfWork.Operation;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.port.out.SessionStore.*;
import com.purpuragenda.application.scheduling.AdministratorFinance;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Wire projection tests; actual factor/transaction behavior is exercised by the PostgreSQL HTTP suite. */
class AdministratorFinanceHttpTest {
    static final UUID BOOKING=UUID.fromString("11111111-1111-4111-8111-111111111111"),PAYMENT=UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final String BASE="/api/v1/admin/bookings/"+BOOKING;
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String FACT="{\"amountMinor\":1500,\"currency\":\"EUR\",\"method\":\"SYNTHETIC\",\"reference\":\"synthetic-reference\",\"verifiedAt\":\"2026-11-01T10:00:00Z\"}";
    final List<FinanceUnitOfWork.Command> commands=new ArrayList<>();Code code=Code.OK;Failure.Kind failure;boolean unavailable;
    final AdministratorFinance finance=new AdministratorFinance((command,work)->{
        commands.add(command);if(failure!=null)throw new Failure(failure);if(unavailable)throw new IllegalStateException("SYNTHETIC_PRIVATE_PROVIDER");
        if(code!=Code.OK)return Receipt.failure(code);
        return new Receipt(code,command.operation()==Operation.APPROVE_BOOKING || command.operation()==Operation.CONFIRM_BOOKING?BOOKING:PAYMENT,7);
    },Set.of("zoom.us"));
    MockMvc mvc;
    @BeforeEach void setup() { mvc=MockMvcBuilders.standaloneSetup(new AdministratorFinanceController(finance)).build(); }
    Snapshot session(Kind kind) { return new Snapshot(new SessionReference(UUID.randomUUID(),"a".repeat(64)),kind,null,null,null,UUID.randomUUID(),0L,Instant.EPOCH,Instant.EPOCH,Instant.EPOCH.plusSeconds(28800),Instant.EPOCH); }
    MockHttpServletRequestBuilder request(String action,String body) { return post(BASE+action).requestAttr(ApiGuardFilter.SESSION_ATTRIBUTE,session(Kind.ADMIN)).header("Idempotency-Key","synthetic-key").contentType("application/json").content(body); }
    MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception { return mvc.perform(request).andReturn().getResponse(); }
    void problem(MockHttpServletResponse response,int status,String expected) throws Exception {
        assertEquals(status,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals(expected,JSON.readTree(response.getContentAsString()).get("code").textValue());
        assertFalse(response.getContentAsString().contains("SYNTHETIC_PRIVATE_PROVIDER"));assertNull(response.getHeader("Set-Cookie"));
    }
    @Test void actualMoneyEntryIdIsNeverMislabeledAsBookingAndBookingVersionRemainsExplicit() throws Exception {
        var response=send(request("/receipts",FACT));assertEquals(201,response.getStatus());assertNull(response.getHeader("ETag"));
        var body=JSON.readTree(response.getContentAsString());assertEquals(BOOKING.toString(),body.get("bookingId").textValue());assertEquals(PAYMENT.toString(),body.get("paymentEntryId").textValue());assertEquals(7,body.get("bookingVersion").asLong());assertEquals(3,body.size());
        assertEquals(Operation.RECORD_RECEIPT,commands.getFirst().operation());assertEquals(BOOKING,commands.getFirst().bookingId());
        var refund=JSON.readTree(FACT);((com.fasterxml.jackson.databind.node.ObjectNode)refund).put("receiptId",PAYMENT.toString());
        assertEquals(201,send(request("/refunds",refund.toString())).getStatus());assertEquals(Operation.RECORD_REFUND,commands.getLast().operation());
        var required=send(request("/refund-requirements","{\"receiptId\":\""+PAYMENT+"\",\"reason\":\"Synthetic refund requirement\"}"));assertEquals(200,required.getStatus());assertTrue(JSON.readTree(required.getContentAsString()).has("receiptId"));assertEquals(Operation.REQUIRE_REFUND,commands.getLast().operation());
    }
    @Test void confirmationsRequireStrongReviewedVersionsAndLeaveReceiptFactsToSeparateRoutes() throws Exception {
        String body="{\"meetingUrl\":\"https://zoom.us/j/123456789\"}";
        problem(send(request("/approve",body)),428,"VERSION_REQUIRED");
        for(String invalid:List.of("1","W/\"1\"","\"0\"","\"9223372036854775808\""))problem(send(request("/approve",body).header("If-Match",invalid)),400,"INVALID_REQUEST");
        for(String action:List.of("/approve","/confirm-external-payment")) {
            var response=send(request(action,body).header("If-Match","\"1\""));assertEquals(200,response.getStatus());assertEquals("\"7\"",response.getHeader("ETag"));
            var value=JSON.readTree(response.getContentAsString());assertEquals(BOOKING.toString(),value.get("bookingId").textValue());assertEquals(7,value.get("version").asLong());assertFalse(value.has("paymentEntryId"));
        }
        assertEquals(List.of(Operation.APPROVE_BOOKING,Operation.CONFIRM_BOOKING),commands.stream().map(FinanceUnitOfWork.Command::operation).toList());
    }
    @Test void unsupportedMoneyVersionConditionIsNotSilentlyTreatedAsABookingEdit() throws Exception {
        problem(send(request("/receipts",FACT).header("If-Match","\"1\"")),400,"INVALID_REQUEST");assertTrue(commands.isEmpty());
    }
    @Test void absentAndWrongSessionRolesCannotReachAnyFinancialUnit() throws Exception {
        problem(send(post(BASE+"/receipts").contentType("application/json").content(FACT)),401,"SESSION_REQUIRED");
        for(var kind:List.of(Kind.ANONYMOUS,Kind.GUEST,Kind.ACCOUNT))problem(send(request("/receipts",FACT).requestAttr(ApiGuardFilter.SESSION_ATTRIBUTE,session(kind))),403,"ADMIN_ACCESS_REQUIRED");
        assertTrue(commands.isEmpty());assertThrows(NullPointerException.class,()->new AdministratorFinanceController(null));
    }
    @Test void malformedUnexpectedDuplicateAndNonIntegralFactsFailBeforeTheUnit() throws Exception {
        for(String body:List.of("{}",FACT+"{}",FACT.replace("1500","\"1500\""),FACT.replace("1500","0"),FACT.replace("1500","9223372036854775808"),FACT.replace("\"EUR\"","\"eur\""),FACT.replace("10:00:00Z","10:00:00.000000001Z"),FACT.replace("10:00:00Z","invalid"),FACT.replace("\"amountMinor\":","\"adminId\":\"ignored\",\"amountMinor\":"),FACT.replace("\"amountMinor\":","\"amountMinor\":1,\"amountMinor\":")))problem(send(request("/receipts",body)),400,"INVALID_REQUEST");
        problem(send(post(BASE+"/receipts").requestAttr(ApiGuardFilter.SESSION_ATTRIBUTE,session(Kind.ADMIN)).contentType("application/json").content(FACT)),400,"INVALID_REQUEST");
        problem(send(request("/refunds",FACT)),400,"INVALID_REQUEST");
        problem(send(request("/refund-requirements","{\"receiptId\":\"not-a-uuid\",\"reason\":\"Synthetic refund reason\"}")),400,"INVALID_REQUEST");
        problem(send(request("/refund-requirements","{\"receiptId\":\""+PAYMENT+"\",\"reason\":\"short\"}")),400,"INVALID_REQUEST");assertTrue(commands.isEmpty());
    }
    @Test void safeBusinessFailuresDistinguishVersionValidationAndLedgerConflicts() throws Exception {
        for(Code denied:List.of(Code.STALE_VERSION,Code.INVALID_PAYMENT,Code.INVALID_REFUND,Code.INVALID_MEETING,Code.REFUND_EXCEEDS_RECEIPT,Code.PAYMENT_INSUFFICIENT,Code.IDEMPOTENCY_KEY_REUSED)) {
            code=denied;int expected=denied==Code.STALE_VERSION?412:Set.of(Code.INVALID_PAYMENT,Code.INVALID_REFUND,Code.INVALID_MEETING).contains(denied)?400:409;
            problem(send(request("/receipts",FACT)),expected,denied.name());
        }
    }
    @Test void persistedFreshnessOwnershipAndStorageFailuresNeverExposeProviderMessages() throws Exception {
        for(var denied:List.of(Failure.Kind.AUTHENTICATION_REQUIRED,Failure.Kind.FRESH_AUTHENTICATION_REQUIRED,Failure.Kind.FORBIDDEN,Failure.Kind.NOT_FOUND,Failure.Kind.MAINTENANCE)) {
            failure=denied;int status=denied==Failure.Kind.AUTHENTICATION_REQUIRED?401:denied==Failure.Kind.FRESH_AUTHENTICATION_REQUIRED || denied==Failure.Kind.FORBIDDEN?403:denied==Failure.Kind.NOT_FOUND?404:503;
            String expected=denied==Failure.Kind.AUTHENTICATION_REQUIRED?"SESSION_REQUIRED":denied==Failure.Kind.FRESH_AUTHENTICATION_REQUIRED?"AUTHENTICATION_REQUIRED":denied==Failure.Kind.FORBIDDEN?"ADMIN_ACCESS_REQUIRED":denied==Failure.Kind.NOT_FOUND?"BOOKING_NOT_FOUND":"TEMPORARILY_UNAVAILABLE";
            problem(send(request("/receipts",FACT)),status,expected);
        }
        failure=null;unavailable=true;var response=send(request("/receipts",FACT));problem(response,503,"TEMPORARILY_UNAVAILABLE");assertEquals("1",response.getHeader("Retry-After"));
    }
}
