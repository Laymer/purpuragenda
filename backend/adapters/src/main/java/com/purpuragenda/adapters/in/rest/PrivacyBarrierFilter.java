package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.port.out.SessionStore;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.springframework.web.filter.OncePerRequestFilter;

/** Stops existing-session reads on every running instance while a privacy/restore barrier is active. */
public final class PrivacyBarrierFilter extends OncePerRequestFilter {
    private final BooleanSupplier blocked;
    public PrivacyBarrierFilter(BooleanSupplier blocked){this.blocked=Objects.requireNonNull(blocked);}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        boolean stopped;try{stopped=blocked.getAsBoolean();}catch(RuntimeException failure){stopped=true;}
        String path=request.getRequestURI(),method=request.getMethod();
        boolean health=method.equals("GET")&&(path.equals("/actuator/health")||path.equals("/actuator/health/readiness")||path.equals("/actuator/health/liveness"));
        boolean session=method.equals("POST")&&(path.equals("/api/v1/session/bootstrap")||path.equals("/api/v1/session/logout")||path.equals("/api/v1/admin/login"))||method.equals("GET")&&path.equals("/api/v1/session");
        boolean retry=method.equals("POST")&&path.matches("/api/v1/admin/erasures/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/confirm")&&request.getAttribute(ApiGuardFilter.SESSION_ATTRIBUTE) instanceof SessionStore.Snapshot value&&value.kind()==SessionStore.Kind.ADMIN;
        if(stopped&&!health&&!session&&!retry){BookingHttp.problem(response,503,"MAINTENANCE",true,false);return;}
        chain.doFilter(request,response);
    }
}
