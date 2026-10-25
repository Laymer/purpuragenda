package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.usecase.BookingService;
import com.purpuragenda.application.port.in.Actor;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Receipt;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.customer.Intake;
import com.purpuragenda.domain.scheduling.SchedulingRules;

import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class BookingController {
    private final BookingService service;
    public BookingController(BookingService service){this.service=service;}
    public record IntakeBody(String firstName,String lastName,String telephone,LocalDate birthDate,LocalTime birthTime,String birthplace,String message){public Intake domain(){return new Intake(firstName,lastName,telephone,birthDate,birthTime,birthplace,message);}}
    public record RequestBody(UUID serviceId,long serviceVersion,long policyVersion,Instant startInstant,String displayZone,IntakeBody intake,String privacyNoticeVersion){}
    public record Cancellation(String reason){}
    public record Move(Instant startInstant,String displayZone,long policyVersion){}
    @GetMapping("/services") public Object services(){return service.services().stream().map(ApiViews::service).toList();}
    @GetMapping("/availability") public Object availability(@RequestParam UUID serviceId,@RequestParam LocalDate from,@RequestParam LocalDate to,@RequestParam String displayZone,HttpServletRequest request){var identity=SessionAccess.identity(request);return service.availability(serviceId,from,to,displayZone,identity==null?null:identity.actor());}
    @PostMapping("/bookings") public ResponseEntity<?> create(@org.springframework.web.bind.annotation.RequestBody RequestBody body,@RequestHeader("Idempotency-Key")String key,HttpServletRequest request){
        Actor actor=SessionAccess.actor(request);if(body.intake()==null)throw new DomainFailure("VALIDATION_FAILED");
        var command=new BookingService.Request(body.serviceId(),body.serviceVersion(),body.policyVersion(),body.startInstant(),body.displayZone(),body.intake().domain(),body.privacyNoticeVersion(),null,false,null,SchedulingRules.Override.none());
        return receipt(service.create(actor,key,fingerprint(body),command),true);
    }
    @GetMapping("/bookings/{id}") public ResponseEntity<?> get(@PathVariable UUID id,HttpServletRequest request){var view=service.get(SessionAccess.actor(request),id);return ResponseEntity.ok().eTag(Long.toString(view.booking().version())).cacheControl(CacheControl.noStore()).body(ApiViews.booking(view));}
    @GetMapping("/bookings/{id}/availability") public Object rescheduleAvailability(@PathVariable UUID id,@RequestParam LocalDate from,@RequestParam LocalDate to,@RequestParam String displayZone,HttpServletRequest request){return service.rescheduleAvailability(SessionAccess.actor(request),id,from,to,displayZone);}
    @PostMapping("/bookings/{id}/cancellations") public ResponseEntity<?> cancel(@PathVariable UUID id,@org.springframework.web.bind.annotation.RequestBody Cancellation body,@RequestHeader("Idempotency-Key")String key,@RequestHeader(value="If-Match",required=false)String version,HttpServletRequest request){return receipt(service.cancel(SessionAccess.actor(request),id,version(version),key,fingerprint(body),"CUSTOMER",body.reason()),false);}
    @PostMapping("/bookings/{id}/reschedules") public ResponseEntity<?> move(@PathVariable UUID id,@org.springframework.web.bind.annotation.RequestBody Move body,@RequestHeader("Idempotency-Key")String key,@RequestHeader(value="If-Match",required=false)String version,HttpServletRequest request){com.purpuragenda.domain.customer.Input.zone(body.displayZone());return receipt(service.reschedule(SessionAccess.actor(request),id,version(version),key,fingerprint(body),body.startInstant(),body.policyVersion(),SchedulingRules.Override.none(),""),false);}
    @GetMapping("/account/bookings") public Object account(HttpServletRequest request){return service.list(SessionAccess.actor(request)).stream().map(ApiViews::booking).toList();}
    public static long version(String value){if(value==null)throw new DomainFailure("PRECONDITION_REQUIRED");try{return Long.parseLong(value.replace("\"",""));}catch(NumberFormatException e){throw new DomainFailure("VALIDATION_FAILED");}}
    public static String fingerprint(Object body){return RequestSecrets.hash(com.purpuragenda.adapters.codec.Json.write(body));}
    public static ResponseEntity<?> receipt(Receipt result,boolean created){if(!result.success())return ProblemHandler.problem(result.code());return ResponseEntity.status(created?201:200).cacheControl(CacheControl.noStore()).body(result);}
}
