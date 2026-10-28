package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.port.out.SessionStore.*;
import com.purpuragenda.application.scheduling.AdminBooking;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.customer.Intake;
import com.purpuragenda.domain.scheduling.SchedulingRules;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.DateTimeException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * Manual administrator creation, kept apart from the private reads. A meeting URL confirms immediately; otherwise the
 * booking is pending. Overrides name explicit violated rules with a reason; reservation overlap is never overridable.
 */
@RestController
@RequestMapping("/api/v1/admin/bookings")
public final class AdministratorBookingCommandController {
    private final AdminBooking bookings;
    public AdministratorBookingCommandController(AdminBooking bookings) { this.bookings=Objects.requireNonNull(bookings); }
    @PostMapping
    public ResponseEntity<String> create(HttpServletRequest request,HttpServletResponse response) throws IOException {
        if(!(request.getAttribute(ApiGuardFilter.SESSION_ATTRIBUTE) instanceof Snapshot session)) { BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return null; }
        if(session.kind()!=Kind.ADMIN) { BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);return null; }
        try {
            if(request.getHeader("If-Match")!=null)throw new BookingHttp.Invalid();
            var body=BookingHttp.body(request,"serviceId","serviceVersion","policyVersion","calendarVersion","startInstant","customerEmail",
                    "displayZone","noticeVersion","intake","meetingUrl","override");
            var reference=session.reference();
            var receipt=bookings.create(new SessionBinding(reference.id(),reference.browserHash()),BookingHttp.header(request,"Idempotency-Key"),
                    new AdminBooking.Request(BookingHttp.id(BookingHttp.text(body,"serviceId")),BookingHttp.number(body,"serviceVersion"),
                            BookingHttp.number(body,"policyVersion"),BookingHttp.number(body,"calendarVersion"),BookingHttp.start(body),
                            BookingHttp.text(body,"customerEmail"),intake(body.get("intake")),BookingHttp.text(body,"displayZone"),
                            BookingHttp.text(body,"noticeVersion"),optional(body,"meetingUrl"),override(body.get("override"))));
            if(!receipt.success()) { BookingHttp.business(response,receipt.code());return null; }
            return ResponseEntity.status(201).header(HttpHeaders.CACHE_CONTROL,"no-store").eTag('"'+Long.toString(receipt.version())+'"')
                    .header(HttpHeaders.LOCATION,"/api/v1/admin/bookings/"+receipt.entityId()).contentType(MediaType.APPLICATION_JSON).body(BookingHttp.receipt(receipt));
        } catch(DomainFailure failure) {
            // A malformed participant URL is a caller error reported by its own code, as for confirmations.
            BookingHttp.problem(response,400,"INVALID_MEETING".equals(failure.code())?"INVALID_MEETING":"INVALID_REQUEST",false,false);
        } catch(BookingHttp.Invalid | IllegalArgumentException | DateTimeException | ArithmeticException invalid) { BookingHttp.problem(response,400,"INVALID_REQUEST",false,false); }
        catch(Failure failure) {
            switch(failure.kind()) {
                case AUTHENTICATION_REQUIRED -> BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);
                case FRESH_AUTHENTICATION_REQUIRED -> BookingHttp.problem(response,403,"AUTHENTICATION_REQUIRED",false,false);
                case FORBIDDEN -> BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);
                default -> BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false);
            }
        } catch(RuntimeException unavailable) { BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false); }
        return null;
    }
    private static Intake intake(JsonNode node) throws BookingHttp.Invalid {
        var intake=BookingHttp.object(node,"firstName","lastName","telephone","birthDate","birthTime","birthplace","message");
        return new Intake(BookingHttp.text(intake,"firstName"),BookingHttp.text(intake,"lastName"),BookingHttp.text(intake,"telephone"),
                BookingHttp.birthDate(intake),BookingHttp.birthTime(intake),BookingHttp.text(intake,"birthplace"),BookingHttp.text(intake,"message"));
    }
    private static String optional(JsonNode node,String field) throws BookingHttp.Invalid { return node.get(field).isNull()?null:BookingHttp.text(node,field); }
    /** No rules means no override and a null reason; otherwise a reason and only blackout ids the HARD_BLACKOUT rule covers. */
    static SchedulingRules.Override override(JsonNode node) throws BookingHttp.Invalid {
        BookingHttp.object(node,"rules","reason","blackoutIds");
        JsonNode rules=node.get("rules"),blackouts=node.get("blackoutIds");
        if(!rules.isArray() || !blackouts.isArray())throw new BookingHttp.Invalid();
        Set<SchedulingRules.Rule> named=EnumSet.noneOf(SchedulingRules.Rule.class);
        for(JsonNode rule:rules) {
            if(!rule.isTextual() || Arrays.stream(SchedulingRules.Rule.values()).noneMatch(r -> r.name().equals(rule.textValue())))throw new BookingHttp.Invalid();
            if(!named.add(SchedulingRules.Rule.valueOf(rule.textValue())))throw new BookingHttp.Invalid();
        }
        Set<UUID> ids=new HashSet<>();
        for(JsonNode id:blackouts)if(!id.isTextual() || !ids.add(BookingHttp.id(id.textValue())))throw new BookingHttp.Invalid();
        if(named.isEmpty()) {
            if(!node.get("reason").isNull() || !ids.isEmpty())throw new BookingHttp.Invalid();
            return SchedulingRules.Override.none();
        }
        return new SchedulingRules.Override(named,BookingHttp.text(node,"reason"),ids);
    }
}
