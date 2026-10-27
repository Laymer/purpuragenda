package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import com.purpuragenda.application.scheduling.GuestBookingView;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Discovers exactly the current guest grant's booking, without accepting a target or returning history. */
@RestController
public final class GuestBookingController {
    private final GuestBookingView views;private final SessionAccess access;private final SessionCookies cookies;
    public GuestBookingController(GuestBookingView views,SessionAccess access,SessionCookies cookies) {
        this.views=Objects.requireNonNull(views);this.access=Objects.requireNonNull(access);this.cookies=Objects.requireNonNull(cookies);
    }
    @GetMapping("/api/v1/guest/booking")
    public void get(HttpServletRequest request,HttpServletResponse response) throws IOException {
        if(request.getQueryString()!=null) { BookingHttp.problem(response,400,"INVALID_REQUEST",false,false);return; }
        try {
            var session=access.inspect(cookies.read(request));
            if(session.isEmpty()) { BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return; }
            var reference=session.get().reference();
            var view=views.find(new SessionBinding(reference.id(),reference.browserHash()));
            if(view.isEmpty()) { BookingHttp.problem(response,404,"BOOKING_NOT_FOUND",false,false);return; }
            // The booking command version is not a cache validator for independent payment facts.
            byte[] body=BookingHttp.view(view.get()).getBytes(StandardCharsets.UTF_8);
            response.setStatus(200);response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
            response.setHeader(HttpHeaders.ETAG,'"'+Long.toString(view.get().version())+'"');
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);response.setCharacterEncoding("UTF-8");
            response.setContentLength(body.length);response.getOutputStream().write(body);
        } catch(Failure failure) { BookingHttp.failure(response,failure); }
        catch(RuntimeException unavailable) { BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false); }
    }
}
