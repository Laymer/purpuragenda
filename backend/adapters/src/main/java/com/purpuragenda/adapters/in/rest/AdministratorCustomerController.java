package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.port.out.AdministratorCustomerQuery;
import com.purpuragenda.application.port.out.AdministratorCustomerQuery.*;
import com.purpuragenda.application.port.out.AdministratorHistoryQuery.Cursor;
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
@RequestMapping("/api/v1/admin/customers")
public final class AdministratorCustomerController {
    private final AdministratorCustomerQuery query;private final SessionAccess access;private final SessionCookies cookies;
    public AdministratorCustomerController(AdministratorCustomerQuery query,SessionAccess access,SessionCookies cookies) {
        this.query=Objects.requireNonNull(query);this.access=Objects.requireNonNull(access);this.cookies=Objects.requireNonNull(cookies);
    }
    @GetMapping
    public void search(HttpServletRequest request,HttpServletResponse response) throws IOException {
        read(request,response,session->{
            var values=request.getParameterMap();if(!Set.of("query","cursor","limit").containsAll(values.keySet()))throw new BookingHttp.Invalid();
            for(var entry:values.entrySet()) {var value=entry.getValue();if(value==null || value.length!=1 || value[0]==null || value[0].isEmpty() || value[0].length()>(entry.getKey().equals("query")?508:200))throw new BookingHttp.Invalid();}
            String text=request.getParameter("query"),cursor=request.getParameter("cursor"),limit=request.getParameter("limit");
            if(text==null || limit!=null && !limit.matches("[1-9][0-9]{0,2}"))throw new BookingHttp.Invalid();
            var page=query.search(session,new Search(text,cursor==null?null:BookingHttp.id(cursor),limit==null?50:Integer.parseInt(limit)));
            var body=AdministratorHistoryHttp.JSON.createObjectNode().put("serverNow",page.observedAt().toString()).put("nextCursor",page.next()==null?null:page.next().toString());var entries=body.putArray("entries");
            for(var customer:page.entries())entries.add(customer(customer));return body.toString();
        });
    }
    @GetMapping("/{customerId}/bookings")
    public void bookings(@PathVariable String customerId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        read(request,response,session->{
            var result=query.bookings(session,BookingHttp.id(customerId),AdministratorHistoryHttp.parameters(request,false));var page=result.history();
            var body=AdministratorHistoryHttp.JSON.createObjectNode().put("serverNow",page.observedAt().toString()).put("nextCursor",page.next()==null?null:AdministratorHistoryHttp.cursor(new Cursor(page.next().start(),page.next().id())));
            body.set("customer",customer(result.customer()));var entries=body.putArray("bookings");
            for(var booking:page.bookings())entries.addObject().put("id",booking.id().toString()).put("reference",booking.reference()).put("state",booking.state().name())
                    .put("startInstant",booking.start().toString()).put("sessionEndInstant",booking.sessionEnd().toString()).put("pendingExpired",booking.pendingExpired())
                    .put("serviceName",booking.serviceName()).put("priceMinor",booking.price().minor()).put("currency",booking.price().currency());return body.toString();
        });
    }
    private static ObjectNode customer(Customer customer) {return AdministratorHistoryHttp.JSON.createObjectNode().put("id",customer.id().toString()).put("email",customer.email()).put("enrolled",customer.enrolled());}
    private interface Read {String run(SessionBinding session) throws BookingHttp.Invalid;}
    private void read(HttpServletRequest request,HttpServletResponse response,Read work) throws IOException {
        try {
            var session=access.inspect(cookies.read(request));
            if(session.isEmpty()){BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return;}
            if(session.get().kind()!=SessionStore.Kind.ADMIN){BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);return;}
            var reference=session.get().reference();AdministratorHistoryHttp.json(response,200,work.run(new SessionBinding(reference.id(),reference.browserHash())));
        } catch(BookingHttp.Invalid | DomainFailure | IllegalArgumentException | DateTimeException invalid){BookingHttp.problem(response,400,"INVALID_REQUEST",false,false);}
        catch(Failure failure){if(failure.kind()==Failure.Kind.NOT_FOUND)BookingHttp.problem(response,404,"CUSTOMER_NOT_FOUND",false,false);else AdministratorHistoryHttp.failure(response,failure);}
        catch(RuntimeException unavailable){BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false);}
    }
}
