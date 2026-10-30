package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.port.out.CustomerErasureStore;
import com.purpuragenda.application.port.out.CustomerErasureStore.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.port.out.SessionStore.Snapshot;
import com.purpuragenda.domain.DomainFailure;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin")
public final class AdministratorErasureController {
    private static final com.fasterxml.jackson.databind.json.JsonMapper JSON=com.fasterxml.jackson.databind.json.JsonMapper.builder().addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private final CustomerErasureStore store;private final SessionAccess access;private final SessionCookies cookies;
    public AdministratorErasureController(CustomerErasureStore store,SessionAccess access,SessionCookies cookies){this.store=Objects.requireNonNull(store);this.access=Objects.requireNonNull(access);this.cookies=Objects.requireNonNull(cookies);}
    @GetMapping("/customers/{customerId}/privacy-review")
    public void overview(@PathVariable String customerId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        read(request,response,s->JSON.valueToTree(store.overview(s,BookingHttp.id(customerId))).toString());
    }
    @PostMapping("/customers/{customerId}/erasures")
    public void prepare(@PathVariable String customerId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        command(request,response,s->{var body=BookingHttp.body(request,"scopes","reason");var scopes=body.get("scopes");if(scopes==null||!scopes.isArray())throw new BookingHttp.Invalid();var selected=new ArrayList<CustomerErasureStore.Scope>();for(var node:scopes){if(!node.isObject()||node.size()!=2)throw new BookingHttp.Invalid();selected.add(new CustomerErasureStore.Scope(Kind.valueOf(BookingHttp.text(node,"kind")),BookingHttp.id(BookingHttp.text(node,"id"))));}return receipt(store.prepare(s,BookingHttp.id(customerId),BookingHttp.header(request,"Idempotency-Key"),new Request(selected,BookingHttp.text(body,"reason"))));});
    }
    @GetMapping("/erasures/{id}")
    public void preview(@PathVariable String id,HttpServletRequest request,HttpServletResponse response) throws IOException {
        read(request,response,s->JSON.valueToTree(store.preview(s,BookingHttp.id(id))).toString());
    }
    @PostMapping("/erasures/{id}/confirm")
    public void confirm(@PathVariable String id,HttpServletRequest request,HttpServletResponse response) throws IOException {
        if(request.getHeader("If-Match")==null){BookingHttp.problem(response,428,"VERSION_REQUIRED",false,false);return;}
        command(request,response,s->{var body=BookingHttp.body(request,"digest","confirmed");if(!body.path("confirmed").isBoolean())throw new BookingHttp.Invalid();return receipt(store.confirm(s,BookingHttp.id(id),BookingHttp.header(request,"Idempotency-Key"),BookingHttp.version(request),new Review(BookingHttp.text(body,"digest"),body.path("confirmed").booleanValue())));});
    }
    @PostMapping("/customers/{customerId}/privacy-holds")
    public void hold(@PathVariable String customerId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        command(request,response,s->{var body=BookingHttp.body(request,"kind","targetId","reason","through","confirmed");if(!body.path("confirmed").isBoolean())throw new BookingHttp.Invalid();var through=body.get("through");LocalDate end=through==null||through.isNull()?null:LocalDate.parse(BookingHttp.text(body,"through"));return receipt(store.hold(s,BookingHttp.id(customerId),BookingHttp.header(request,"Idempotency-Key"),new Hold(new CustomerErasureStore.Scope(Kind.valueOf(BookingHttp.text(body,"kind")),BookingHttp.id(BookingHttp.text(body,"targetId"))),BookingHttp.text(body,"reason"),end,body.path("confirmed").booleanValue())));});
    }
    @PostMapping("/privacy-holds/{id}/release")
    public void release(@PathVariable String id,HttpServletRequest request,HttpServletResponse response) throws IOException {
        command(request,response,s->{var body=BookingHttp.body(request,"reason","confirmed");if(!body.path("confirmed").isBoolean())throw new BookingHttp.Invalid();return receipt(store.releaseHold(s,BookingHttp.id(id),BookingHttp.header(request,"Idempotency-Key"),BookingHttp.text(body,"reason"),body.path("confirmed").booleanValue()));});
    }
    private interface Read {String run(SessionBinding session) throws BookingHttp.Invalid;}
    private interface Command {String run(SessionBinding session) throws BookingHttp.Invalid;}
    private void read(HttpServletRequest request,HttpServletResponse response,Read work) throws IOException {
        try{
            var session=access.inspect(cookies.read(request)).orElse(null);SessionBinding binding=binding(session,request,response);if(binding==null)return;
            AdministratorHistoryHttp.json(response,200,work.run(binding));
        }catch(BookingHttp.Invalid|RuntimeException failure){failed(response,failure);}
    }
    private void command(HttpServletRequest request,HttpServletResponse response,Command work) throws IOException {
        try{
            var session=request.getAttribute(ApiGuardFilter.SESSION_ATTRIBUTE) instanceof Snapshot value?value:null;SessionBinding binding=binding(session,request,response);if(binding==null)return;
            AdministratorHistoryHttp.json(response,200,work.run(binding));
        }catch(BookingHttp.Invalid|RuntimeException failure){failed(response,failure);}
    }
    private static SessionBinding binding(Snapshot session,HttpServletRequest request,HttpServletResponse response) throws IOException,BookingHttp.Invalid {
        if(session==null){BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return null;}
        if(session.kind()!=com.purpuragenda.application.port.out.SessionStore.Kind.ADMIN){BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);return null;}
        if(!request.getParameterMap().isEmpty())throw new BookingHttp.Invalid();var ref=session.reference();return new SessionBinding(ref.id(),ref.browserHash());
    }
    private static void failed(HttpServletResponse response,Exception failure) throws IOException {
        if(failure instanceof Rejected rejected)BookingHttp.business(response,rejected.code);
        else if(failure instanceof BookingHttp.Invalid || failure instanceof DomainFailure || failure instanceof IllegalArgumentException || failure instanceof DateTimeException)BookingHttp.problem(response,400,"INVALID_REQUEST",false,false);
        else if(failure instanceof Failure denial){if(denial.kind()==Failure.Kind.NOT_FOUND)BookingHttp.problem(response,404,"ERASURE_OR_TARGET_NOT_FOUND",false,false);else AdministratorHistoryHttp.failure(response,denial);}
        else BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false);
    }
    private static final class Rejected extends RuntimeException {final Code code;Rejected(Code code){super(null,null,false,false);this.code=code;}}
    private static String receipt(Receipt r){if(!r.success())throw new Rejected(r.code());return AdministratorHistoryHttp.JSON.createObjectNode().put("erasureId",r.entityId().toString()).put("version",r.version()).toString();}
}
