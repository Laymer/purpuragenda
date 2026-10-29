package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.privacy.AdministratorExports;
import com.purpuragenda.application.port.out.CustomerExportStore.Review;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.port.out.SessionStore.*;
import com.purpuragenda.domain.DomainFailure;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.DateTimeException;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin")
public final class AdministratorExportController {
    private final AdministratorExports exports;private final SessionAccess access;private final SessionCookies cookies;
    public AdministratorExportController(AdministratorExports exports,SessionAccess access,SessionCookies cookies){this.exports=Objects.requireNonNull(exports);this.access=Objects.requireNonNull(access);this.cookies=Objects.requireNonNull(cookies);}
    @PostMapping("/customers/{customerId}/exports")
    public void prepare(@PathVariable String customerId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        command(request,response,session->{var body=BookingHttp.body(request,"reason");return exports.prepare(session,BookingHttp.id(customerId),BookingHttp.header(request,"Idempotency-Key"),BookingHttp.text(body,"reason"));});
    }
    @PostMapping("/exports/{exportId}/release")
    public void release(@PathVariable String exportId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        command(request,response,session->{var body=BookingHttp.body(request,"digest","reviewedThirdPartyInformation");
            if(!body.path("reviewedThirdPartyInformation").isBoolean())throw new BookingHttp.Invalid();
            return exports.release(session,BookingHttp.id(exportId),BookingHttp.header(request,"Idempotency-Key"),new Review(BookingHttp.text(body,"digest"),body.path("reviewedThirdPartyInformation").booleanValue()));});
    }
    private interface Command {Receipt run(SessionBinding session) throws BookingHttp.Invalid;}
    private void command(HttpServletRequest request,HttpServletResponse response,Command work) throws IOException {
        if(!(request.getAttribute(ApiGuardFilter.SESSION_ATTRIBUTE) instanceof Snapshot session)){BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return;}
        if(session.kind()!=Kind.ADMIN){BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);return;}
        try{
            if(!request.getParameterMap().isEmpty()||request.getHeader("If-Match")!=null)throw new BookingHttp.Invalid();var ref=session.reference();
            Receipt receipt=work.run(new SessionBinding(ref.id(),ref.browserHash()));if(!receipt.success()){BookingHttp.business(response,receipt.code());return;}
            AdministratorHistoryHttp.json(response,200,AdministratorHistoryHttp.JSON.createObjectNode().put("exportId",receipt.entityId().toString()).put("version",receipt.version()).toString());
        }catch(BookingHttp.Invalid|DomainFailure|IllegalArgumentException|DateTimeException invalid){BookingHttp.problem(response,400,"INVALID_REQUEST",false,false);}
        catch(Failure failure){failure(response,failure);}catch(RuntimeException unavailable){BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false);}
    }
    @GetMapping("/exports/{exportId}")
    public void read(@PathVariable String exportId,HttpServletRequest request,HttpServletResponse response) throws IOException {read(exportId,false,request,response);}
    @GetMapping("/exports/{exportId}/download")
    public void download(@PathVariable String exportId,HttpServletRequest request,HttpServletResponse response) throws IOException {read(exportId,true,request,response);}
    private void read(String exportId,boolean download,HttpServletRequest request,HttpServletResponse response) throws IOException {
        try{
            var session=access.inspect(cookies.read(request));if(session.isEmpty()){BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return;}
            if(session.get().kind()!=Kind.ADMIN){BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);return;}
            if(!request.getParameterMap().isEmpty())throw new BookingHttp.Invalid();var ref=session.get().reference();var doc=exports.read(new SessionBinding(ref.id(),ref.browserHash()),BookingHttp.id(exportId),download);
            if(download){response.setHeader("Content-Disposition","attachment; filename=customer-export.json");response.setStatus(200);response.setHeader("Cache-Control","no-store");response.setContentType("application/octet-stream");response.getOutputStream().write(doc.json().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            else AdministratorHistoryHttp.json(response,200,AdministratorHistoryHttp.JSON.createObjectNode().put("id",doc.id().toString()).put("customerId",doc.customerId().toString()).put("expiresAt",doc.expiresAt().toString()).put("digest",doc.digest()).put("released",doc.released()).put("document",doc.json()).toString());
        }catch(BookingHttp.Invalid|DomainFailure|IllegalArgumentException|DateTimeException invalid){BookingHttp.problem(response,400,"INVALID_REQUEST",false,false);}
        catch(Failure failure){failure(response,failure);}catch(RuntimeException unavailable){BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false);}
    }
    private static void failure(HttpServletResponse response,Failure failure) throws IOException {
        if(failure.kind()==Failure.Kind.NOT_FOUND)BookingHttp.problem(response,404,"EXPORT_OR_CUSTOMER_NOT_FOUND",false,false);else AdministratorHistoryHttp.failure(response,failure);
    }
}
