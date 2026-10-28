package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.port.out.SessionStore.*;
import com.purpuragenda.application.scheduling.AdministratorConfiguration;
import com.purpuragenda.application.scheduling.AdministratorConfiguration.*;
import com.purpuragenda.application.scheduling.AdministratorConfigurationView;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.scheduling.*;
import com.purpuragenda.domain.scheduling.AvailabilityCalendar.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * Private schedule and policy boundary. Reads and previews recheck persisted authority in their snapshot; commands require
 * idempotency, the reviewed version and fresh MFA under the resource lock. Existing bookings are never moved or invalidated.
 */
@RestController
@RequestMapping("/api/v1/admin/configuration")
public final class AdministratorConfigurationController {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final AdministratorConfiguration configuration;private final AdministratorConfigurationView view;
    private final SessionAccess access;private final SessionCookies cookies;
    public AdministratorConfigurationController(AdministratorConfiguration configuration,AdministratorConfigurationView view,SessionAccess access,SessionCookies cookies) {
        this.configuration=Objects.requireNonNull(configuration);this.view=Objects.requireNonNull(view);
        this.access=Objects.requireNonNull(access);this.cookies=Objects.requireNonNull(cookies);
    }
    @GetMapping
    public ResponseEntity<String> current(HttpServletRequest request,HttpServletResponse response) throws IOException {
        if(!request.getParameterMap().isEmpty()) { BookingHttp.problem(response,400,"INVALID_REQUEST",false,false);return null; }
        // Its own try: sharing a callback with the command path would make this read look state-changing to analysis.
        try {
            var session=access.inspect(cookies.read(request));
            if(session.isEmpty()) { BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return null; }
            if(session.get().kind()!=Kind.ADMIN) { BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);return null; }
            var current=view.current(binding(session.get()));
            ObjectNode body=JSON.createObjectNode().put("observedAt",current.observedAt().toString())
                    .put("calendarVersion",current.calendar().version()).put("policyVersion",current.policy().version());
            calendar(body,current.calendar());policy(body.putObject("policy"),current.policy());
            var services=body.putArray("services");
            current.services().stream().sorted(Comparator.comparing(s->s.id().toString())).forEach(s->services.addObject()
                    .put("id",s.id().toString()).put("version",s.version()).put("name",s.name()).put("durationMinutes",s.durationMinutes())
                    .put("priceMinor",s.price().minor()).put("currency",s.price().currency()).put("active",s.active()));
            return ok(body,null);
        } catch(RuntimeException failure) { failed(response,failure);return null; }
    }
    @PostMapping("/conflicts")
    public ResponseEntity<String> conflicts(HttpServletRequest request,HttpServletResponse response) throws IOException {
        return guarded(request,response,false,session->{
            var body=BookingHttp.body(request,"proposal");JsonNode proposal=body.get("proposal");
            AdministratorConfigurationView.Preview preview;
            if(proposal!=null && proposal.has("weekly"))
                preview=view.calendarConflicts(session,weekly(BookingHttp.object(proposal,"weekly","dateExceptions","blackouts").get("weekly")),
                        exceptions(proposal.get("dateExceptions")),blackouts(proposal.get("blackouts")));
            else preview=view.policyConflicts(session,values(proposal));
            ObjectNode result=JSON.createObjectNode().put("assessedAt",preview.assessedAt().toString())
                    .put("calendarVersion",preview.calendarVersion()).put("policyVersion",preview.policyVersion());
            var items=result.putArray("conflicts");
            for(var conflict:preview.conflicts()) {
                var item=items.addObject().put("bookingId",conflict.bookingId().toString()).put("reference",conflict.reference())
                        .put("startInstant",conflict.start().toString()).put("state",conflict.state().name());
                var reasons=item.putArray("reasons");conflict.reasons().stream().map(Enum::name).sorted().forEach(reasons::add);
                var ids=item.putArray("blackoutIds");conflict.blackoutIds().stream().map(UUID::toString).sorted().forEach(ids::add);
            }
            return ok(result,null);
        });
    }
    @PostMapping("/weekly")
    public ResponseEntity<String> weekly(HttpServletRequest request,HttpServletResponse response) throws IOException {
        return command(request,response,(session,key,version,body)->configuration.replaceWeekly(session,key,version,weekly(body.get("weekly")),review(body)),"weekly");
    }
    @PostMapping("/date-exceptions")
    public ResponseEntity<String> setException(HttpServletRequest request,HttpServletResponse response) throws IOException {
        return command(request,response,(session,key,version,body)->{
            var exception=exception(BookingHttp.object(body.get("dateException"),"date","mode","windows"));
            return configuration.setDateException(session,key,version,date(body.get("dateException")),exception,review(body));
        },"dateException");
    }
    @PostMapping("/date-exceptions/{date}/removal")
    public ResponseEntity<String> removeException(@PathVariable String date,HttpServletRequest request,HttpServletResponse response) throws IOException {
        return command(request,response,(session,key,version,body)->configuration.removeDateException(session,key,version,localDate(date),review(body)));
    }
    @PostMapping("/blackouts")
    public ResponseEntity<String> addBlackout(HttpServletRequest request,HttpServletResponse response) throws IOException {
        return command(request,response,(session,key,version,body)->configuration.addBlackout(session,key,version,blackout(body.get("blackout")),review(body)),"blackout");
    }
    @PostMapping("/blackouts/{blackoutId}/removal")
    public ResponseEntity<String> removeBlackout(@PathVariable String blackoutId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        return command(request,response,(session,key,version,body)->configuration.removeBlackout(session,key,version,BookingHttp.id(blackoutId),review(body)));
    }
    @PostMapping("/policy")
    public ResponseEntity<String> policy(HttpServletRequest request,HttpServletResponse response) throws IOException {
        return command(request,response,(session,key,version,body)->configuration.updatePolicy(session,key,version,values(body.get("policy")),review(body)),"policy");
    }

    private interface Guarded { ResponseEntity<String> run(SessionBinding session) throws BookingHttp.Invalid,IOException; }
    private interface Command { Receipt run(SessionBinding session,String key,long version,JsonNode body) throws BookingHttp.Invalid; }
    /** Every command body carries the review fields and, except removals, exactly one named change. */
    private ResponseEntity<String> command(HttpServletRequest request,HttpServletResponse response,Command work,String... change) throws IOException {
        return guarded(request,response,true,session->{
            List<String> fields=new ArrayList<>(List.of("reason","acknowledgedBookingIds"));fields.addAll(List.of(change));
            var body=BookingHttp.body(request,fields.toArray(String[]::new));
            var receipt=work.run(session,BookingHttp.header(request,"Idempotency-Key"),BookingHttp.version(request),body);
            if(!receipt.success()) {
                int status=Set.of(Code.CALENDAR_CHANGED,Code.POLICY_CHANGED).contains(receipt.code())?412:receipt.code()==Code.INVALID_CONFIGURATION?400:409;
                BookingHttp.problem(response,status,receipt.code().name(),false,false);return null;
            }
            String name=receipt.entityId().equals(AdministratorConfiguration.POLICY_ID)?"policyVersion":"calendarVersion";
            return ok(JSON.createObjectNode().put(name,receipt.version()),receipt.version());
        });
    }
    private ResponseEntity<String> guarded(HttpServletRequest request,HttpServletResponse response,boolean versioned,Guarded work) throws IOException {
        if(!(request.getAttribute(ApiGuardFilter.SESSION_ATTRIBUTE) instanceof Snapshot session)) { BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return null; }
        if(session.kind()!=Kind.ADMIN) { BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);return null; }
        if(versioned && request.getHeader("If-Match")==null) { BookingHttp.problem(response,428,"VERSION_REQUIRED",false,false);return null; }
        try {
            if(!versioned && request.getHeader("If-Match")!=null)throw new BookingHttp.Invalid();
            return work.run(binding(session));
        } catch(BookingHttp.Invalid invalid) { BookingHttp.problem(response,400,"INVALID_REQUEST",false,false); }
        catch(RuntimeException failure) { failed(response,failure); }
        return null;
    }
    private static void failed(HttpServletResponse response,RuntimeException failure) throws IOException {
        if(failure instanceof DomainFailure || failure instanceof IllegalArgumentException || failure instanceof DateTimeException || failure instanceof ArithmeticException)
            BookingHttp.problem(response,400,"INVALID_REQUEST",false,false);
        else if(failure instanceof Failure denial) {
            switch(denial.kind()) {
                case AUTHENTICATION_REQUIRED -> BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);
                case FRESH_AUTHENTICATION_REQUIRED -> BookingHttp.problem(response,403,"AUTHENTICATION_REQUIRED",false,false);
                case FORBIDDEN -> BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);
                default -> BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false);
            }
        } else BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false);
    }
    private static SessionBinding binding(Snapshot session) { var reference=session.reference();return new SessionBinding(reference.id(),reference.browserHash()); }
    private static ResponseEntity<String> ok(ObjectNode body,Long version) {
        var result=ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.APPLICATION_JSON);
        if(version!=null)result.eTag('"'+version.toString()+'"');return result.body(body.toString());
    }

    private static Review review(JsonNode body) throws BookingHttp.Invalid {
        JsonNode ids=body.get("acknowledgedBookingIds");if(!ids.isArray())throw new BookingHttp.Invalid();
        Set<UUID> acknowledged=new HashSet<>();
        for(JsonNode id:ids)if(!id.isTextual() || !acknowledged.add(BookingHttp.id(id.textValue())))throw new BookingHttp.Invalid();
        return new Review(BookingHttp.text(body,"reason"),acknowledged);
    }
    private static Map<DayOfWeek,List<Window>> weekly(JsonNode node) throws BookingHttp.Invalid {
        // All seven days are explicit; an empty list closes the day.
        var days=Arrays.stream(DayOfWeek.values()).map(Enum::name).toArray(String[]::new);BookingHttp.object(node,days);
        Map<DayOfWeek,List<Window>> weekly=new EnumMap<>(DayOfWeek.class);
        for(DayOfWeek day:DayOfWeek.values())weekly.put(day,windows(node.get(day.name())));
        return weekly;
    }
    private static List<Window> windows(JsonNode node) throws BookingHttp.Invalid {
        if(node==null || !node.isArray() || node.size()>24)throw new BookingHttp.Invalid();
        List<Window> windows=new ArrayList<>();
        for(JsonNode window:node) { BookingHttp.object(window,"start","end");windows.add(new Window(minute(window,"start",false),minute(window,"end",true))); }
        return windows;
    }
    private static int minute(JsonNode node,String field,boolean end) throws BookingHttp.Invalid {
        String value=BookingHttp.text(node,field);
        if(end && value.equals("24:00"))return 1440;
        if(!value.matches("([01][0-9]|2[0-3]):[0-5][0-9]"))throw new BookingHttp.Invalid();
        return Integer.parseInt(value.substring(0,2))*60+Integer.parseInt(value.substring(3));
    }
    private static Map<LocalDate,DateException> exceptions(JsonNode node) throws BookingHttp.Invalid {
        if(node==null || !node.isArray() || node.size()>400)throw new BookingHttp.Invalid();
        Map<LocalDate,DateException> exceptions=new HashMap<>();
        for(JsonNode item:node) { BookingHttp.object(item,"date","mode","windows");if(exceptions.put(date(item),exception(item))!=null)throw new BookingHttp.Invalid(); }
        return exceptions;
    }
    private static DateException exception(JsonNode node) throws BookingHttp.Invalid {
        String mode=BookingHttp.text(node,"mode");
        if(!Set.of("REPLACE","ADD","CLOSED").contains(mode))throw new BookingHttp.Invalid();
        return new DateException(Mode.valueOf(mode),windows(node.get("windows")));
    }
    private static LocalDate date(JsonNode node) throws BookingHttp.Invalid {
        if(node==null)throw new BookingHttp.Invalid();return localDate(BookingHttp.text(node,"date"));
    }
    private static LocalDate localDate(String value) throws BookingHttp.Invalid {
        if(value==null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new BookingHttp.Invalid();return LocalDate.parse(value);
    }
    private static List<Blackout> blackouts(JsonNode node) throws BookingHttp.Invalid {
        if(node==null || !node.isArray() || node.size()>400)throw new BookingHttp.Invalid();
        List<Blackout> blackouts=new ArrayList<>();Set<UUID> ids=new HashSet<>();
        for(JsonNode item:node) { var blackout=blackout(item);if(!ids.add(blackout.id()))throw new BookingHttp.Invalid();blackouts.add(blackout); }
        return blackouts;
    }
    private static Blackout blackout(JsonNode node) throws BookingHttp.Invalid {
        BookingHttp.object(node,"id","startInstant","endInstant","reason");
        var interval=new ReservationInterval(instant(node,"startInstant"),instant(node,"endInstant"));
        return new Blackout(BookingHttp.id(BookingHttp.text(node,"id")),interval,BookingHttp.text(node,"reason"));
    }
    private static Instant instant(JsonNode node,String field) throws BookingHttp.Invalid { return OffsetDateTime.parse(BookingHttp.text(node,field)).toInstant(); }
    private static PolicyValues values(JsonNode node) throws BookingHttp.Invalid {
        BookingHttp.object(node,"gridMinutes","dailyCapacity","minimumNotice","pendingLifetime","expiryBuffer","customerCutoff");
        return new PolicyValues(integer(node,"gridMinutes"),integer(node,"dailyCapacity"),duration(node,"minimumNotice"),
                duration(node,"pendingLifetime"),duration(node,"expiryBuffer"),duration(node,"customerCutoff"));
    }
    private static int integer(JsonNode node,String field) throws BookingHttp.Invalid {
        JsonNode value=node.get(field);if(!value.isIntegralNumber() || !value.canConvertToInt())throw new BookingHttp.Invalid();return value.intValue();
    }
    private static Duration duration(JsonNode node,String field) throws BookingHttp.Invalid {
        String value=BookingHttp.text(node,field);if(!value.matches("PT([0-9]{1,4}H)?([0-9]{1,6}M)?([0-9]{1,8}S)?") || value.equals("PT"))throw new BookingHttp.Invalid();
        return Duration.parse(value);
    }

    private static void calendar(ObjectNode body,AvailabilityCalendar calendar) {
        var weekly=body.putObject("weekly");
        for(DayOfWeek day:DayOfWeek.values())windows(weekly.putArray(day.name()),calendar.weekly().getOrDefault(day,List.of()));
        var exceptions=body.putArray("dateExceptions");
        new TreeMap<>(calendar.exceptions()).forEach((date,exception)->{
            var item=exceptions.addObject().put("date",date.toString()).put("mode",exception.mode().name());windows(item.putArray("windows"),exception.windows());
        });
        var blackouts=body.putArray("blackouts");
        calendar.blackouts().stream().sorted(Comparator.comparing((Blackout b)->b.interval().start()).thenComparing(Blackout::id)).forEach(b->blackouts.addObject()
                .put("id",b.id().toString()).put("startInstant",b.interval().start().toString()).put("endInstant",b.interval().end().toString()).put("reason",b.reason()));
    }
    private static void windows(com.fasterxml.jackson.databind.node.ArrayNode array,List<Window> windows) {
        for(Window w:windows)array.addObject().put("start",clock(w.startMinute())).put("end",clock(w.endMinute()));
    }
    private static String clock(int minute) { return String.format(Locale.ROOT,"%02d:%02d",minute/60,minute%60); }
    private static void policy(ObjectNode body,SchedulingPolicy policy) {
        body.put("gridMinutes",policy.gridMinutes()).put("dailyCapacity",policy.dailyCapacity()).put("minimumNotice",policy.minimumNotice().toString())
                .put("pendingLifetime",policy.pendingLifetime().toString()).put("expiryBuffer",policy.expiryBuffer().toString()).put("customerCutoff",policy.customerCutoff().toString());
    }
}
