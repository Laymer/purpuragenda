package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.purpuragenda.application.port.out.AdminChangesUnitOfWork.Classification;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.port.out.SessionStore.*;
import com.purpuragenda.application.scheduling.AdministratorBookingChanges;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.Booking;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.DateTimeException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Versioned administrator rejection, cancellation, rescheduling and outcomes; each needs the reviewed version and a reason. */
@RestController
@RequestMapping("/api/v1/admin/bookings/{bookingId}")
public final class AdministratorBookingChangeController {
    private final AdministratorBookingChanges changes;
    public AdministratorBookingChangeController(AdministratorBookingChanges changes) { this.changes=Objects.requireNonNull(changes); }
    @PostMapping("/reject")
    public ResponseEntity<String> reject(@PathVariable String bookingId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        return command(bookingId,request,response,(session,id,key,version)->{
            var body=BookingHttp.body(request,"reason");
            return changes.reject(session,id,key,new AdministratorBookingChanges.Rejection(version,BookingHttp.text(body,"reason")));
        });
    }
    @PostMapping("/cancel")
    public ResponseEntity<String> cancel(@PathVariable String bookingId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        return command(bookingId,request,response,(session,id,key,version)->{
            var body=BookingHttp.body(request,"classification","reason");
            String classification=BookingHttp.text(body,"classification");
            if(!Set.of("PROVIDER","CUSTOMER").contains(classification))throw new BookingHttp.Invalid();
            return changes.cancel(session,id,key,new AdministratorBookingChanges.Cancellation(version,Classification.valueOf(classification),BookingHttp.text(body,"reason")));
        });
    }
    @PostMapping("/reschedule")
    public ResponseEntity<String> reschedule(@PathVariable String bookingId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        return command(bookingId,request,response,(session,id,key,version)->{
            var body=BookingHttp.body(request,"policyVersion","calendarVersion","startInstant","reason","override");
            return changes.reschedule(session,id,key,new AdministratorBookingChanges.Reschedule(version,BookingHttp.number(body,"policyVersion"),
                    BookingHttp.number(body,"calendarVersion"),BookingHttp.start(body),BookingHttp.text(body,"reason"),
                    AdministratorBookingCommandController.override(body.get("override"))));
        });
    }
    @PostMapping("/outcome")
    public ResponseEntity<String> outcome(@PathVariable String bookingId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        return command(bookingId,request,response,(session,id,key,version)->{
            var body=BookingHttp.body(request,"outcome","reason");
            String outcome=BookingHttp.text(body,"outcome");
            if(!Set.of("COMPLETED","NO_SHOW").contains(outcome))throw new BookingHttp.Invalid();
            JsonNode reason=body.get("reason");
            return changes.outcome(session,id,key,new AdministratorBookingChanges.Outcome(version,Booking.State.valueOf(outcome),
                    reason.isNull()?null:BookingHttp.text(body,"reason")));
        });
    }
    private interface Work { Receipt run(SessionBinding session,UUID id,String key,long version) throws BookingHttp.Invalid; }
    private ResponseEntity<String> command(String bookingId,HttpServletRequest request,HttpServletResponse response,Work work) throws IOException {
        if(!(request.getAttribute(ApiGuardFilter.SESSION_ATTRIBUTE) instanceof Snapshot session)) { BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return null; }
        if(session.kind()!=Kind.ADMIN) { BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);return null; }
        if(request.getHeader("If-Match")==null) { BookingHttp.problem(response,428,"VERSION_REQUIRED",false,false);return null; }
        try {
            var reference=session.reference();
            var receipt=work.run(new SessionBinding(reference.id(),reference.browserHash()),BookingHttp.id(bookingId),
                    BookingHttp.header(request,"Idempotency-Key"),BookingHttp.version(request));
            if(!receipt.success()) { BookingHttp.business(response,receipt.code());return null; }
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").eTag('"'+Long.toString(receipt.version())+'"')
                    .contentType(MediaType.APPLICATION_JSON).body(BookingHttp.receipt(receipt));
        } catch(BookingHttp.Invalid | DomainFailure | IllegalArgumentException | DateTimeException | ArithmeticException invalid) { BookingHttp.problem(response,400,"INVALID_REQUEST",false,false); }
        catch(Failure failure) {
            switch(failure.kind()) {
                case AUTHENTICATION_REQUIRED -> BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);
                case FRESH_AUTHENTICATION_REQUIRED -> BookingHttp.problem(response,403,"AUTHENTICATION_REQUIRED",false,false);
                case FORBIDDEN -> BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);
                case NOT_FOUND -> BookingHttp.problem(response,404,"BOOKING_NOT_FOUND",false,false);
                default -> BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false);
            }
        } catch(RuntimeException unavailable) { BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false); }
        return null;
    }
}
