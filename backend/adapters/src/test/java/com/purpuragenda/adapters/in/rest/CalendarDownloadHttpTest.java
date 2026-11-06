package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.calendar.CalendarDownloads;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.AdministratorBookingQuery.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import com.purpuragenda.application.port.out.SessionStore.*;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class CalendarDownloadHttpTest {
    @Test void storageAndRevokedAuthorityErrorsAreSafeForBothAudiences() throws Exception {
        for(boolean owner:List.of(false,true))for(RuntimeException failure:List.of(new Failure(Failure.Kind.STORAGE_FAILURE),new Failure(Failure.Kind.AUTHENTICATION_REQUIRED),new Failure(Failure.Kind.FORBIDDEN),new IllegalStateException("SYNTHETIC_PRIVATE_PROVIDER_DETAIL"))) {
            var sessions=new SessionHttpTest.Memory();var request=new MockHttpServletRequest("GET","/calendar");
            try(var issued=sessions.anonymous()) {
                String token=new String(issued.token());request.setCookies(new Cookie("__Host-purpuragenda",token));
                if(owner){var s=issued.session();sessions.byToken.put(token,new SessionHttpTest.Memory.Entry(new Snapshot(s.reference(),Kind.ADMIN,null,null,null,UUID.randomUUID(),1L,s.issuedAt(),s.lastSeenAt(),s.absoluteExpiresAt(),s.issuedAt()),"unused",false));}
            }
            var admin=new AdministratorBookingQuery() {
                public Page agenda(SessionBinding s,Window w,Cursor c,int n){throw new AssertionError();}
                public Optional<Detail> detail(SessionBinding s,UUID id){throw failure;}
            };
            var controller=new CalendarDownloadController(new CalendarDownloads((s,id)->{throw failure;},admin,(e,t)->{throw new AssertionError();}),new SessionAccess(sessions),SessionCookies.operational());
            var response=new MockHttpServletResponse();if(owner)controller.owner(UUID.randomUUID().toString(),request,response);else controller.customer(UUID.randomUUID().toString(),request,response);
            int expected=failure instanceof Failure f?switch(f.kind()){case AUTHENTICATION_REQUIRED->401;case FORBIDDEN->owner?403:404;default->503;}:503;
            assertEquals(expected,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(response.getHeader("Content-Disposition"));
            assertFalse(response.getContentAsString().contains("SYNTHETIC_PRIVATE_PROVIDER_DETAIL"));assertEquals("no-referrer",response.getHeader("Referrer-Policy"));assertEquals("nosniff",response.getHeader("X-Content-Type-Options"));
        }
    }
}
