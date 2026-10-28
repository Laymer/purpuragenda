package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import com.purpuragenda.application.port.out.SessionStore.*;
import com.purpuragenda.application.scheduling.AdministratorNotificationRetry;
import com.purpuragenda.domain.DomainFailure;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.DateTimeException;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/notifications/{jobId}/retry")
public final class AdministratorNotificationRetryController {
    private final AdministratorNotificationRetry retry;
    public AdministratorNotificationRetryController(AdministratorNotificationRetry retry){this.retry=Objects.requireNonNull(retry);}
    @PostMapping
    public void retry(@PathVariable String jobId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        if(!(request.getAttribute(ApiGuardFilter.SESSION_ATTRIBUTE) instanceof Snapshot session)){BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return;}
        if(session.kind()!=Kind.ADMIN){BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);return;}
        if(request.getHeader("If-Match")==null){BookingHttp.problem(response,428,"VERSION_REQUIRED",false,false);return;}
        try {
            if(!request.getParameterMap().isEmpty())throw new BookingHttp.Invalid();UUID id=BookingHttp.id(jobId);var body=BookingHttp.body(request,"reason");var reference=session.reference();
            var receipt=retry.retry(new SessionBinding(reference.id(),reference.browserHash()),id,BookingHttp.header(request,"Idempotency-Key"),new AdministratorNotificationRetry.Request(BookingHttp.version(request),BookingHttp.text(body,"reason")));
            if(!receipt.success()){BookingHttp.business(response,receipt.code());return;}
            AdministratorHistoryHttp.json(response,202,AdministratorHistoryHttp.JSON.createObjectNode().put("jobId",id.toString()).put("auditId",receipt.entityId().toString()).put("version",receipt.version()).toString());
        } catch(BookingHttp.Invalid | DomainFailure | IllegalArgumentException | DateTimeException | ArithmeticException invalid){BookingHttp.problem(response,400,"INVALID_REQUEST",false,false);}
        catch(Failure failure){if(failure.kind()==Failure.Kind.NOT_FOUND)BookingHttp.problem(response,404,"NOTIFICATION_NOT_FOUND",false,false);else AdministratorHistoryHttp.failure(response,failure);}
        catch(RuntimeException unavailable){BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false);}
    }
}
