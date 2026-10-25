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
        SESSION_UNAVAILABLE(503,"Session service unavailable",true);
        final int status;final String title;final boolean retryable;
        Code(int status,String title,boolean retryable) { this.status=status;this.title=title;this.retryable=retryable; }
    }
    private Problems() { }
    static void send(HttpServletResponse response,Code code) throws IOException {
        byte[] body=("{\"type\":\"about:blank\",\"title\":\""+code.title+"\",\"status\":"+code.status+",\"code\":\""+code.name()
                +"\",\"correlationId\":\""+UUID.randomUUID()+"\",\"retryable\":"+code.retryable+",\"refreshAvailability\":false}")
                .getBytes(StandardCharsets.UTF_8);
        response.reset();
        response.setStatus(code.status);
        response.setHeader("Cache-Control","no-store");
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
