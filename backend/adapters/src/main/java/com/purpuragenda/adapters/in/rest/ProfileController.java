package com.purpuragenda.adapters.in.rest;
import com.purpuragenda.application.usecase.OperationsService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/account/profile")
public class ProfileController {
    private final OperationsService operations;
    public ProfileController(OperationsService operations){this.operations=operations;}
    public record ProfileBody(Map<String,String> fields){}
    @GetMapping public Object get(HttpServletRequest request){return operations.profile(SessionAccess.actor(request));}
    @PutMapping public Object put(@RequestBody ProfileBody body,@RequestHeader(value="If-Match",required=false)String version,HttpServletRequest request){operations.profile(SessionAccess.actor(request),BookingController.version(version),body.fields());return get(request);}
}
