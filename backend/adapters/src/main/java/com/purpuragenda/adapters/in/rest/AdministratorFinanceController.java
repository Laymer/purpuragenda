package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.port.out.SessionStore.*;
import com.purpuragenda.application.scheduling.AdministratorFinance;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.Money;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Independent actual-money commands and separately versioned confirmations; persisted fresh MFA is rechecked by the unit. */
@RestController
@RequestMapping("/api/v1/admin/bookings/{bookingId}")
public final class AdministratorFinanceController {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final AdministratorFinance finance;
    public AdministratorFinanceController(AdministratorFinance finance) { this.finance=Objects.requireNonNull(finance); }
    @PostMapping("/receipts")
    public ResponseEntity<String> receipt(@PathVariable String bookingId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        return command(bookingId,request,response,false,"paymentEntryId",(session,id,key)->{
            var body=BookingHttp.body(request,"amountMinor","currency","method","reference","verifiedAt");
            return finance.recordReceipt(session,id,key,new AdministratorFinance.ReceiptRequest(amount(body),BookingHttp.text(body,"method"),BookingHttp.text(body,"reference"),verified(body)));
        });
    }
    @PostMapping("/approve")
    public ResponseEntity<String> approve(@PathVariable String bookingId,HttpServletRequest request,HttpServletResponse response) throws IOException { return confirmation(bookingId,request,response,false); }
    @PostMapping("/confirm-external-payment")
    public ResponseEntity<String> confirmExternal(@PathVariable String bookingId,HttpServletRequest request,HttpServletResponse response) throws IOException { return confirmation(bookingId,request,response,true); }
    private ResponseEntity<String> confirmation(String bookingId,HttpServletRequest request,HttpServletResponse response,boolean external) throws IOException {
        return command(bookingId,request,response,true,null,(session,id,key)->{
            var body=BookingHttp.body(request,"meetingUrl");var value=new AdministratorFinance.Confirmation(BookingHttp.version(request),BookingHttp.text(body,"meetingUrl"));
            return external?finance.confirmExternalPayment(session,id,key,value):finance.approve(session,id,key,value);
        });
    }
    @PostMapping("/refunds")
    public ResponseEntity<String> refund(@PathVariable String bookingId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        return command(bookingId,request,response,false,"paymentEntryId",(session,id,key)->{
            var body=BookingHttp.body(request,"receiptId","amountMinor","currency","method","reference","verifiedAt");
            return finance.recordRefund(session,id,key,new AdministratorFinance.RefundRequest(BookingHttp.id(BookingHttp.text(body,"receiptId")),amount(body),BookingHttp.text(body,"method"),BookingHttp.text(body,"reference"),verified(body)));
        });
    }
    @PostMapping("/refund-requirements")
    public ResponseEntity<String> requireRefund(@PathVariable String bookingId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        return command(bookingId,request,response,false,"receiptId",(session,id,key)->{
            var body=BookingHttp.body(request,"receiptId","reason");
            return finance.requireRefund(session,id,key,new AdministratorFinance.RefundRequirement(BookingHttp.id(BookingHttp.text(body,"receiptId")),BookingHttp.text(body,"reason")));
        });
    }
    private interface Work { Receipt run(SessionBinding session,UUID id,String key) throws BookingHttp.Invalid; }
    private ResponseEntity<String> command(String bookingId,HttpServletRequest request,HttpServletResponse response,boolean versioned,String entityField,Work work) throws IOException {
        if(!(request.getAttribute(ApiGuardFilter.SESSION_ATTRIBUTE) instanceof Snapshot session)) { BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);return null; }
        if(session.kind()!=Kind.ADMIN) { BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);return null; }
        if(versioned && request.getHeader("If-Match")==null) { BookingHttp.problem(response,428,"VERSION_REQUIRED",false,false);return null; }
        try {
            // Actual-money facts do not have a booking-version precondition. Reject an implied unsupported condition.
            if(!versioned && request.getHeader("If-Match")!=null)throw new BookingHttp.Invalid();
            UUID id=BookingHttp.id(bookingId);var reference=session.reference();
            var receipt=work.run(new SessionBinding(reference.id(),reference.browserHash()),id,BookingHttp.header(request,"Idempotency-Key"));
            if(!receipt.success()) {
                int status=receipt.code()==Code.STALE_VERSION?412:Set.of(Code.INVALID_PAYMENT,Code.INVALID_REFUND,Code.INVALID_MEETING).contains(receipt.code())?400:409;
                BookingHttp.problem(response,status,receipt.code().name(),false,false);return null;
            }
            var body=JSON.createObjectNode().put("bookingId",id.toString());
            if(entityField==null)body.put("version",receipt.version());else body.put(entityField,receipt.entityId().toString()).put("bookingVersion",receipt.version());
            var result=ResponseEntity.status("paymentEntryId".equals(entityField)?201:200).header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.APPLICATION_JSON);
            if(versioned)result.eTag('"'+Long.toString(receipt.version())+'"');return result.body(body.toString());
        } catch(BookingHttp.Invalid | DomainFailure | IllegalArgumentException | DateTimeException invalid) { BookingHttp.problem(response,400,"INVALID_REQUEST",false,false); }
        catch(Failure failure) {
            switch(failure.kind()) {
                case AUTHENTICATION_REQUIRED -> BookingHttp.problem(response,401,"SESSION_REQUIRED",false,false);
                case FRESH_AUTHENTICATION_REQUIRED -> BookingHttp.problem(response,403,"AUTHENTICATION_REQUIRED",false,false);
                case FORBIDDEN -> BookingHttp.problem(response,403,"ADMIN_ACCESS_REQUIRED",false,false);
                case NOT_FOUND -> BookingHttp.problem(response,404,"BOOKING_NOT_FOUND",false,false);
                default -> BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false);
            }
        } catch(RuntimeException unavailable) { BookingHttp.problem(response,503,"TEMPORARILY_UNAVAILABLE",true,false); }
        return null;
    }
    private static Money amount(JsonNode body) throws BookingHttp.Invalid { return new Money(BookingHttp.number(body,"amountMinor"),BookingHttp.text(body,"currency")); }
    private static Instant verified(JsonNode body) throws BookingHttp.Invalid { return OffsetDateTime.parse(BookingHttp.text(body,"verifiedAt")).toInstant(); }
}
