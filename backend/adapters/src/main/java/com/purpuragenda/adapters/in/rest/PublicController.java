package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.RequestThrottle;
import com.purpuragenda.application.scheduling.PublicScheduling;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.ServiceDefinition;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * Anonymous catalog and advisory availability. Wire documents are written field by field to match the OpenAPI
 * contract; application records are never serialized directly. Availability is never cacheable because the booking
 * write path rechecks everything authoritatively. No personal data is read or returned.
 */
@RestController
@RequestMapping("/api/v1")
public final class PublicController {
    static final int MAX_DAYS = 31;
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DATE_TEXT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private final PublicScheduling scheduling;
    private final RequestThrottle throttle;

    public PublicController(PublicScheduling scheduling, RequestThrottle throttle) {
        this.scheduling = Objects.requireNonNull(scheduling);
        this.throttle = Objects.requireNonNull(throttle);
    }

    @GetMapping("/services")
    public ResponseEntity<String> services(HttpServletRequest request, HttpServletResponse response) throws IOException {
        List<ServiceDefinition> services;
        try { services = scheduling.services(); }
        catch (RuntimeException unavailable) { Problems.reject(response, Problems.Code.TEMPORARILY_UNAVAILABLE); return null; }
        var body = new StringBuilder("[");
        for (int i = 0; i < services.size(); i++) {
            var s = services.get(i);
            if (i > 0) body.append(',');
            body.append("{\"id\":\"").append(s.id()).append("\",\"version\":").append(s.version())
                .append(",\"name\":").append(string(s.name())).append(",\"durationMinutes\":").append(s.durationMinutes())
                .append(",\"priceMinor\":").append(s.price().minor()).append(",\"currency\":\"").append(s.price().currency())
                .append("\",\"active\":").append(s.active()).append('}');
        }
        String json = body.append(']').toString();
        String etag = '"' + digest(json) + '"';
        // Revalidate every time: the catalog changes rarely but a stale price must never survive a reload.
        var headers = new HttpHeaders();
        headers.setETag(etag);
        headers.set(HttpHeaders.CACHE_CONTROL, "no-cache");
        if (matches(request.getHeaders("If-None-Match"), etag)) return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(headers).build();
        return ResponseEntity.ok().headers(headers).contentType(MediaType.APPLICATION_JSON).body(json);
    }

    @GetMapping("/availability")
    public ResponseEntity<String> availability(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Throttle first so malformed floods are charged too; storage failure fails closed.
        RequestThrottle.Admission admission;
        try { admission = throttle.availability(client(request)); }
        catch (RuntimeException unavailable) { Problems.reject(response, Problems.Code.TEMPORARILY_UNAVAILABLE); return null; }
        if (!admission.allowed()) { Problems.reject(response, Problems.Code.RATE_LIMITED, admission.retryAfterSeconds()); return null; }
        UUID service; LocalDate from, to; String zone;
        try {
            service = uuid(single(request, "serviceId"));
            from = date(single(request, "from"));
            to = date(single(request, "to"));
            zone = single(request, "displayZone");
        } catch (IllegalArgumentException invalid) { Problems.reject(response, Problems.Code.INVALID_REQUEST); return null; }
        if (to.isBefore(from)) { Problems.reject(response, Problems.Code.INVALID_DATE_RANGE); return null; }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) { Problems.reject(response, Problems.Code.RANGE_TOO_WIDE); return null; }
        PublicScheduling.Availability result;
        try { result = scheduling.availability(service, from, to, zone); }
        catch (DomainFailure failure) {
            Problems.reject(response, switch (failure.code()) {
                case "INVALID_ZONE" -> Problems.Code.INVALID_ZONE;
                case "SERVICE_UNAVAILABLE" -> Problems.Code.SERVICE_UNAVAILABLE;
                case "INVALID_DATE_RANGE" -> Problems.Code.INVALID_DATE_RANGE;
                default -> Problems.Code.INVALID_REQUEST;
            });
            return null;
        } catch (RuntimeException unavailable) { Problems.reject(response, Problems.Code.TEMPORARILY_UNAVAILABLE); return null; }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").contentType(MediaType.APPLICATION_JSON).body(availability(result));
    }

    static String availability(PublicScheduling.Availability a) {
        var body = new StringBuilder("{\"serverNow\":\"").append(a.serverNow()).append("\",\"businessZone\":").append(string(a.businessZone()))
            .append(",\"displayZone\":").append(string(a.displayZone())).append(",\"horizonInstant\":\"").append(a.horizon())
            .append("\",\"policyVersion\":").append(a.policyVersion()).append(",\"slots\":[");
        for (int i = 0; i < a.slots().size(); i++) {
            var s = a.slots().get(i);
            if (i > 0) body.append(',');
            body.append("{\"startInstant\":\"").append(s.start()).append("\",\"sessionEndInstant\":\"").append(s.sessionEnd())
                .append("\",\"reservedEndInstant\":\"").append(s.reservationEnd()).append("\",\"businessDate\":\"").append(s.businessDate())
                .append("\",\"displayLocalTime\":").append(string(s.displayLocal())).append(",\"offset\":").append(string(s.displayOffset())).append('}');
        }
        body.append("],\"restrictions\":[");
        for (int i = 0; i < a.restrictions().size(); i++) { if (i > 0) body.append(','); body.append(string(a.restrictions().get(i))); }
        return body.append("]}").toString();
    }

    private static String client(HttpServletRequest request) {
        Object value = request.getAttribute(ApiGuardFilter.CLIENT_ATTRIBUTE);
        return value instanceof String text ? text : "0.0.0.0";
    }
    private static String single(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null || values.length != 1 || values[0].isEmpty() || values[0].length() > 64) throw new IllegalArgumentException();
        return values[0];
    }
    private static UUID uuid(String text) {
        if (!UUID_TEXT.matcher(text).matches()) throw new IllegalArgumentException();
        return UUID.fromString(text);
    }
    private static LocalDate date(String text) {
        if (!DATE_TEXT.matcher(text).matches()) throw new IllegalArgumentException();
        try { return LocalDate.parse(text); } catch (DateTimeException invalid) { throw new IllegalArgumentException(); }
    }
    private static boolean matches(Enumeration<String> header, String etag) {
        for (String line : Collections.list(header))
            for (String candidate : line.split(","))
                if (candidate.strip().equals("*") || candidate.strip().replaceFirst("^W/", "").equals(etag)) return true;
        return false;
    }
    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)), 0, 16); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** JSON string escaping for every control character, quote and backslash. */
    static String string(String value) {
        var out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> { if (c < 0x20 || c == 0x7f) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }
}
