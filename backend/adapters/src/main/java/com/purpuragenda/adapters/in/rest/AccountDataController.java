package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.*;
import com.purpuragenda.application.port.out.AccountDataStore.Cursor;
import com.purpuragenda.application.port.out.SessionStore.Kind;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import static com.purpuragenda.adapters.in.rest.AccountDataHttp.*;

/** Account reads retain fresh private representations even when conditional cache headers are supplied. */
@RestController
@RequestMapping("/api/v1/account")
public final class AccountDataController {
    private final AccountData data;private final SessionAccess sessions;private final SessionCookies cookies;
    public AccountDataController(AccountData data,SessionAccess sessions,SessionCookies cookies) { this.data=Objects.requireNonNull(data);this.sessions=Objects.requireNonNull(sessions);this.cookies=Objects.requireNonNull(cookies); }
    @GetMapping("/profile")
    public void profile(HttpServletRequest request,HttpServletResponse response) throws IOException {
        try {
            if(!request.getParameterMap().isEmpty())throw new BookingHttp.Invalid();
            var session=sessions.inspect(cookies.read(request));
            if(session.isEmpty()) { BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return; }
            if(session.get().kind()!=Kind.ACCOUNT) { BookingHttp.problem(response,403,"ACCOUNT_ACCESS_REQUIRED",false,false);return; }
            var value=data.profile(binding(session.get()));var profile=value.profile();var fields=profile.fields();
            output(response,JSON.createObjectNode().put("loginEmail",value.loginEmail()).put("version",profile.version())
                    .put("firstName",fields.firstName()).put("lastName",fields.lastName()).put("telephone",fields.telephone()).put("observedAt",value.observedAt().toString()),profile.version());
        } catch(BookingHttp.Invalid | RuntimeException failure) { failed(response,failure); }
    }
    @GetMapping("/history")
    public void history(HttpServletRequest request,HttpServletResponse response) throws IOException {
        try {
            for(var entry:request.getParameterMap().entrySet())if(!Set.of("limit","beforeStart","beforeId").contains(entry.getKey()) || entry.getValue().length!=1)throw new BookingHttp.Invalid();
            String limitValue=request.getParameter("limit"),start=request.getParameter("beforeStart"),id=request.getParameter("beforeId");
            if(limitValue!=null && !limitValue.matches("[1-9][0-9]{0,2}"))throw new BookingHttp.Invalid();
            int limit=limitValue==null?20:Integer.parseInt(limitValue);if(limit>100 || (start==null)!=(id==null))throw new BookingHttp.Invalid();
            Cursor cursor=start==null?null:new Cursor(Instant.parse(start),BookingHttp.id(id));
            var session=sessions.inspect(cookies.read(request));
            if(session.isEmpty()) { BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return; }
            if(session.get().kind()!=Kind.ACCOUNT) { BookingHttp.problem(response,403,"ACCOUNT_ACCESS_REQUIRED",false,false);return; }
            var page=data.history(binding(session.get()),cursor,limit);var body=JSON.createObjectNode().put("observedAt",page.observedAt().toString());var items=body.putArray("bookings");
            for(var booking:page.bookings())items.addObject().put("id",booking.id().toString()).put("reference",booking.reference()).put("state",booking.state().name())
                    .put("startInstant",booking.start().toString()).put("sessionEndInstant",booking.sessionEnd().toString()).put("pendingExpired",booking.pendingExpired())
                    .put("serviceName",booking.serviceName()).put("priceMinor",booking.price().minor()).put("currency",booking.price().currency());
            if(page.next()==null)body.putNull("next");else body.putObject("next").put("beforeStart",page.next().start().toString()).put("beforeId",page.next().id().toString());
            output(response,body,null);
        } catch(BookingHttp.Invalid | RuntimeException failure) { failed(response,failure); }
    }
}
