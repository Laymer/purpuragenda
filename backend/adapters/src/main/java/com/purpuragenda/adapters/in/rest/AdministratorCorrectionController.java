package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.privacy.AdministratorCorrections;
import com.purpuragenda.application.port.out.CustomerCorrectionStore.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.port.out.SessionStore.Snapshot;
import com.purpuragenda.domain.DomainFailure;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.DateTimeException;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin")
public final class AdministratorCorrectionController {
    private final AdministratorCorrections corrections;private final SessionAccess access;private final SessionCookies cookies;
    public AdministratorCorrectionController(AdministratorCorrections corrections,SessionAccess access,SessionCookies cookies){this.corrections=Objects.requireNonNull(corrections);this.access=Objects.requireNonNull(access);this.cookies=Objects.requireNonNull(cookies);}
    @GetMapping("/customers/{customerId}/corrections/{kind}/{targetId}")
    public void target(@PathVariable String customerId,@PathVariable String kind,@PathVariable String targetId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        read(request,response,session->target(corrections.target(session,BookingHttp.id(customerId),kind(kind),BookingHttp.id(targetId))).toString());
    }
    @PostMapping("/customers/{customerId}/corrections/{kind}/{targetId}")
    public void prepare(@PathVariable String customerId,@PathVariable String kind,@PathVariable String targetId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        command(request,response,session->{
            if(request.getHeader("If-Match")!=null)throw new BookingHttp.Invalid();var body=BookingHttp.body(request,"reason","values");var values=body.get("values");if(values==null||!values.isObject())throw new BookingHttp.Invalid();
            var fields=new TreeMap<String,String>();var names=values.fieldNames();while(names.hasNext()){String name=names.next();fields.put(name,BookingHttp.text(values,name));}
            return receipt(corrections.prepare(session,BookingHttp.id(customerId),BookingHttp.id(targetId),BookingHttp.header(request,"Idempotency-Key"),new Change(kind(kind),fields,BookingHttp.text(body,"reason"))));
        });
    }
    @GetMapping("/corrections/{id}")
    public void preview(@PathVariable String id,HttpServletRequest request,HttpServletResponse response) throws IOException {
        read(request,response,session->{var p=corrections.preview(session,BookingHttp.id(id));var out=AdministratorHistoryHttp.JSON.createObjectNode().put("id",p.id().toString()).put("customerId",p.customerId().toString()).put("reason",p.reason()).put("digest",p.digest()).put("expiresAt",p.expiresAt().toString()).put("version",p.version());out.set("before",target(p.before()));out.set("after",AdministratorHistoryHttp.JSON.valueToTree(p.after()));return out.toString();});
    }
    @PostMapping("/corrections/{id}/confirm")
    public void confirm(@PathVariable String id,HttpServletRequest request,HttpServletResponse response) throws IOException {
        if(request.getHeader("If-Match")==null){BookingHttp.problem(response,428,"VERSION_REQUIRED",false,false);return;}
        command(request,response,session->{var body=BookingHttp.body(request,"digest","confirmed");if(!body.path("confirmed").isBoolean())throw new BookingHttp.Invalid();return receipt(corrections.confirm(session,BookingHttp.id(id),BookingHttp.header(request,"Idempotency-Key"),BookingHttp.version(request),new Review(BookingHttp.text(body,"digest"),body.path("confirmed").booleanValue())));});
    }
    private static Kind kind(String value) throws BookingHttp.Invalid {if(!Set.of("profile","note","intake").contains(value))throw new BookingHttp.Invalid();return Kind.valueOf(value.toUpperCase(Locale.ROOT));}
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
        else if(failure instanceof Failure denial){if(denial.kind()==Failure.Kind.NOT_FOUND)BookingHttp.problem(response,404,"CORRECTION_OR_TARGET_NOT_FOUND",false,false);else AdministratorHistoryHttp.failure(response,denial);}
        else BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false);
    }
    private static final class Rejected extends RuntimeException {final Code code;Rejected(Code code){super(null,null,false,false);this.code=code;}}
    private static String receipt(Receipt r){if(!r.success())throw new Rejected(r.code());return AdministratorHistoryHttp.JSON.createObjectNode().put("correctionId",r.entityId().toString()).put("version",r.version()).toString();}
    private static com.fasterxml.jackson.databind.node.ObjectNode target(Target target){var out=AdministratorHistoryHttp.JSON.createObjectNode().put("kind",target.kind().name()).put("targetId",target.targetId().toString()).put("version",target.version());out.set("values",AdministratorHistoryHttp.JSON.valueToTree(target.values()));return out;}
}
