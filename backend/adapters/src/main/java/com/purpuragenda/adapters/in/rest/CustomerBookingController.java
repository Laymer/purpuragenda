package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.port.out.SessionStore.Snapshot;
import com.purpuragenda.application.scheduling.*;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.customer.Intake;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Protected customer commands recheck persisted authority inside their transaction, before idempotent replay. */
@RestController
@RequestMapping("/api/v1/bookings")
public final class CustomerBookingController {
    private final CustomerBooking create;private final CustomerBookingChanges changes;private final CustomerBookingView views;
    private final SessionAccess access;private final SessionCookies cookies;
    public CustomerBookingController(CustomerBooking create,CustomerBookingChanges changes,CustomerBookingView views,SessionAccess access,SessionCookies cookies) {
        this.create=Objects.requireNonNull(create);this.changes=Objects.requireNonNull(changes);this.views=Objects.requireNonNull(views);
        this.access=Objects.requireNonNull(access);this.cookies=Objects.requireNonNull(cookies);
    }
    @GetMapping("/{bookingId}")
    public ResponseEntity<String> get(@PathVariable String bookingId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        UUID id;
        try { id=BookingHttp.id(bookingId); } catch(BookingHttp.Invalid invalid) { invalid(response);return null; }
        try {
            var session=access.inspect(cookies.read(request));
            if(session.isEmpty()) { BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return null; }
            var view=views.find(binding(session.get()),id);
            if(view.isEmpty()) { BookingHttp.problem(response,404,"BOOKING_NOT_FOUND",false,false);return null; }
            return json(200,view.get().version(),BookingHttp.view(view.get()));
        } catch(Failure failure) { BookingHttp.failure(response,failure); }
        catch(RuntimeException unavailable) { unavailable(response); }
        return null;
    }
    @PostMapping
    public ResponseEntity<String> create(HttpServletRequest request,HttpServletResponse response) throws IOException {
        return mutation(request,response,false,(session,key)->{
            var body=BookingHttp.body(request,"serviceId","serviceVersion","policyVersion","startInstant","displayZone","noticeVersion","intake");
            var intake=BookingHttp.object(body.get("intake"),"firstName","lastName","telephone","birthDate","birthTime","birthplace","message");
            var values=new Intake(BookingHttp.text(intake,"firstName"),BookingHttp.text(intake,"lastName"),BookingHttp.text(intake,"telephone"),
                    BookingHttp.birthDate(intake),BookingHttp.birthTime(intake),
                    BookingHttp.text(intake,"birthplace"),BookingHttp.text(intake,"message"));
            return create.create(session,key,new CustomerBooking.Request(BookingHttp.id(BookingHttp.text(body,"serviceId")),
                    BookingHttp.number(body,"serviceVersion"),BookingHttp.number(body,"policyVersion"),BookingHttp.start(body),
                    values,BookingHttp.text(body,"displayZone"),BookingHttp.text(body,"noticeVersion")));
        });
    }
    @PostMapping("/{bookingId}/cancel")
    public ResponseEntity<String> cancel(@PathVariable String bookingId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        return mutation(request,response,true,(session,key)->{
            BookingHttp.body(request);return changes.cancel(session,BookingHttp.id(bookingId),key,new CustomerBookingChanges.Cancellation(BookingHttp.version(request)));
        });
    }
    @PostMapping("/{bookingId}/reschedule")
    public ResponseEntity<String> reschedule(@PathVariable String bookingId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        return mutation(request,response,true,(session,key)->{
            var body=BookingHttp.body(request,"policyVersion","startInstant");
            return changes.reschedule(session,BookingHttp.id(bookingId),key,new CustomerBookingChanges.Reschedule(BookingHttp.version(request),
                    BookingHttp.number(body,"policyVersion"),BookingHttp.start(body)));
        });
    }
    private interface Work { Receipt run(SessionBinding session,String key) throws BookingHttp.Invalid; }
    private ResponseEntity<String> mutation(HttpServletRequest request,HttpServletResponse response,boolean versioned,Work work) throws IOException {
        if(!(request.getAttribute(ApiGuardFilter.SESSION_ATTRIBUTE) instanceof Snapshot session)) { BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return null; }
        if(versioned && request.getHeader("If-Match")==null) { BookingHttp.problem(response,428,"VERSION_REQUIRED",false,false);return null; }
        try {
            Receipt receipt=work.run(binding(session),BookingHttp.header(request,"Idempotency-Key"));
            if(!receipt.success()) { BookingHttp.business(response,receipt.code());return null; }
            var result=ResponseEntity.status(versioned?200:201).header(HttpHeaders.CACHE_CONTROL,"no-store").eTag('"'+Long.toString(receipt.version())+'"')
                    .contentType(MediaType.APPLICATION_JSON);
            if(!versioned)result.header(HttpHeaders.LOCATION,"/api/v1/bookings/"+receipt.entityId());
            return result.body(BookingHttp.receipt(receipt));
        } catch(BookingHttp.Invalid | DomainFailure | IllegalArgumentException | DateTimeException malformed) { invalid(response); }
        catch(Failure failure) {
            if(!versioned && failure.kind()==Failure.Kind.FORBIDDEN)BookingHttp.problem(response,403,"BOOKING_ACCESS_REQUIRED",false,false);
            else BookingHttp.failure(response,failure);
        }
        catch(RuntimeException failure) { unavailable(response); }
        return null;
    }
    private static SessionBinding binding(Snapshot session) { return new SessionBinding(session.reference().id(),session.reference().browserHash()); }
    private static ResponseEntity<String> json(int status,long version,String body) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,"no-store").eTag('"'+Long.toString(version)+'"').contentType(MediaType.APPLICATION_JSON).body(body);
    }
    private static void invalid(HttpServletResponse response) throws IOException { BookingHttp.problem(response,400,"INVALID_REQUEST",false,false); }
    private static void unavailable(HttpServletResponse response) throws IOException { BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false); }
}
