package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.AccountData;
import com.purpuragenda.application.port.out.AccountDataStore.Fields;
import com.purpuragenda.application.port.out.SessionStore.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Objects;
import org.springframework.web.bind.annotation.*;
import static com.purpuragenda.adapters.in.rest.AccountDataHttp.*;

@RestController
@RequestMapping("/api/v1/account/profile")
public final class AccountProfileController {
    private final AccountData data;
    public AccountProfileController(AccountData data) { this.data=Objects.requireNonNull(data); }
    @PostMapping
    public void update(HttpServletRequest request,HttpServletResponse response) throws IOException {
        if(!(request.getAttribute(ApiGuardFilter.SESSION_ATTRIBUTE) instanceof Snapshot session)) { BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return; }
        if(session.kind()!=Kind.ACCOUNT) { BookingHttp.problem(response,403,"ACCOUNT_ACCESS_REQUIRED",false,false);return; }
        if(request.getHeader("If-Match")==null) { BookingHttp.problem(response,428,"VERSION_REQUIRED",false,false);return; }
        try {
            if(!request.getParameterMap().isEmpty())throw new BookingHttp.Invalid();
            long version=BookingHttp.version(request);String key=BookingHttp.header(request,"Idempotency-Key");var body=BookingHttp.body(request,"firstName","lastName","telephone");
            var receipt=data.update(binding(session),key,version,new Fields(BookingHttp.text(body,"firstName"),BookingHttp.text(body,"lastName"),BookingHttp.text(body,"telephone")));
            if(!receipt.success()) { BookingHttp.business(response,receipt.code());return; }
            output(response,JSON.createObjectNode().put("version",receipt.version()),receipt.version());
        } catch(BookingHttp.Invalid | RuntimeException failure) { failed(response,failure); }
    }
}
