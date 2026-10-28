package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.port.out.AdministratorOperationsQuery;
import com.purpuragenda.application.port.out.AdministratorOperationsQuery.*;
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
public final class AdministratorOperationsController {
    private final AdministratorOperationsQuery query;private final SessionAccess access;private final SessionCookies cookies;
    public AdministratorOperationsController(AdministratorOperationsQuery query,SessionAccess access,SessionCookies cookies){this.query=Objects.requireNonNull(query);this.access=Objects.requireNonNull(access);this.cookies=Objects.requireNonNull(cookies);}
    @GetMapping("/reconciliation")
    public void reconciliation(HttpServletRequest request,HttpServletResponse response) throws IOException {
        read(request,response,session->{
            var parameters=parameters(request,false);var page=query.reconciliation(session,parameters.booking(),parameters.page());var body=page(page);var entries=body.putArray("entries");
            for(var item:page.entries())entries.addObject().put("receiptId",item.receiptId().toString()).put("bookingId",item.bookingId().toString()).put("reference",item.reference()).put("state",item.state())
                    .put("currency",item.currency()).put("receivedMinor",item.receivedMinor()).put("refundedMinor",item.refundedMinor()).put("dueMinor",item.dueMinor()).put("outstandingMinor",item.outstandingMinor()).put("reason",item.reason()).put("verifiedAt",item.verifiedAt().toString());return body.toString();
        });
    }
    @GetMapping("/notifications")
    public void notifications(HttpServletRequest request,HttpServletResponse response) throws IOException {
        read(request,response,session->{
            var parameters=parameters(request,true);var page=query.notifications(session,parameters.booking(),parameters.state(),parameters.page());var body=page(page);var entries=body.putArray("entries");
            for(var item:page.entries())entries.addObject().put("id",item.id().toString()).put("bookingId",item.bookingId().toString()).put("reference",item.reference()).put("audience",item.audience()).put("template",item.template())
                    .put("version",item.version()).put("state",item.state().name()).put("attempts",item.attempts()).put("dueAt",item.dueAt().toString()).put("safeCode",item.safeCode());return body.toString();
        });
    }
    private record Parameters(UUID booking,NotificationState state,Request page){@Override public String toString(){return "OperationsParameters[REDACTED]";}}
    private static Parameters parameters(HttpServletRequest request,boolean notifications) throws BookingHttp.Invalid {
        var values=request.getParameterMap();if(!(notifications?Set.of("bookingId","state","cursor","limit"):Set.of("bookingId","cursor","limit")).containsAll(values.keySet()))throw new BookingHttp.Invalid();
        for(String[] value:values.values())if(value==null || value.length!=1 || value[0]==null || value[0].isEmpty() || value[0].length()>200)throw new BookingHttp.Invalid();
        String booking=request.getParameter("bookingId"),state=request.getParameter("state"),cursor=request.getParameter("cursor"),limit=request.getParameter("limit");
        if(limit!=null && !limit.matches("[1-9][0-9]{0,2}"))throw new BookingHttp.Invalid();
        return new Parameters(booking==null?null:BookingHttp.id(booking),state==null?null:NotificationState.valueOf(state),new Request(cursor==null?null:BookingHttp.id(cursor),limit==null?50:Integer.parseInt(limit)));
    }
    private static ObjectNode page(Page<?> page){return AdministratorHistoryHttp.JSON.createObjectNode().put("nextCursor",page.next()==null?null:page.next().toString()).put("serverNow",page.observedAt().toString());}
    private interface Read {String run(SessionBinding session) throws BookingHttp.Invalid;}
    private void read(HttpServletRequest request,HttpServletResponse response,Read work) throws IOException {
        try {
            var session=access.inspect(cookies.read(request));if(session.isEmpty()){BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return;}
            if(session.get().kind()!=SessionStore.Kind.ADMIN){BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);return;}
            var reference=session.get().reference();AdministratorHistoryHttp.json(response,200,work.run(new SessionBinding(reference.id(),reference.browserHash())));
        } catch(BookingHttp.Invalid | DomainFailure | IllegalArgumentException | DateTimeException | ArithmeticException invalid){BookingHttp.problem(response,400,"INVALID_REQUEST",false,false);}
        catch(Failure failure){AdministratorHistoryHttp.failure(response,failure);}
        catch(RuntimeException unavailable){BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false);}
    }
}
