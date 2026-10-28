package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.purpuragenda.application.port.out.AdministratorHistoryQuery.*;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

final class AdministratorHistoryHttp {
    static final JsonMapper JSON=JsonMapper.builder().build();
    private AdministratorHistoryHttp() { }
    static Request parameters(HttpServletRequest request,boolean audit) throws BookingHttp.Invalid {
        var values=request.getParameterMap();if(!(audit?Set.of("bookingId","cursor","limit"):Set.of("cursor","limit")).containsAll(values.keySet()))throw new BookingHttp.Invalid();
        for(String[] value:values.values())if(value==null || value.length!=1 || value[0]==null || value[0].isEmpty() || value[0].length()>200)throw new BookingHttp.Invalid();
        String limit=request.getParameter("limit"),cursor=request.getParameter("cursor");
        if(limit!=null && !limit.matches("[1-9][0-9]{0,2}"))throw new BookingHttp.Invalid();
        return new Request(cursor==null?null:cursor(cursor),limit==null?50:Integer.parseInt(limit));
    }
    static String cursor(Cursor cursor) {return Base64.getUrlEncoder().withoutPadding().encodeToString((cursor.time()+"|"+cursor.id()).getBytes(StandardCharsets.US_ASCII));}
    static Cursor cursor(String value) throws BookingHttp.Invalid {
        if(value.length()>200 || !value.matches("[A-Za-z0-9_-]+"))throw new BookingHttp.Invalid();
        try {
            String[] fields=new String(Base64.getUrlDecoder().decode(value),StandardCharsets.US_ASCII).split("\\|",-1);if(fields.length!=2)throw new BookingHttp.Invalid();
            var result=new Cursor(Instant.parse(fields[0]),BookingHttp.id(fields[1]));if(!cursor(result).equals(value))throw new BookingHttp.Invalid();return result;
        } catch(IllegalArgumentException | DateTimeException invalid) {throw new BookingHttp.Invalid();}
    }
    static String notes(NotePage page) {
        ObjectNode body=page(page.next(),page.observedAt());var entries=body.putArray("entries");
        for(var note:page.entries())entries.addObject().put("id",note.id().toString()).put("text",note.text()).put("createdAt",note.createdAt().toString());return body.toString();
    }
    static String audit(AuditPage page) {
        ObjectNode body=page(page.next(),page.observedAt());var entries=body.putArray("entries");
        for(var event:page.entries())entries.addObject().put("id",event.id().toString()).put("bookingId",event.bookingId()==null?null:event.bookingId().toString())
                .put("actorKind",event.actorKind()).put("type",event.type()).put("reason",event.reason()).put("occurredAt",event.occurredAt().toString()).put("version",event.version());return body.toString();
    }
    private static ObjectNode page(Cursor next,Instant now) {return JSON.createObjectNode().put("nextCursor",next==null?null:cursor(next)).put("serverNow",now.toString());}
    static void json(HttpServletResponse response,int status,String body) throws IOException {
        response.setStatus(status);response.setHeader("Cache-Control","no-store");response.setContentType("application/json");response.setCharacterEncoding("UTF-8");response.getWriter().write(body);
    }
    static void failure(HttpServletResponse response,Failure failure) throws IOException {
        if(failure.kind()==Failure.Kind.FORBIDDEN)BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);else BookingHttp.failure(response,failure);
    }
}
