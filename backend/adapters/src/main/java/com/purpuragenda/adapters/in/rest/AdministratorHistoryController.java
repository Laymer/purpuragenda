package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.port.out.AdministratorHistoryQuery;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import com.purpuragenda.application.port.out.SessionStore;
import com.purpuragenda.domain.DomainFailure;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.DateTimeException;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public final class AdministratorHistoryController {
    private final AdministratorHistoryQuery query;private final SessionAccess access;private final SessionCookies cookies;
    public AdministratorHistoryController(AdministratorHistoryQuery query,SessionAccess access,SessionCookies cookies) {
        this.query=Objects.requireNonNull(query);this.access=Objects.requireNonNull(access);this.cookies=Objects.requireNonNull(cookies);
    }
    @GetMapping("/bookings/{bookingId}/notes")
    public void notes(@PathVariable String bookingId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        read(request,response,session->AdministratorHistoryHttp.notes(query.notes(session,BookingHttp.id(bookingId),AdministratorHistoryHttp.parameters(request,false))));
    }
    @GetMapping("/audit")
    public void audit(HttpServletRequest request,HttpServletResponse response) throws IOException {
        read(request,response,session->{
            var parameters=AdministratorHistoryHttp.parameters(request,true);String booking=request.getParameter("bookingId");
            return AdministratorHistoryHttp.audit(query.audit(session,booking==null?null:BookingHttp.id(booking),parameters));
        });
    }
    private interface Read {String run(SessionBinding session) throws BookingHttp.Invalid;}
    private void read(HttpServletRequest request,HttpServletResponse response,Read work) throws IOException {
        try {
            var session=access.inspect(cookies.read(request));
            if(session.isEmpty()){BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return;}
            if(session.get().kind()!=SessionStore.Kind.ADMIN){BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);return;}
            var reference=session.get().reference();AdministratorHistoryHttp.json(response,200,work.run(new SessionBinding(reference.id(),reference.browserHash())));
        } catch(BookingHttp.Invalid | DomainFailure | IllegalArgumentException | DateTimeException invalid){BookingHttp.problem(response,400,"INVALID_REQUEST",false,false);}
        catch(Failure failure){AdministratorHistoryHttp.failure(response,failure);}
        catch(RuntimeException unavailable){BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false);}
    }
}
