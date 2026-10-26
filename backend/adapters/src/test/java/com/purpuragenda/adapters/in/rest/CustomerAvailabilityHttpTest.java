package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import com.purpuragenda.application.scheduling.CustomerReschedulingAvailability;
import com.purpuragenda.domain.DomainFailure;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class CustomerAvailabilityHttpTest {
    final SessionHttpTest.Memory sessions=new SessionHttpTest.Memory();
    final String id=UUID.randomUUID().toString();
    MockHttpServletRequest request() {
        var request=new MockHttpServletRequest();request.addParameter("from","2026-01-02");request.addParameter("to","2026-01-02");request.addParameter("displayZone","UTC");
        try(var issued=sessions.anonymous()) { request.setCookies(new Cookie("__Host-purpuragenda",new String(issued.token()))); }return request;
    }
    CustomerAvailabilityController controller(RuntimeException failure) {
        return new CustomerAvailabilityController(new CustomerReschedulingAvailability((s,b,r)->{if(failure!=null)throw failure;return Optional.empty();}),new SessionAccess(sessions),SessionCookies.operational());
    }
    @Test void rejectsAmbiguousOrMalformedInputsWithoutQuerying() throws Exception {
        var controller=controller(new AssertionFailure());
        for(int variant=0;variant<8;variant++) {
            var request=request();String target=id;
            switch(variant) {
                case 0 -> target="1-1-1-1-1";
                case 1 -> request.addParameter("extra","x");
                case 2 -> request.removeParameter("from");
                case 3 -> request.setParameter("from","2026-02-30");
                case 4 -> request.setParameter("from","2026-1-2");
                case 5 -> request.setParameter("displayZone","");
                case 6 -> request.addParameter("from","2026-01-03");
                default -> request.setParameter("displayZone","x".repeat(65));
            }
            var response=new MockHttpServletResponse();controller.get(target,request,response);assertEquals(400,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));
        }
    }
    @Test void invalidRangesAndZonesHaveBoundedProblems() throws Exception {
        for(String value:List.of("zone","reverse","wide")) {
            var request=request();if(value.equals("zone"))request.setParameter("displayZone","bogus");else request.setParameter("to",value.equals("wide")?"2026-02-02":"2026-01-01");
            var response=new MockHttpServletResponse();controller(new AssertionFailure()).get(id,request,response);assertEquals(400,response.getStatus());
        }
    }
    @Test void absentScopeAndSessionArePrivateAndDoNotBecomeAnAnonymousQuery() throws Exception {
        var response=new MockHttpServletResponse();controller(null).get(id,request(),response);assertEquals(404,response.getStatus());
        var request=request();request.setCookies();response=new MockHttpServletResponse();controller(new AssertionFailure()).get(id,request,response);assertEquals(401,response.getStatus());
    }
    @Test void storageAndUnexpectedFailuresAreSanitized() throws Exception {
        for(RuntimeException failure:List.of(new Failure(Failure.Kind.STORAGE_FAILURE),new IllegalStateException("synthetic-provider-detail"),new DomainFailure("UNEXPECTED_DOMAIN_CODE"))) {
            var response=new MockHttpServletResponse();controller(failure).get(id,request(),response);
            assertEquals(failure instanceof DomainFailure?400:503,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));
            assertFalse(response.getContentAsString().contains("synthetic-provider-detail") || response.getContentAsString().contains("UNEXPECTED_DOMAIN_CODE"));
        }
    }
    static class AssertionFailure extends RuntimeException { AssertionFailure(){super("Query unexpectedly invoked");} }
}
