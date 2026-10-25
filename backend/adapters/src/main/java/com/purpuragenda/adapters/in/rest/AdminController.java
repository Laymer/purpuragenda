package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.usecase.*;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.scheduling.*;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.customer.Input;
import static com.purpuragenda.domain.DomainFailure.require;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import com.purpuragenda.adapters.in.rest.BookingController.IntakeBody;
import static com.purpuragenda.adapters.in.rest.BookingController.fingerprint;
import static com.purpuragenda.adapters.in.rest.BookingController.receipt;
import static com.purpuragenda.adapters.in.rest.BookingController.version;

@RestController @RequestMapping("/api/v1/admin")
public class AdminController {
    private final BookingService bookings;private final IdentityService identities;private final OperationsService operations;
    public AdminController(BookingService bookings,IdentityService identities,OperationsService operations){this.bookings=bookings;this.identities=identities;this.operations=operations;}
    public record Search(String text,String status,LocalDate from,LocalDate to){}
    public record AdminRequest(UUID serviceId,long serviceVersion,long policyVersion,Instant startInstant,String displayZone,IntakeBody intake,String privacyNoticeVersion,String email,boolean immediatelyConfirmed,String participantUrl,OverrideBody override){}
    public record OverrideBody(Set<SchedulingRules.Rule> rules,String reason,Set<UUID> affectedBlackoutIds){SchedulingRules.Override domain(){return new SchedulingRules.Override(rules==null?Set.of():rules,reason,affectedBlackoutIds==null?Set.of():affectedBlackoutIds);}}
    public record MeetingBody(String participantUrl){}
    public record Reason(String reason){}
    public record CancelBody(String initiator,String reason){}
    public record MoveBody(Instant startInstant,long policyVersion,OverrideBody override,String reason){}
    public record Outcome(String status,String reason){}
    public record Payment(long amountMinor,String currency,String method,String reference,Instant verifiedAt,boolean confirm,String participantUrl){}
    public record Refund(UUID receiptId,long amountMinor,String currency,String method,String reference,Instant verifiedAt){}
    public record ServiceBody(String name,int durationMinutes,long priceMinor,boolean active){}
    public record PolicyBody(int gridMinutes,int dailyCapacity,long minimumNoticeSeconds,long pendingLifetimeSeconds,long expiryBufferSeconds,long customerCutoffSeconds){}
    public record WindowBody(int startMinute,int endMinute){}
    public record ExceptionBody(AvailabilityCalendar.Mode mode,List<WindowBody> windows){}
    public record BlackoutBody(UUID id,Instant startInstant,Instant endInstant,String reason){}
    public record CalendarBody(Map<DayOfWeek,List<WindowBody>> weekly,Map<LocalDate,ExceptionBody> exceptions,List<BlackoutBody> blackouts){}
    public record SettingsBody(PolicyBody policy,CalendarBody calendar,String reason){}
    public record NoteBody(String note){}
    @PostMapping("/bookings/search") public Object search(@RequestBody Search query,HttpServletRequest request){return bookings.list(SessionAccess.actor(request)).stream().filter(v->{Booking b=v.booking();String text=Objects.requireNonNullElse(query.text(),"").toLowerCase(Locale.ROOT);return (query.status()==null||query.status().isBlank()||b.state().name().equals(query.status()))&&(query.from()==null||!b.businessDate().isBefore(query.from()))&&(query.to()==null||!b.businessDate().isAfter(query.to()))&&(b.reference().toLowerCase(Locale.ROOT).contains(text)||b.deliveryEmail().contains(text)||(b.snapshot().intake().firstName()+" "+b.snapshot().intake().lastName()).toLowerCase(Locale.ROOT).contains(text));}).limit(200).map(ApiViews::booking).toList();}
    @GetMapping("/bookings/{id}") public Object get(@PathVariable UUID id,HttpServletRequest request){return ApiViews.booking(bookings.get(SessionAccess.actor(request),id));}
    @PostMapping("/bookings") public ResponseEntity<?> create(@RequestBody AdminRequest body,@RequestHeader("Idempotency-Key")String key,HttpServletRequest request){
        var actor=SessionAccess.actor(request);UUID customer=identities.ensureCustomer(actor,body.email());
        return receipt(bookings.create(actor,key,fingerprint(body),new BookingService.Request(body.serviceId(),body.serviceVersion(),body.policyVersion(),body.startInstant(),body.displayZone(),body.intake().domain(),body.privacyNoticeVersion(),customer,body.immediatelyConfirmed(),parseMeeting(body.participantUrl()),body.override()==null?SchedulingRules.Override.none():body.override().domain())),true);
    }
    @PostMapping("/bookings/{id}/approvals") public ResponseEntity<?> approve(@PathVariable UUID id,@RequestBody MeetingBody body,@RequestHeader("Idempotency-Key")String key,@RequestHeader(value="If-Match",required=false)String version,HttpServletRequest request){return receipt(bookings.approve(SessionAccess.actor(request),id,version(version),key,fingerprint(body),parseMeeting(body.participantUrl())),false);}
    @PostMapping("/bookings/{id}/rejections") public ResponseEntity<?> reject(@PathVariable UUID id,@RequestBody Reason body,@RequestHeader("Idempotency-Key")String key,@RequestHeader(value="If-Match",required=false)String version,HttpServletRequest request){return receipt(bookings.reject(SessionAccess.actor(request),id,version(version),key,fingerprint(body),body.reason()),false);}
    @PostMapping("/bookings/{id}/cancellations") public ResponseEntity<?> cancel(@PathVariable UUID id,@RequestBody CancelBody body,@RequestHeader("Idempotency-Key")String key,@RequestHeader(value="If-Match",required=false)String version,HttpServletRequest request){return receipt(bookings.cancel(SessionAccess.actor(request),id,version(version),key,fingerprint(body),body.initiator(),body.reason()),false);}
    @PostMapping("/bookings/{id}/reschedules") public ResponseEntity<?> move(@PathVariable UUID id,@RequestBody MoveBody body,@RequestHeader("Idempotency-Key")String key,@RequestHeader(value="If-Match",required=false)String version,HttpServletRequest request){return receipt(bookings.reschedule(SessionAccess.actor(request),id,version(version),key,fingerprint(body),body.startInstant(),body.policyVersion(),body.override()==null?SchedulingRules.Override.none():body.override().domain(),body.reason()),false);}
    @PutMapping("/bookings/{id}/meeting") public ResponseEntity<?> meeting(@PathVariable UUID id,@RequestBody MeetingBody body,@RequestHeader("Idempotency-Key")String key,@RequestHeader(value="If-Match",required=false)String version,HttpServletRequest request){return receipt(bookings.meeting(SessionAccess.actor(request),id,version(version),key,fingerprint(body),parseMeeting(body.participantUrl())),false);}
    @PostMapping("/bookings/{id}/outcomes") public ResponseEntity<?> outcome(@PathVariable UUID id,@RequestBody Outcome body,@RequestHeader("Idempotency-Key")String key,@RequestHeader(value="If-Match",required=false)String version,HttpServletRequest request){return receipt(bookings.outcome(SessionAccess.actor(request),id,version(version),key,fingerprint(body),Booking.State.valueOf(body.status()),body.reason()),false);}
    @PostMapping("/bookings/{id}/payments") public ResponseEntity<?> payment(@PathVariable UUID id,@RequestBody Payment body,@RequestHeader("Idempotency-Key")String key,@RequestHeader(value="If-Match",required=false)String version,HttpServletRequest request){Meeting meeting=null;try{meeting=parseMeeting(body.participantUrl());}catch(DomainFailure invalid){/* A malformed meeting must not discard a verified financial fact. */}return receipt(bookings.payment(SessionAccess.actor(request),id,version(version),key,fingerprint(body),new Money(body.amountMinor(),body.currency()),body.method(),body.reference(),body.verifiedAt(),body.confirm(),meeting),false);}
    @PostMapping("/bookings/{id}/refunds") public ResponseEntity<?> refund(@PathVariable UUID id,@RequestBody Refund body,@RequestHeader("Idempotency-Key")String key,HttpServletRequest request){return receipt(bookings.refund(SessionAccess.actor(request),id,key,fingerprint(body),body.receiptId(),new Money(body.amountMinor(),body.currency()),body.method(),body.reference(),body.verifiedAt()),false);}
    @GetMapping("/services") public Object services(HttpServletRequest request){return bookings.adminServices(SessionAccess.actor(request)).stream().map(ApiViews::service).toList();}
    @PostMapping("/services") public Object createService(@RequestBody ServiceBody body,@RequestHeader("Idempotency-Key")String key,HttpServletRequest request){return receipt(bookings.updateService(SessionAccess.actor(request),key,fingerprint(body),new ServiceDefinition(UUID.nameUUIDFromBytes((SessionAccess.actor(request).id()+key).getBytes(java.nio.charset.StandardCharsets.UTF_8)),1,body.name(),body.durationMinutes(),Money.eur(body.priceMinor()),body.active()),null),true);}
    @PutMapping("/services/{id}") public Object service(@PathVariable UUID id,@RequestBody ServiceBody body,@RequestHeader("Idempotency-Key")String key,@RequestHeader(value="If-Match",required=false)String version,HttpServletRequest request){long expected=version(version);return receipt(bookings.updateService(SessionAccess.actor(request),key,fingerprint(body),new ServiceDefinition(id,expected+1,body.name(),body.durationMinutes(),Money.eur(body.priceMinor()),body.active()),expected),false);}
    @GetMapping("/settings") public Object settings(HttpServletRequest request){var configuration=bookings.configuration(SessionAccess.actor(request));var p=configuration.policy();var c=configuration.calendar();return Map.of("policy",Map.of("version",p.version(),"gridMinutes",p.gridMinutes(),"dailyCapacity",p.dailyCapacity(),"minimumNoticeSeconds",p.minimumNotice().toSeconds(),"pendingLifetimeSeconds",p.pendingLifetime().toSeconds(),"expiryBufferSeconds",p.expiryBuffer().toSeconds(),"customerCutoffSeconds",p.customerCutoff().toSeconds()),"calendar",Map.of("weekly",c.weekly(),"exceptions",c.exceptions(),"blackouts",c.blackouts().stream().map(b->Map.of("id",b.id(),"startInstant",b.interval().start(),"endInstant",b.interval().end(),"reason",b.reason())).toList()),"conflicts",configuration.conflicts());}
    @PutMapping("/settings") public Object settings(@RequestBody SettingsBody body,@RequestHeader("Idempotency-Key")String key,@RequestHeader(value="If-Match",required=false)String version,HttpServletRequest request){
        long expected=version(version);var p=body.policy();var policy=new SchedulingPolicy(expected+1,p.gridMinutes(),p.dailyCapacity(),Duration.ofSeconds(p.minimumNoticeSeconds()),Duration.ofSeconds(p.pendingLifetimeSeconds()),Duration.ofSeconds(p.expiryBufferSeconds()),Duration.ofSeconds(p.customerCutoffSeconds()));
        Map<DayOfWeek,List<AvailabilityCalendar.Window>> weekly=new EnumMap<>(DayOfWeek.class);body.calendar().weekly().forEach((d,w)->weekly.put(d,w.stream().map(v->new AvailabilityCalendar.Window(v.startMinute(),v.endMinute())).toList()));
        Map<LocalDate,AvailabilityCalendar.DateException> exceptions=new HashMap<>();body.calendar().exceptions().forEach((d,e)->exceptions.put(d,new AvailabilityCalendar.DateException(e.mode(),e.windows().stream().map(v->new AvailabilityCalendar.Window(v.startMinute(),v.endMinute())).toList())));
        var blackouts=body.calendar().blackouts().stream().map(b->new AvailabilityCalendar.Blackout(b.id(),new ReservationInterval(b.startInstant(),b.endInstant()),b.reason())).toList();
        return receipt(bookings.configuration(SessionAccess.actor(request),key,fingerprint(body),expected,policy,new AvailabilityCalendar(weekly,exceptions,blackouts,expected+1),body.reason()),false);
    }
    @GetMapping("/bookings/{id}/notes") public Object notes(@PathVariable UUID id,HttpServletRequest request){bookings.get(SessionAccess.actor(request),id);return operations.read(SessionAccess.actor(request),"notes",id);}
    @PostMapping("/bookings/{id}/notes") public Object note(@PathVariable UUID id,@RequestBody NoteBody body,@RequestHeader("Idempotency-Key")String key,HttpServletRequest request){return receipt(bookings.note(SessionAccess.actor(request),id,key,fingerprint(body),body.note()),true);}
    @GetMapping("/bookings/{id}/payments") public Object payments(@PathVariable UUID id,HttpServletRequest request){bookings.get(SessionAccess.actor(request),id);return operations.read(SessionAccess.actor(request),"payments",id);}
    @GetMapping({"/audit","/notifications","/reconciliation","/privacy"}) public Object operational(HttpServletRequest request){return operations.read(SessionAccess.actor(request),request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/')+1),null);}
    @PostMapping("/customers/search") public Object customers(@RequestBody Search body,HttpServletRequest request){String text=Objects.requireNonNullElse(body.text(),"").toLowerCase(Locale.ROOT);return operations.read(SessionAccess.actor(request),"customers",null).stream().filter(r->r.get("email_key").toString().contains(text)).toList();}
    @PostMapping("/customers/{id}/exports") public Object export(@PathVariable UUID id,@RequestBody Reason body,HttpServletRequest request){recent(request);return Map.of("id",operations.exportCustomer(SessionAccess.actor(request),id,body.reason()),"expiresInSeconds",600);}
    @GetMapping("/exports/{id}") public ResponseEntity<?> export(@PathVariable UUID id,HttpServletRequest request){recent(request);return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header("Content-Disposition","attachment; filename=customer-export.json").cacheControl(CacheControl.noStore()).body(operations.downloadExport(SessionAccess.actor(request),id));}
    @PostMapping("/customers/{id}/erasure-requests") public Object erasure(@PathVariable UUID id,@RequestBody Reason body,HttpServletRequest request){recent(request);return Map.of("id",operations.erasure(SessionAccess.actor(request),id,body.reason()),"state","REVIEW_REQUIRED");}
    @PostMapping("/notifications/{id}/retries") public Object retry(@PathVariable UUID id,@RequestBody Reason body,HttpServletRequest request){operations.retry(SessionAccess.actor(request),id,body.reason());return Map.of("state","QUEUED");}
    private static Meeting parseMeeting(String url){return url==null||url.isBlank()?null:Meeting.parse(url,Set.of("zoom.us"));}
    private static void recent(HttpServletRequest request){require(SessionAccess.identity(request).authenticatedAt().plusSeconds(600).isAfter(Instant.now()),"REAUTHENTICATION_REQUIRED");}
}
