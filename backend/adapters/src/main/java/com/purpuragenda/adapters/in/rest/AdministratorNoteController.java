package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import com.purpuragenda.application.port.out.SessionStore.*;
import com.purpuragenda.application.scheduling.AdministratorNotes;
import com.purpuragenda.domain.DomainFailure;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.DateTimeException;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/bookings/{bookingId}/notes")
public final class AdministratorNoteController {
    private final AdministratorNotes notes;
    public AdministratorNoteController(AdministratorNotes notes){this.notes=Objects.requireNonNull(notes);}
    @PostMapping
    public void append(@PathVariable String bookingId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        if(!(request.getAttribute(ApiGuardFilter.SESSION_ATTRIBUTE) instanceof Snapshot session)){BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return;}
        if(session.kind()!=Kind.ADMIN){BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);return;}
        try {
            if(!request.getParameterMap().isEmpty() || request.getHeader("If-Match")!=null)throw new BookingHttp.Invalid();
            UUID id=BookingHttp.id(bookingId);var body=BookingHttp.body(request,"text");var reference=session.reference();
            var receipt=notes.append(new SessionBinding(reference.id(),reference.browserHash()),id,BookingHttp.header(request,"Idempotency-Key"),new AdministratorNotes.Addition(BookingHttp.text(body,"text")));
            if(!receipt.success()){BookingHttp.business(response,receipt.code());return;}
            AdministratorHistoryHttp.json(response,201,AdministratorHistoryHttp.JSON.createObjectNode().put("bookingId",id.toString()).put("noteId",receipt.entityId().toString()).put("version",receipt.version()).toString());
        } catch(BookingHttp.Invalid | DomainFailure | IllegalArgumentException | DateTimeException invalid){BookingHttp.problem(response,400,"INVALID_REQUEST",false,false);}
        catch(Failure failure){AdministratorHistoryHttp.failure(response,failure);}
        catch(RuntimeException unavailable){BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false);}
    }
}
