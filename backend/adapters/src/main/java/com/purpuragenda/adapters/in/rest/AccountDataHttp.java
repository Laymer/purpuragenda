package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import com.purpuragenda.application.port.out.SessionStore.Snapshot;
import com.purpuragenda.domain.DomainFailure;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class AccountDataHttp {
    static final JsonMapper JSON=JsonMapper.builder().build();
    private AccountDataHttp() { }
    static SessionBinding binding(Snapshot session) { return new SessionBinding(session.reference().id(),session.reference().browserHash()); }
    static void output(HttpServletResponse response,ObjectNode body,Long version) throws IOException {
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);response.setStatus(200);response.setHeader("Cache-Control","no-store");
        if(version!=null)response.setHeader("ETag",'"'+Long.toString(version)+'"');
        response.setContentType("application/json");response.setCharacterEncoding("UTF-8");response.setContentLength(bytes.length);response.getOutputStream().write(bytes);
    }
    static void failed(HttpServletResponse response,Exception failure) throws IOException {
        if(failure instanceof Failure value) {
            if(value.kind()==Failure.Kind.AUTHENTICATION_REQUIRED)BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);
            else if(value.kind()==Failure.Kind.FORBIDDEN)BookingHttp.problem(response,403,"ACCOUNT_ACCESS_REQUIRED",false,false);
            else BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false);
        } else if(failure instanceof BookingHttp.Invalid || failure instanceof DomainFailure || failure instanceof IllegalArgumentException || failure instanceof java.time.DateTimeException || failure instanceof ArithmeticException)
            BookingHttp.problem(response,400,"INVALID_REQUEST",false,false);
        else BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false);
    }
}
