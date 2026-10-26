package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.application.scheduling.PublicBookingContext;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.scheduling.SchedulingPolicy;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Anonymous review content, explicitly mapped and never cached. Not a capacity reservation. */
@RestController
@RequestMapping("/api/v1/booking-context")
public final class BookingContextController {
    private final PublicBookingContext query;
    public BookingContextController(PublicBookingContext query) { this.query=Objects.requireNonNull(query); }
    @GetMapping
    public ResponseEntity<String> get(HttpServletRequest request,HttpServletResponse response) throws IOException {
        UUID id;
        try {
            var values=request.getParameterValues("serviceId");
            if(!request.getParameterMap().keySet().equals(Set.of("serviceId")) || values==null || values.length!=1)throw new IllegalArgumentException();
            id=UUID.fromString(values[0]);if(!id.toString().equalsIgnoreCase(values[0]))throw new IllegalArgumentException();
        } catch(IllegalArgumentException invalid) { Problems.reject(response,Problems.Code.INVALID_REQUEST);return null; }
        try {
            var result=query.find(id);var service=result.service();var policy=result.policy();
            var body=JsonMapper.builder().build().createObjectNode();
            body.put("serverNow",result.serverNow().toString());body.put("horizon",result.horizon().toString());
            body.put("businessZone",SchedulingPolicy.BUSINESS_ZONE.getId());body.put("reservationMinutes",120);
            var selected=body.putObject("service");selected.put("id",service.id().toString());selected.put("version",service.version());
            selected.put("name",service.name());selected.put("durationMinutes",service.durationMinutes());
            selected.put("active",service.active());selected.put("priceMinor",service.price().minor());selected.put("currency",service.price().currency());
            var rules=body.putObject("policy");rules.put("version",policy.version());rules.put("gridMinutes",policy.gridMinutes());rules.put("dailyCapacity",policy.dailyCapacity());
            rules.put("minimumNoticeSeconds",policy.minimumNotice().getSeconds());rules.put("pendingLifetimeSeconds",policy.pendingLifetime().getSeconds());
            rules.put("expiryBufferSeconds",policy.expiryBuffer().getSeconds());rules.put("customerCutoffSeconds",policy.customerCutoff().getSeconds());
            var notice=body.putObject("notice");notice.put("version",result.notice().version());notice.put("text",result.notice().text());notice.put("synthetic",result.notice().synthetic());
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.APPLICATION_JSON).body(body.toString());
        } catch(DomainFailure unavailable) { Problems.reject(response,Problems.Code.SERVICE_UNAVAILABLE); }
        catch(RuntimeException unavailable) { Problems.reject(response,Problems.Code.TEMPORARILY_UNAVAILABLE,1); }
        return null;
    }
}
