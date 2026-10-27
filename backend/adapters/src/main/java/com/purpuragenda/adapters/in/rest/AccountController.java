package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SessionStore.*;
import com.purpuragenda.domain.DomainFailure;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Origin/session/CSRF guarded account credential actions; enrollment and public administrator registration are absent. */
@RestController
@RequestMapping("/api/v1/account")
public final class AccountController {
    private final AccountLogin login;private final AccountPasswordChange passwords;private final SessionCookies cookies;private final long minimumNanos;
    public AccountController(AccountLogin login,AccountPasswordChange passwords,SessionCookies cookies,Duration minimumLatency) {
        this.login=Objects.requireNonNull(login);this.passwords=Objects.requireNonNull(passwords);this.cookies=Objects.requireNonNull(cookies);
        if(minimumLatency.isNegative() || minimumLatency.compareTo(Duration.ofSeconds(5))>0)throw new IllegalArgumentException("INVALID_LATENCY");minimumNanos=minimumLatency.toNanos();
    }
    @PostMapping("/login")
    public ResponseEntity<String> authenticate(HttpServletRequest request,HttpServletResponse response) throws IOException {
        if(!(request.getAttribute(ApiGuardFilter.SESSION_ATTRIBUTE) instanceof Snapshot session)) { Problems.reject(response,Problems.Code.SESSION_REQUIRED);return null; }
        long start=System.nanoTime();char[] password=null;
        try {
            String[] body=JsonBodies.read(request,"email","password");password=body[1].toCharArray();
            var result=login.authenticate(session.reference(),body[0],password,client(request));
            if(result instanceof AccountLogin.Throttled limited) { Problems.reject(response,Problems.Code.RATE_LIMITED,limited.retryAfterSeconds());return null; }
            if(!(result instanceof AccountLogin.Authenticated authenticated)) { Problems.reject(response,Problems.Code.AUTHENTICATION_FAILED);return null; }
            try(authenticated) {
                var value=authenticated.session();char[] bearer=value.cookie(),csrf=value.csrf();
                try { cookies.write(response,bearer);return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.APPLICATION_JSON).body(SessionController.state(value.view(),new String(csrf))); }
                finally { Arrays.fill(bearer,'\0');Arrays.fill(csrf,'\0'); }
            }
        } catch(JsonBodies.Invalid | DomainFailure | IllegalArgumentException invalid) { Problems.reject(response,Problems.Code.INVALID_REQUEST);return null; }
        catch(RuntimeException unavailable) { Problems.reject(response,Problems.Code.TEMPORARILY_UNAVAILABLE);return null; }
        finally { if(password!=null)Arrays.fill(password,'\0');pad(start); }
    }
    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(HttpServletRequest request,HttpServletResponse response) throws IOException {
        if(!(request.getAttribute(ApiGuardFilter.SESSION_ATTRIBUTE) instanceof Snapshot session) || session.kind()!=Kind.ACCOUNT) { Problems.reject(response,Problems.Code.SESSION_REQUIRED);return null; }
        long start=System.nanoTime();char[] previous=null,replacement=null;
        try {
            String[] body=JsonBodies.read(request,"email","currentPassword","newPassword");previous=body[1].toCharArray();replacement=body[2].toCharArray();
            var result=passwords.change(body[0],new SessionBinding(session.reference().id(),session.reference().browserHash()),previous,replacement,client(request));
            if(result.retryAfterSeconds()>0) { Problems.reject(response,Problems.Code.RATE_LIMITED,result.retryAfterSeconds());return null; }
            if(!result.changed()) { Problems.reject(response,Problems.Code.AUTHENTICATION_FAILED);return null; }
            cookies.clear(response);return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL,"no-store").build();
        } catch(JsonBodies.Invalid | DomainFailure | IllegalArgumentException invalid) { Problems.reject(response,Problems.Code.INVALID_REQUEST);return null; }
        catch(RuntimeException unavailable) { Problems.reject(response,Problems.Code.TEMPORARILY_UNAVAILABLE);return null; }
        finally { if(previous!=null)Arrays.fill(previous,'\0');if(replacement!=null)Arrays.fill(replacement,'\0');pad(start); }
    }
    private static String client(HttpServletRequest request) { return request.getAttribute(ApiGuardFilter.CLIENT_ATTRIBUTE) instanceof String value?value:"0.0.0.0"; }
    private void pad(long started) {
        long remaining=minimumNanos-(System.nanoTime()-started);
        if(remaining>0)try { Thread.sleep(remaining/1_000_000,(int)(remaining%1_000_000)); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
