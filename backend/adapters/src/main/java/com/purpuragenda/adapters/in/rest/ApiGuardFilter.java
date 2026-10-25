package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.SessionAccess;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Unsafe-method guard for every route. Requires the single exact configured Origin (never derived from
 * forwarded headers), no cross-site Fetch Metadata, a live persisted session and its X-CSRF-Token. Only
 * the session bootstrap is exempt from the session and CSRF steps; it still needs the exact Origin.
 */
public final class ApiGuardFilter extends OncePerRequestFilter {
    public static final String SESSION_ATTRIBUTE=ApiGuardFilter.class.getName()+".session";
    public static final String CLIENT_ATTRIBUTE=ApiGuardFilter.class.getName()+".client";
    static final String BOOTSTRAP="/api/v1/session/bootstrap";
    private static final Pattern CSRF=Pattern.compile("[A-Za-z0-9_-]{43}");
    private final String origin;private final SessionAccess access;private final SessionCookies cookies;private final ClientAddress clients;
    public ApiGuardFilter(String origin,SessionAccess access,SessionCookies cookies,ClientAddress clients) {
        this.origin=Objects.requireNonNull(origin);this.access=access;this.cookies=cookies;this.clients=clients;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        request.setAttribute(CLIENT_ATTRIBUTE,clients.resolve(request));
        String method=request.getMethod();
        if(method.equals("GET") || method.equals("HEAD")) { chain.doFilter(request,response);return; }
        try {
            var origins=Collections.list(request.getHeaders("Origin"));
            if(origins.size()!=1 || !origins.get(0).equals(origin)) { Problems.reject(response,Problems.Code.ORIGIN_REJECTED);return; }
            var site=Collections.list(request.getHeaders("Sec-Fetch-Site"));
            if(site.size()>1 || site.size()==1 && !site.get(0).equals("same-origin")) { Problems.reject(response,Problems.Code.CROSS_SITE_REJECTED);return; }
            if(method.equals("POST") && request.getRequestURI().equals(BOOTSTRAP)) { chain.doFilter(request,response);return; }
            char[] cookie=cookies.read(request);
            var session=cookie==null?Optional.<com.purpuragenda.application.port.out.SessionStore.Snapshot>empty():access.current(cookie);
            if(session.isEmpty()) { Problems.reject(response,Problems.Code.SESSION_REQUIRED);return; }
            var tokens=Collections.list(request.getHeaders("X-CSRF-Token"));
            if(tokens.size()!=1 || !CSRF.matcher(tokens.get(0)).matches() || !access.csrf(session.get(),tokens.get(0).toCharArray())) {
                Problems.reject(response,Problems.Code.CSRF_REJECTED);return;
            }
            request.setAttribute(SESSION_ATTRIBUTE,session.get());
        } catch(IllegalStateException unavailable) { Problems.reject(response,Problems.Code.SESSION_UNAVAILABLE);return; }
        chain.doFilter(request,response);
    }
}
