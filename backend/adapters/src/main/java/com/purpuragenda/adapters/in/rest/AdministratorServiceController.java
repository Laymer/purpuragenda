package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.port.out.SessionStore.*;
import com.purpuragenda.application.scheduling.AdministratorConfiguration;
import com.purpuragenda.application.scheduling.AdministratorConfiguration.ServiceValues;
import com.purpuragenda.domain.DomainFailure;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/configuration/services")
public final class AdministratorServiceController {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final AdministratorConfiguration configuration;
    public AdministratorServiceController(AdministratorConfiguration configuration) { this.configuration=Objects.requireNonNull(configuration); }
    @PostMapping
    public ResponseEntity<String> create(HttpServletRequest request,HttpServletResponse response) throws IOException {
        return command(request,response,null,"create");
    }
    @PostMapping("/{id}")
    public ResponseEntity<String> update(@PathVariable String id,HttpServletRequest request,HttpServletResponse response) throws IOException {
        return command(request,response,id,"update");
    }
    @PostMapping("/{id}/archive")
    public ResponseEntity<String> archive(@PathVariable String id,HttpServletRequest request,HttpServletResponse response) throws IOException {
        return command(request,response,id,"archive");
    }
    @PostMapping("/{id}/restore")
    public ResponseEntity<String> restore(@PathVariable String id,HttpServletRequest request,HttpServletResponse response) throws IOException {
        return command(request,response,id,"restore");
    }
    private ResponseEntity<String> command(HttpServletRequest request,HttpServletResponse response,String id,String action) throws IOException {
        if(!(request.getAttribute(ApiGuardFilter.SESSION_ATTRIBUTE) instanceof Snapshot session)) { BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return null; }
        if(session.kind()!=Kind.ADMIN) { BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);return null; }
        if(id!=null && request.getHeader("If-Match")==null) { BookingHttp.problem(response,428,"VERSION_REQUIRED",false,false);return null; }
        try {
            if(id==null && request.getHeader("If-Match")!=null)throw new BookingHttp.Invalid();
            var body=action.equals("create") || action.equals("update")?BookingHttp.body(request,"name","durationMinutes","priceMinor","reason"):BookingHttp.body(request,"reason");
            var binding=new SessionBinding(session.reference().id(),session.reference().browserHash());
            String key=BookingHttp.header(request,"Idempotency-Key"),reason=BookingHttp.text(body,"reason");
            UUID target=id==null?null:BookingHttp.id(id);long version=id==null?0:BookingHttp.version(request);
            Receipt receipt=switch(action) {
                case "create" -> configuration.createService(binding,key,values(body),reason);
                case "update" -> configuration.updateService(binding,target,key,version,values(body),reason);
                case "archive" -> configuration.archiveService(binding,target,key,version,reason);
                default -> configuration.restoreService(binding,target,key,version,reason);
            };
            if(!receipt.success()) { BookingHttp.business(response,receipt.code());return null; }
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.APPLICATION_JSON)
                    .eTag('"'+Long.toString(receipt.version())+'"').body(JSON.createObjectNode().put("id",receipt.entityId().toString()).put("version",receipt.version()).toString());
        } catch(BookingHttp.Invalid | DomainFailure | IllegalArgumentException | ArithmeticException invalid) { BookingHttp.problem(response,400,"INVALID_REQUEST",false,false); }
        catch(Failure failure) {
            switch(failure.kind()) {
                case AUTHENTICATION_REQUIRED -> BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);
                case FRESH_AUTHENTICATION_REQUIRED -> BookingHttp.problem(response,403,"AUTHENTICATION_REQUIRED",false,false);
                case FORBIDDEN -> BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);
                case NOT_FOUND -> BookingHttp.problem(response,404,"SERVICE_NOT_FOUND",false,false);
                default -> BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false);
            }
        } catch(RuntimeException failure) { BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false); }
        return null;
    }
    private static ServiceValues values(JsonNode body) throws BookingHttp.Invalid {
        var duration=body.get("durationMinutes");var price=body.get("priceMinor");
        if(!duration.isIntegralNumber() || !duration.canConvertToInt() || !price.isIntegralNumber() || !price.canConvertToLong())throw new BookingHttp.Invalid();
        return new ServiceValues(BookingHttp.text(body,"name"),duration.intValue(),price.longValue());
    }
}
