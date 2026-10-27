package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import com.purpuragenda.application.scheduling.GuestBookingView;
import jakarta.servlet.http.Cookie;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class GuestBookingHttpTest {
    @Test void storageFailuresHaveSafeProblemsAndNeverExposeProviderDetails() throws Exception {
        var sessions=new SessionHttpTest.Memory();
        for(RuntimeException failure:new RuntimeException[]{new Failure(Failure.Kind.STORAGE_FAILURE),new IllegalStateException("synthetic-provider-detail")}) {
            var controller=new GuestBookingController(new GuestBookingView(binding->{throw failure;}),new SessionAccess(sessions),SessionCookies.operational());
            var request=new MockHttpServletRequest();
            try(var issued=sessions.anonymous()) { request.setCookies(new Cookie("__Host-purpuragenda",new String(issued.token()))); }
            var response=new MockHttpServletResponse();controller.get(request,response);
            assertEquals(503,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));
            assertFalse(response.getContentAsString().contains("synthetic-provider-detail"));
        }
    }
    @Test void noClientInputMayReplaceTheDerivedScope() throws Exception {
        var controller=new GuestBookingController(new GuestBookingView(binding->{fail("Query must not run for a supplied target");return Optional.empty();}),
                new SessionAccess(new SessionHttpTest.Memory()),SessionCookies.operational());
        var request=new MockHttpServletRequest();request.setQueryString("reference=synthetic");
        var response=new MockHttpServletResponse();controller.get(request,response);assertEquals(400,response.getStatus());
    }
}
