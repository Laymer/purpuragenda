package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.port.out.SessionStore.*;
import com.purpuragenda.application.scheduling.*;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class CustomerBookingHttpTest {
    final SessionHttpTest.Memory sessions=new SessionHttpTest.Memory();
    RuntimeException failure;Code outcome=Code.OK;boolean queryFails;
    final UUID id=UUID.randomUUID();
    CustomerBookingController controller() {
        return new CustomerBookingController(new CustomerBooking((command,work)->receipt(),"local-v1"),
                new CustomerBookingChanges((command,work)->receipt()),new CustomerBookingView((session,booking)->{
                    if(queryFails)throw failure;return Optional.empty();
                }),new SessionAccess(sessions),SessionCookies.operational());
    }
    Receipt receipt() { if(failure!=null)throw failure;return outcome==Code.OK?new Receipt(Code.OK,id,1):Receipt.failure(outcome); }
    String body() { return "{\"serviceId\":\""+id+"\",\"serviceVersion\":1,\"policyVersion\":1,\"startInstant\":\"2026-11-01T10:00:00Z\",\"displayZone\":\"Europe/Brussels\",\"noticeVersion\":\"local-v1\",\"intake\":{\"firstName\":\"Synthetic\",\"lastName\":\"Customer\",\"telephone\":\"+32400123456\",\"birthDate\":\"1990-01-01\",\"birthTime\":\"12:00\",\"birthplace\":\"Synthetic city\",\"message\":\"\"}}"; }
    MockHttpServletRequest request(String body) {
        var r=new MockHttpServletRequest();r.setContentType("application/json");r.setContent(body.getBytes(StandardCharsets.UTF_8));r.addHeader("Idempotency-Key","synthetic-key");
        try(var issued=sessions.anonymous()) { r.setAttribute(ApiGuardFilter.SESSION_ATTRIBUTE,issued.session());r.setCookies(new Cookie("__Host-purpuragenda",new String(issued.token()))); }
        return r;
    }
    @Test void controllerNeverLeaksProviderErrorsAndMapsAuthoritySeparatelyFromBookingExistence() throws Exception {
        for(var kind:Failure.Kind.values()) {
            failure=new Failure(kind);var response=new MockHttpServletResponse();assertNull(controller().create(request(body()),response));
            int expected=switch(kind){case AUTHENTICATION_REQUIRED->401;case FORBIDDEN,FRESH_AUTHENTICATION_REQUIRED->403;case NOT_FOUND->404;default->503;};
            assertEquals(expected,response.getStatus());
        }
        failure=new IllegalStateException("synthetic-private-provider-detail");var unavailable=new MockHttpServletResponse();controller().create(request(body()),unavailable);
        assertEquals(503,unavailable.getStatus());assertFalse(unavailable.getContentAsString().contains(failure.getMessage()));
        failure=null;outcome=Code.SLOT_UNAVAILABLE;var conflict=new MockHttpServletResponse();controller().create(request(body()),conflict);assertEquals(409,conflict.getStatus());
    }
    @Test void safeReadsHandleMissingTargetsSessionsAndStorageFailureWithoutTouchingAuthority() throws Exception {
        var absent=new MockHttpServletResponse();controller().get(id.toString(),request(""),absent);assertEquals(404,absent.getStatus());
        queryFails=true;failure=new Failure(Failure.Kind.STORAGE_FAILURE);var failed=new MockHttpServletResponse();controller().get(id.toString(),request(""),failed);assertEquals(503,failed.getStatus());
        failure=new IllegalStateException("synthetic-private-provider-detail");var unexpected=new MockHttpServletResponse();controller().get(id.toString(),request(""),unexpected);assertEquals(503,unexpected.getStatus());assertFalse(unexpected.getContentAsString().contains(failure.getMessage()));
        var request=request("");sessions.unavailable=true;var unavailable=new MockHttpServletResponse();controller().get(id.toString(),request,unavailable);assertEquals(503,unavailable.getStatus());
    }
    @Test void directMissingGuardAndMalformedChangesFailBeforeCallingCommands() throws Exception {
        var response=new MockHttpServletResponse();controller().create(new MockHttpServletRequest(),response);assertEquals(401,response.getStatus());
        var cancel=request("{\"unexpected\":true}");cancel.addHeader("If-Match","\"1\"");response=new MockHttpServletResponse();controller().cancel(id.toString(),cancel,response);assertEquals(400,response.getStatus());
        var move=request("{\"policyVersion\":1,\"startInstant\":\"invalid\"}");move.addHeader("If-Match","\"1\"");response=new MockHttpServletResponse();controller().reschedule(id.toString(),move,response);assertEquals(400,response.getStatus());
        response=new MockHttpServletResponse();controller().create(request(body().replace("+32400123456","invalid")),response);assertEquals(400,response.getStatus());
    }
}
