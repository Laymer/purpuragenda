package com.purpuragenda.adapters.in.rest;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Safe private problem replies: fixed codes and a random correlation id, never exception text. */
final class Problems {
    enum Code {
        ORIGIN_REJECTED(403,"Request origin rejected",false),CROSS_SITE_REJECTED(403,"Cross-site request rejected",false),
        SESSION_REQUIRED(401,"A current session is required",false),CSRF_REJECTED(403,"CSRF proof rejected",false),
        SESSION_UNAVAILABLE(503,"Session service unavailable",true),
        INVALID_REQUEST(400,"Request parameters are invalid",false),INVALID_ZONE(400,"Display zone is invalid",false),
        INVALID_DATE_RANGE(400,"Date range is invalid",false),RANGE_TOO_WIDE(400,"Date range is too wide",false),
        SERVICE_UNAVAILABLE(404,"Service is not available",false),RATE_LIMITED(429,"Too many requests",true),
        TEMPORARILY_UNAVAILABLE(503,"Service temporarily unavailable",true);
        final int status;final String title;final boolean retryable;
        Code(int status,String title,boolean retryable) { this.status=status;this.title=title;this.retryable=retryable; }
    }
    private Problems() { }
    static void reject(HttpServletResponse response,Code code) throws IOException { reject(response,code,0); }
    /** A positive retryAfter adds the whole-second Retry-After header. */
    static void reject(HttpServletResponse response,Code code,long retryAfter) throws IOException {
        byte[] body=("{\"type\":\"about:blank\",\"title\":\""+code.title+"\",\"status\":"+code.status+",\"code\":\""+code.name()
                +"\",\"correlationId\":\""+UUID.randomUUID()+"\",\"retryable\":"+code.retryable+",\"refreshAvailability\":false}")
                .getBytes(StandardCharsets.UTF_8);
        response.reset();
        response.setStatus(code.status);
        response.setHeader("Cache-Control","no-store");
        // A 429 must always tell the client when to return; never send zero or a negative wait.
        long wait=code.status==429?Math.max(1,retryAfter):retryAfter;
        if(wait>0) response.setHeader("Retry-After",Long.toString(wait));
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
