package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.port.out.SessionStore.Snapshot;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Objects;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Explicit wire models only: role and deadlines, plus a CSRF proof at bootstrap. */
@RestController
@RequestMapping("/api/v1/session")
public final class SessionController {
    private final SessionAccess access;private final SessionCookies cookies;
    public SessionController(SessionAccess access,SessionCookies cookies) { this.access=Objects.requireNonNull(access);this.cookies=Objects.requireNonNull(cookies); }

    @PostMapping("/bootstrap")
    public ResponseEntity<String> bootstrap(HttpServletRequest request,HttpServletResponse response) throws IOException {
        char[] cookie=cookies.read(request);
        try(var started=access.bootstrap(cookie)) {
            if(started.cookie()!=null) cookies.write(response,started.cookie());
            return json(HttpStatus.OK,state(started.view(),new String(started.csrf())));
        } catch(IllegalStateException unavailable) { Problems.reject(response,Problems.Code.SESSION_UNAVAILABLE);return null; }
    }
    @GetMapping
    public ResponseEntity<String> getSession(HttpServletRequest request,HttpServletResponse response) throws IOException {
        try {
            var session=access.current(cookies.read(request));
            if(session.isPresent()) return json(HttpStatus.OK,state(SessionAccess.view(session.get()),null));
            Problems.reject(response,Problems.Code.SESSION_REQUIRED);
        } catch(IllegalStateException unavailable) { Problems.reject(response,Problems.Code.SESSION_UNAVAILABLE); }
        return null;
    }
    @PostMapping("/logout")
    public ResponseEntity<String> logout(HttpServletRequest request,HttpServletResponse response) throws IOException {
        if(!(request.getAttribute(ApiGuardFilter.SESSION_ATTRIBUTE) instanceof Snapshot session)) { Problems.reject(response,Problems.Code.SESSION_REQUIRED);return null; }
        try { access.logout(session); }
        catch(IllegalStateException unavailable) { Problems.reject(response,Problems.Code.SESSION_UNAVAILABLE);return null; }
        cookies.clear(response);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL,"no-store").build();
    }
    private static String state(SessionAccess.View view,String csrf) {
        return "{\"role\":\""+view.role().name()+"\",\"idleExpiresAt\":\""+view.idleExpiresAt()+"\",\"absoluteExpiresAt\":\""+view.absoluteExpiresAt()+"\""
                +(csrf==null?"":",\"csrfToken\":\""+csrf+"\"")+"}";
    }
    private static ResponseEntity<String> json(HttpStatus status,String body) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
