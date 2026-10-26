package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.AdministratorLogin;
import com.purpuragenda.application.port.out.SessionStore.Snapshot;
import com.purpuragenda.domain.DomainFailure;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Password/TOTP login only. Recovery grants and public administrator registration have no route here. */
@RestController
@RequestMapping("/api/v1/admin/login")
public final class AdministratorLoginController {
    private final AdministratorLogin login;
    private final SessionCookies cookies;
    private final long minimumNanos;
    public AdministratorLoginController(AdministratorLogin login,SessionCookies cookies,Duration minimumLatency) {
        this.login=Objects.requireNonNull(login);this.cookies=Objects.requireNonNull(cookies);
        if(minimumLatency.isNegative() || minimumLatency.compareTo(Duration.ofSeconds(5))>0)throw new IllegalArgumentException("INVALID_LATENCY");
        minimumNanos=minimumLatency.toNanos();
    }
    @PostMapping
    public ResponseEntity<String> authenticate(HttpServletRequest request,HttpServletResponse response) throws IOException {
        if(!(request.getAttribute(ApiGuardFilter.SESSION_ATTRIBUTE) instanceof Snapshot session)) {
            Problems.reject(response,Problems.Code.SESSION_REQUIRED);return null;
        }
        long start=System.nanoTime();char[] password=null;
        try {
            String[] body=JsonBodies.read(request,"email","password","totp");password=body[1].toCharArray();
            String client=request.getAttribute(ApiGuardFilter.CLIENT_ATTRIBUTE) instanceof String value?value:"0.0.0.0";
            var outcome=login.authenticate(session.reference(),body[0],password,body[2],client);
            if(outcome instanceof AdministratorLogin.Throttled limited) {
                Problems.reject(response,Problems.Code.RATE_LIMITED,limited.retryAfterSeconds());return null;
            }
            if(!(outcome instanceof AdministratorLogin.Authenticated authenticated)) {
                Problems.reject(response,Problems.Code.AUTHENTICATION_FAILED);return null;
            }
            try(authenticated) {
                var value=authenticated.session();char[] bearer=value.cookie(),csrf=value.csrf();
                try {
                    cookies.write(response,bearer);
                    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.APPLICATION_JSON)
                            .body(SessionController.state(value.view(),new String(csrf)));
                } finally { Arrays.fill(bearer,'\0');Arrays.fill(csrf,'\0'); }
            }
        } catch(JsonBodies.Invalid | DomainFailure | IllegalArgumentException invalid) {
            Problems.reject(response,Problems.Code.INVALID_REQUEST);return null;
        } catch(RuntimeException unavailable) {
            Problems.reject(response,Problems.Code.TEMPORARILY_UNAVAILABLE);return null;
        } finally {
            if(password!=null)Arrays.fill(password,'\0');
            long remaining=minimumNanos-(System.nanoTime()-start);
            if(remaining>0)try { Thread.sleep(remaining/1_000_000,(int)(remaining%1_000_000)); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
