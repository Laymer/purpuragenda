package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.RequestThrottle;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Objects;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Charges every availability request, including malformed ones, to the persisted per-client bucket before any
 * handler runs. It lives in a filter so the GET handler itself stays free of storage writes. Storage failure
 * fails closed with a retryable problem. Run after {@link ApiGuardFilter}, which resolves the client address.
 */
public final class AvailabilityThrottleFilter extends OncePerRequestFilter {
    public static final String PATH = "/api/v1/availability";
    private final RequestThrottle throttle;

    public AvailabilityThrottleFilter(RequestThrottle throttle) { this.throttle = Objects.requireNonNull(throttle); }

    /** Registered for every path; the decision uses the normalized path and fails closed on undecodable input. */
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = RequestPaths.normalize(request.getRequestURI());
        return path != null && !path.equals(PATH);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        RequestThrottle.Admission admission;
        try {
            Object client = request.getAttribute(ApiGuardFilter.CLIENT_ATTRIBUTE);
            admission = throttle.availability(client instanceof String text ? text : "0.0.0.0");
        } catch (RuntimeException unavailable) { Problems.reject(response, Problems.Code.TEMPORARILY_UNAVAILABLE); return; }
        if (!admission.allowed()) { Problems.reject(response, Problems.Code.RATE_LIMITED, admission.retryAfterSeconds()); return; }
        chain.doFilter(request, response);
    }
}
