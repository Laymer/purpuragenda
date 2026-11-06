package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.calendar.CalendarDownloads;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import com.purpuragenda.application.port.out.SessionStore.Kind;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Objects;
import org.springframework.web.bind.annotation.*;

/** Fixed private attachment routes. No bearer URL, client-selected audience, conditional caching or session touch. */
@RestController
public final class CalendarDownloadController {
    private final CalendarDownloads downloads;private final SessionAccess access;private final SessionCookies cookies;
    public CalendarDownloadController(CalendarDownloads downloads,SessionAccess access,SessionCookies cookies) {
        this.downloads=Objects.requireNonNull(downloads);this.access=Objects.requireNonNull(access);this.cookies=Objects.requireNonNull(cookies);
    }
    @GetMapping("/api/v1/bookings/{bookingId}/calendar")
    public void customer(@PathVariable String bookingId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        download(bookingId,request,response,false);
    }
    @GetMapping("/api/v1/admin/bookings/{bookingId}/calendar")
    public void owner(@PathVariable String bookingId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        download(bookingId,request,response,true);
    }
    private void download(String bookingId,HttpServletRequest request,HttpServletResponse response,boolean owner) throws IOException {
        try {
            var session=access.inspect(cookies.read(request));
            if(session.isEmpty()) { BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return; }
            if(owner && session.get().kind()!=Kind.ADMIN) { BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);return; }
            if(request.getQueryString()!=null || !request.getParameterMap().isEmpty())throw new BookingHttp.Invalid();
            var id=BookingHttp.id(bookingId);var reference=session.get().reference();
            var binding=new SessionBinding(reference.id(),reference.browserHash());
            var bytes=owner?downloads.owner(binding,id):downloads.customer(binding,id);
            if(bytes.isEmpty()) { BookingHttp.problem(response,404,"BOOKING_NOT_FOUND",false,false);return; }
            byte[] body=bytes.orElseThrow();
            response.setStatus(200);response.setHeader("Cache-Control","no-store");
            response.setHeader("Content-Disposition","attachment; filename=\"consultation.ics\"");
            response.setContentType("text/calendar");response.setCharacterEncoding("UTF-8");response.setContentLength(body.length);
            if(!request.getMethod().equals("HEAD"))response.getOutputStream().write(body);
        } catch(BookingHttp.Invalid malformed) { BookingHttp.problem(response,400,"INVALID_REQUEST",false,false); }
        catch(Failure failure) {
            if(owner && failure.kind()==Failure.Kind.FORBIDDEN)BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);
            else BookingHttp.failure(response,failure);
        } catch(RuntimeException unavailable) { BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false); }
        finally {
            response.setHeader("Referrer-Policy","no-referrer");response.setHeader("X-Content-Type-Options","nosniff");
        }
    }
}
