package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import com.purpuragenda.application.port.out.SessionStore.Snapshot;
import com.purpuragenda.application.scheduling.CustomerContactChanges;
import com.purpuragenda.domain.DomainFailure;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/** Proving another mailbox changes delivery only; persisted booking authority is required on both POSTs. */
@RestController
@RequestMapping("/api/v1/bookings")
public final class CustomerContactController {
    private final CustomerContactChanges changes;
    public CustomerContactController(CustomerContactChanges changes) { this.changes=Objects.requireNonNull(changes); }
    @PostMapping("/{bookingId}/contact-proofs")
    public void requestProof(@PathVariable String bookingId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        command(request,response,session->{
            var body=BookingHttp.body(request,"email");
            String client=request.getAttribute(ApiGuardFilter.CLIENT_ATTRIBUTE) instanceof String value?value:"0.0.0.0";
            var result=changes.request(session,BookingHttp.id(bookingId),BookingHttp.text(body,"email"),client);
            if(!result.accepted()) { Problems.reject(response,Problems.Code.RATE_LIMITED,result.retryAfterSeconds());return; }
            json(response,202,"{\"accepted\":true}");
        });
    }
    @PostMapping("/{bookingId}/contact")
    public void confirm(@PathVariable String bookingId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        command(request,response,session->{
            if(request.getHeader("If-Match")==null) { BookingHttp.problem(response,428,"VERSION_REQUIRED",false,false);return; }
            var body=BookingHttp.body(request,"token");
            var receipt=changes.confirm(session,BookingHttp.id(bookingId),BookingHttp.header(request,"Idempotency-Key"),
                    new CustomerContactChanges.Confirmation(BookingHttp.version(request),BookingHttp.text(body,"token")));
            if(!receipt.success()) { BookingHttp.business(response,receipt.code());return; }
            response.setHeader("ETag",'"'+Long.toString(receipt.version())+'"');json(response,200,BookingHttp.receipt(receipt));
        });
    }
    private interface Work { void run(SessionBinding session) throws BookingHttp.Invalid,IOException; }
    private static void command(HttpServletRequest request,HttpServletResponse response,Work work) throws IOException {
        if(!(request.getAttribute(ApiGuardFilter.SESSION_ATTRIBUTE) instanceof Snapshot session)) { BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return; }
        try {
            if(!request.getParameterMap().isEmpty())throw new BookingHttp.Invalid();
            work.run(new SessionBinding(session.reference().id(),session.reference().browserHash()));
        } catch(BookingHttp.Invalid | IllegalArgumentException invalid) { BookingHttp.problem(response,400,"INVALID_REQUEST",false,false); }
        catch(DomainFailure invalid) { BookingHttp.problem(response,400,invalid.code().equals("INVALID_CONTACT_PROOF")?"PROOF_INVALID":"INVALID_REQUEST",false,false); }
        catch(Failure failure) { BookingHttp.failure(response,failure); }
        catch(RuntimeException unavailable) { BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false); }
    }
    private static void json(HttpServletResponse response,int status,String value) throws IOException {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);response.setStatus(status);response.setHeader("Cache-Control","no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);response.setCharacterEncoding("UTF-8");response.setContentLength(bytes.length);response.getOutputStream().write(bytes);
    }
}
