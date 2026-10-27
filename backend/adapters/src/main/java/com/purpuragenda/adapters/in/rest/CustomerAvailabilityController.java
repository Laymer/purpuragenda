package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import com.purpuragenda.application.scheduling.CustomerReschedulingAvailability;
import com.purpuragenda.domain.DomainFailure;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Read-only advice is authorized in the same snapshot as the booked terms and occupancy. */
@RestController
public final class CustomerAvailabilityController {
    private final CustomerReschedulingAvailability availability;private final SessionAccess access;private final SessionCookies cookies;
    public CustomerAvailabilityController(CustomerReschedulingAvailability availability,SessionAccess access,SessionCookies cookies) {
        this.availability=Objects.requireNonNull(availability);this.access=Objects.requireNonNull(access);this.cookies=Objects.requireNonNull(cookies);
    }
    @GetMapping("/api/v1/bookings/{bookingId}/availability")
    public void get(@PathVariable("bookingId") String bookingId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        UUID id;LocalDate from,to;String zone;
        try {
            if(!bookingId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                    || !request.getParameterMap().keySet().equals(Set.of("from","to","displayZone")))throw new IllegalArgumentException();
            id=UUID.fromString(bookingId);from=date(single(request,"from"));to=date(single(request,"to"));zone=single(request,"displayZone");
        } catch(IllegalArgumentException invalid) { BookingHttp.problem(response,400,"INVALID_REQUEST",false,false);return; }
        try {
            var session=access.inspect(cookies.read(request));
            if(session.isEmpty()) { BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return; }
            var reference=session.get().reference();
            var result=availability.find(new SessionBinding(reference.id(),reference.browserHash()),id,from,to,zone);
            if(result.isEmpty()) { BookingHttp.problem(response,404,"BOOKING_NOT_FOUND",false,false);return; }
            byte[] body=PublicController.availability(result.get()).getBytes(StandardCharsets.UTF_8);
            response.setStatus(200);response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);response.setCharacterEncoding("UTF-8");
            response.setContentLength(body.length);response.getOutputStream().write(body);
        } catch(DomainFailure invalid) { BookingHttp.problem(response,400,switch(invalid.code()) {
            case "INVALID_ZONE" -> "INVALID_ZONE";case "INVALID_DATE_RANGE" -> "INVALID_DATE_RANGE";default -> "INVALID_REQUEST";
        },false,false); }
        catch(Failure failure) { BookingHttp.failure(response,failure); }
        catch(RuntimeException unavailable) { BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false); }
    }
    private static String single(HttpServletRequest request,String name) {
        String[] values=request.getParameterValues(name);
        if(values==null || values.length!=1 || values[0].isEmpty() || values[0].length()>64)throw new IllegalArgumentException();
        return values[0];
    }
    private static LocalDate date(String value) {
        if(!value.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException();
        try { return LocalDate.parse(value); } catch(java.time.DateTimeException invalid) { throw new IllegalArgumentException(); }
    }
}
