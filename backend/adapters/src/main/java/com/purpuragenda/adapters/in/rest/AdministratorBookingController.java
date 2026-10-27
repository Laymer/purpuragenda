package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import com.purpuragenda.application.port.out.SessionStore;
import com.purpuragenda.application.scheduling.AdministratorBookingView;
import com.purpuragenda.domain.DomainFailure;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.DateTimeException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Private read boundary. Persisted session inspection never touches authority; the query rechecks it in its snapshot. */
@RestController
@RequestMapping("/api/v1/admin/bookings")
public final class AdministratorBookingController {
    private final AdministratorBookingView view;private final SessionAccess access;private final SessionCookies cookies;
    public AdministratorBookingController(AdministratorBookingView view,SessionAccess access,SessionCookies cookies) {
        this.view=Objects.requireNonNull(view);this.access=Objects.requireNonNull(access);this.cookies=Objects.requireNonNull(cookies);
    }
    @GetMapping
    public ResponseEntity<String> agenda(HttpServletRequest request,HttpServletResponse response) throws IOException {
        return read(request,response,session->{
            var query=AdministratorBookingHttp.parameters(request);
            var result=view.agenda(session,query.from(),query.through(),query.after(),query.limit());
            return json(AdministratorBookingHttp.page(result),null,response);
        });
    }
    @GetMapping("/{bookingId}")
    public ResponseEntity<String> detail(@PathVariable String bookingId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        return read(request,response,session->{
            if(!request.getParameterMap().isEmpty())throw new BookingHttp.Invalid();
            var result=view.detail(session,BookingHttp.id(bookingId));
            if(result.isEmpty()) { BookingHttp.problem(response,404,"BOOKING_NOT_FOUND",false,false);return null; }
            return json(AdministratorBookingHttp.detail(result.get()),result.get().booking().version(),response);
        });
    }
    private interface Work { ResponseEntity<String> run(SessionBinding session) throws BookingHttp.Invalid,IOException; }
    private ResponseEntity<String> read(HttpServletRequest request,HttpServletResponse response,Work work) throws IOException {
        try {
            var session=access.inspect(cookies.read(request));
            if(session.isEmpty()) { BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return null; }
            if(session.get().kind()!=SessionStore.Kind.ADMIN) { BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);return null; }
            var reference=session.get().reference();return work.run(new SessionBinding(reference.id(),reference.browserHash()));
        } catch(BookingHttp.Invalid | DomainFailure | IllegalArgumentException | DateTimeException invalid) { BookingHttp.problem(response,400,"INVALID_REQUEST",false,false); }
        catch(Failure failure) {
            switch(failure.kind()) {
                case AUTHENTICATION_REQUIRED -> BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);
                case FORBIDDEN -> BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);
                default -> BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false);
            }
        } catch(RuntimeException unavailable) { BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false); }
        return null;
    }
    private static ResponseEntity<String> json(String body,Long version,HttpServletResponse response) throws IOException {
        // The command version does not version independent financial facts. Never turn a private read into 304.
        response.setStatus(200);response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
        if(version!=null)response.setHeader(HttpHeaders.ETAG,'"'+version.toString()+'"');
        response.setContentType("application/json");response.setCharacterEncoding("UTF-8");response.getWriter().write(body);return null;
    }
}
