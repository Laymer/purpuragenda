package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.CustomerBookingView;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.payment.PaymentLedger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class BookingHttpTest {
    MockHttpServletRequest request(String body) {
        var request=new MockHttpServletRequest();request.setContentType("application/json; charset=UTF-8");request.setContent(body.getBytes(StandardCharsets.UTF_8));return request;
    }
    @Test void boundedObjectsRejectDuplicateUnknownNullAndTrailingInputWithoutCoercingTypes() throws Exception {
        assertEquals("",BookingHttp.text(BookingHttp.body(request("{\"message\":\"\"}"),"message"),"message"));
        for(String body:List.of("", "null","[]","{\"x\":1,\"x\":2}","{\"x\":1} {}","{\"x\":1,\"extra\":0}","{\"y\":1}","x".repeat(16385)))
            assertThrows(BookingHttp.Invalid.class,()->BookingHttp.body(request(body),"x"));
        for(String type:Arrays.asList(null,"text/plain","application/jsonp")) {
            var r=request("{}");r.setContentType(type);assertThrows(BookingHttp.Invalid.class,()->BookingHttp.body(r));
        }
        for(String value:List.of("null","true","1.5","\"1\"","0","-1","9223372036854775808")) {
            var node=BookingHttp.body(request("{\"n\":"+value+"}"),"n");assertThrows(BookingHttp.Invalid.class,()->BookingHttp.number(node,"n"));
        }
        var node=BookingHttp.body(request("{\"n\":1}"),"n");assertEquals(1,BookingHttp.number(node,"n"));
        assertThrows(BookingHttp.Invalid.class,()->BookingHttp.number(node,"absent"));
        assertThrows(BookingHttp.Invalid.class,()->BookingHttp.text(node,"absent"));assertThrows(BookingHttp.Invalid.class,()->BookingHttp.text(node,"n"));
        assertThrows(BookingHttp.Invalid.class,()->BookingHttp.object(null));
    }
    @Test void versionHeadersRequireOneStrongPositiveDecimalAndIdsRequireCanonicalShape() throws Exception {
        for(String value:List.of("*","W/\"1\"","1","\"0\"","\"-1\"","\"9223372036854775808\"","\"1\",\"2\""," ","a\nb","x".repeat(121))) {
            var r=request("{}");r.addHeader("If-Match",value);assertThrows(BookingHttp.Invalid.class,()->BookingHttp.version(r));
        }
        var r=request("{}");assertThrows(BookingHttp.Invalid.class,()->BookingHttp.version(r));r.addHeader("If-Match","\"1\"");assertEquals(1,BookingHttp.version(r));
        r.addHeader("If-Match","\"2\"");assertThrows(BookingHttp.Invalid.class,()->BookingHttp.version(r));
        for(String id:Arrays.asList(null,"1-1-1-1-1","not-an-id"))assertThrows(BookingHttp.Invalid.class,()->BookingHttp.id(id));
        UUID id=UUID.randomUUID();assertEquals(id,BookingHttp.id(id.toString().toUpperCase(Locale.ROOT)));
    }
    @Test void civilBirthValuesPreserveSecondsAndInstantsRejectLenientMidnightOrLeapSecondNormalization() throws Exception {
        var body=BookingHttp.body(request("{\"birthDate\":\"1990-01-01\",\"birthTime\":\"12:00:30\",\"startInstant\":\"2026-01-01T11:00:00+01:00\"}"),"birthDate","birthTime","startInstant");
        assertEquals("1990-01-01",BookingHttp.birthDate(body).toString());assertEquals("12:00:30",BookingHttp.birthTime(body).toString());
        assertEquals(Instant.parse("2026-01-01T10:00:00Z"),BookingHttp.start(body));
        for(String value:List.of("12:00:00.000","12:00Z","1:00","12:00:30+01:00")) {
            body.put("birthTime",value);assertThrows(BookingHttp.Invalid.class,()->BookingHttp.birthTime(body));
        }
        body.put("birthDate","1990-1-1");assertThrows(BookingHttp.Invalid.class,()->BookingHttp.birthDate(body));
        for(String value:List.of("2026-01-01T24:00:00Z","2026-01-01T23:59:60Z","2026-01-01T10:00:00")) {
            body.put("startInstant",value);assertThrows(java.time.DateTimeException.class,()->BookingHttp.start(body));
        }
    }
    @Test void fixedFailuresAndBusinessReceiptsHaveSafeRetryAndRefreshSemantics() throws Exception {
        for(var kind:Failure.Kind.values()) {
            var response=new MockHttpServletResponse();BookingHttp.failure(response,new Failure(kind));
            int expected=switch(kind){case AUTHENTICATION_REQUIRED->401;case FORBIDDEN,NOT_FOUND->404;case FRESH_AUTHENTICATION_REQUIRED->403;default->503;};
            assertEquals(expected,response.getStatus());assertEquals(expected==503?"1":null,response.getHeader("Retry-After"));
            assertEquals("no-store",response.getHeader("Cache-Control"));assertFalse(response.getContentAsString().contains("STORAGE_FAILURE"));
        }
        for(Code code:List.of(Code.STALE_VERSION,Code.INVALID_BIRTH_DATE,Code.SLOT_UNAVAILABLE,Code.IDEMPOTENCY_KEY_REUSED)) {
            var response=new MockHttpServletResponse();BookingHttp.business(response,code);var body=new JsonMapper().readTree(response.getContentAsString());
            assertEquals(code.name(),body.get("code").textValue());assertEquals(code==Code.STALE_VERSION?412:code==Code.INVALID_BIRTH_DATE?400:409,response.getStatus());
            assertEquals(code!=Code.IDEMPOTENCY_KEY_REUSED,body.get("refreshAvailability").booleanValue());assertFalse(body.get("retryable").booleanValue());
        }
    }
    @Test void explicitViewEscapesPublicValuesAndKeepsIntakeAndProviderReferencesOut() throws Exception {
        UUID id=UUID.randomUUID();Instant now=Instant.parse("2026-01-01T10:00:00Z");
        var v=new CustomerBookingView.View(id,"B-synthetic",2,Booking.State.CONFIRMED,false,
                new CustomerBookingView.Service("Synthetic \"quoted\" service",30,new Money(1500,"EUR")),now,now.plusSeconds(1800),"Europe/Brussels","delivery@example.test",null,
                now.minusSeconds(86400),now,"https://zoom.us/j/123456789",new PaymentLedger.Summary(PaymentLedger.Status.REFUND_DUE,1500,100,100,Map.of("EUR",1400L),Map.of("EUR",1500L)),Set.of());
        var body=new JsonMapper().readTree(BookingHttp.view(v));assertEquals(v.service().name(),body.get("service").get("name").textValue());
        assertTrue(body.get("pendingExpiresAt").isNull());assertEquals(1400,body.get("payment").get("refundDueByCurrency").get("EUR").longValue());
        assertFalse(body.has("intake") || body.has("customerId") || body.has("entries") || body.has("snapshot"));
        var receipt=new JsonMapper().readTree(BookingHttp.receipt(new Receipt(Code.OK,id,2)));assertEquals(id.toString(),receipt.get("bookingId").textValue());assertEquals(2,receipt.get("version").longValue());
    }
}
