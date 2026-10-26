package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.CustomerBookingView;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.*;
import java.util.*;

/** Bounded strict request parsing and explicit customer wire documents; never serialize application records. */
final class BookingHttp {
    private static final ObjectMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private BookingHttp() { }
    static final class Invalid extends Exception { Invalid() { super("INVALID_REQUEST",null,false,false); } }
    static ObjectNode body(HttpServletRequest request,String... fields) throws Invalid {
        String type=request.getContentType();
        if(type==null || !(type.equalsIgnoreCase("application/json") || type.toLowerCase(Locale.ROOT).startsWith("application/json;")))throw new Invalid();
        try {
            byte[] bytes=request.getInputStream().readNBytes(16385);if(bytes.length>16384)throw new Invalid();
            return object(JSON.readTree(bytes),fields);
        } catch(IOException | RuntimeException malformed) { throw new Invalid(); }
    }
    static ObjectNode object(JsonNode node,String... fields) throws Invalid {
        if(node==null || !node.isObject() || node.size()!=fields.length)throw new Invalid();
        for(String field:fields)if(!node.has(field))throw new Invalid();
        return (ObjectNode)node;
    }
    static String text(JsonNode node,String field) throws Invalid {
        JsonNode value=node.get(field);if(value==null || !value.isTextual())throw new Invalid();return value.textValue();
    }
    static long number(JsonNode node,String field) throws Invalid {
        JsonNode value=node.get(field);if(value==null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<1)throw new Invalid();return value.longValue();
    }
    static LocalDate birthDate(JsonNode node) throws Invalid {
        String value=text(node,"birthDate");if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new Invalid();return LocalDate.parse(value);
    }
    static LocalTime birthTime(JsonNode node) throws Invalid {
        String value=text(node,"birthTime");if(!value.matches("[0-9]{2}:[0-9]{2}(:[0-9]{2})?"))throw new Invalid();return LocalTime.parse(value);
    }
    static Instant start(JsonNode node) throws Invalid { return OffsetDateTime.parse(text(node,"startInstant")).toInstant(); }
    static String header(HttpServletRequest request,String name) throws Invalid {
        var values=Collections.list(request.getHeaders(name));
        if(values.size()!=1 || values.getFirst().isBlank() || values.getFirst().length()>120
                || values.getFirst().codePoints().anyMatch(Character::isISOControl))throw new Invalid();return values.getFirst();
    }
    static UUID id(String value) throws Invalid {
        if(value==null || !value.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))throw new Invalid();
        return UUID.fromString(value);
    }
    static long version(HttpServletRequest request) throws Invalid {
        String value=header(request,"If-Match");if(!value.matches("\"[1-9][0-9]{0,18}\""))throw new Invalid();
        try { return Long.parseLong(value.substring(1,value.length()-1)); } catch(NumberFormatException invalid) { throw new Invalid(); }
    }
    static void problem(HttpServletResponse response,int status,String code,boolean retryable,boolean refresh) throws IOException {
        ObjectNode body=JSON.createObjectNode();body.put("type","about:blank").put("title","Booking request could not be completed")
                .put("status",status).put("code",code).put("correlationId",UUID.randomUUID().toString())
                .put("retryable",retryable).put("refreshAvailability",refresh);
        response.reset();response.setStatus(status);response.setHeader("Cache-Control","no-store");
        if(retryable)response.setHeader("Retry-After","1");response.setContentType("application/problem+json");response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body.toString());
    }
    static void business(HttpServletResponse response,Code code) throws IOException {
        int status=code==Code.STALE_VERSION?412:code==Code.INVALID_BIRTH_DATE?400:409;
        problem(response,status,code.name(),false,code!=Code.IDEMPOTENCY_KEY_REUSED);
    }
    static void failure(HttpServletResponse response,Failure failure) throws IOException {
        switch(failure.kind()) {
            case AUTHENTICATION_REQUIRED -> problem(response,401,"SESSION_REQUIRED",false,false);
            case FORBIDDEN,NOT_FOUND -> problem(response,404,"BOOKING_NOT_FOUND",false,false);
            case FRESH_AUTHENTICATION_REQUIRED -> problem(response,403,"AUTHENTICATION_REQUIRED",false,false);
            default -> problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false);
        }
    }
    static String receipt(Receipt receipt) {
        return JSON.createObjectNode().put("bookingId",receipt.entityId().toString()).put("version",receipt.version()).toString();
    }
    static String view(CustomerBookingView.View v) {
        ObjectNode body=JSON.createObjectNode();body.put("id",v.id().toString()).put("reference",v.reference()).put("version",v.version())
                .put("state",v.state().name()).put("pendingExpired",v.pendingExpired()).put("startInstant",v.start().toString())
                .put("sessionEndInstant",v.sessionEnd().toString()).put("displayZone",v.displayZone()).put("deliveryEmail",v.deliveryEmail())
                .put("pendingExpiresAt",v.pendingExpiresAt()==null?null:v.pendingExpiresAt().toString())
                .put("customerChangesUntil",v.customerChangesUntil().toString()).put("serverNow",v.observedAt().toString()).put("meetingUrl",v.meetingUrl());
        var service=body.putObject("service");service.put("name",v.service().name()).put("durationMinutes",v.service().durationMinutes())
                .put("priceMinor",v.service().price().minor()).put("currency",v.service().price().currency());
        var payment=body.putObject("payment");payment.put("status",v.payment().status().name()).put("receivedMinor",v.payment().receivedMinor())
                .put("refundedMinor",v.payment().refundedMinor()).put("remainingMinor",v.payment().remainingMinor());
        var due=payment.putObject("refundDueByCurrency");new TreeMap<>(v.payment().refundDueByCurrency()).forEach(due::put);
        var received=payment.putObject("receivedByCurrency");new TreeMap<>(v.payment().receivedByCurrency()).forEach(received::put);
        var actions=body.putArray("allowedActions");v.allowedActions().stream().sorted().forEach(action->actions.add(action.name()));
        return body.toString();
    }
}
