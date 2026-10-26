package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.application.port.out.AdministratorBookingQuery.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Canonical bounded agenda cursors and explicit private wire mapping; never generic domain serialization. */
final class AdministratorBookingHttp {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private AdministratorBookingHttp() { }
    record Parameters(LocalDate from,LocalDate through,Cursor after,int limit) { @Override public String toString() { return "AdministratorAgendaParameters[REDACTED]"; } }
    static Parameters parameters(HttpServletRequest request) throws BookingHttp.Invalid {
        var values=request.getParameterMap();
        if(!Set.of("from","through","cursor","limit").containsAll(values.keySet()) || !values.keySet().containsAll(Set.of("from","through")))throw new BookingHttp.Invalid();
        for(String[] value:values.values())if(value==null || value.length!=1 || value[0]==null || value[0].isEmpty() || value[0].length()>200)throw new BookingHttp.Invalid();
        String from=request.getParameter("from"),through=request.getParameter("through"),limit=request.getParameter("limit"),cursor=request.getParameter("cursor");
        if(!from.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || !through.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || limit!=null && !limit.matches("[1-9][0-9]{0,2}"))throw new BookingHttp.Invalid();
        return new Parameters(LocalDate.parse(from),LocalDate.parse(through),cursor==null?null:cursor(cursor),limit==null?50:Integer.parseInt(limit));
    }
    static String cursor(Cursor value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((value.start()+"|"+value.id()).getBytes(StandardCharsets.US_ASCII));
    }
    static Cursor cursor(String value) throws BookingHttp.Invalid {
        if(value.length()>200 || !value.matches("[A-Za-z0-9_-]+"))throw new BookingHttp.Invalid();
        try {
            String[] fields=new String(Base64.getUrlDecoder().decode(value),StandardCharsets.US_ASCII).split("\\|",-1);
            if(fields.length!=2)throw new BookingHttp.Invalid();
            var result=new Cursor(Instant.parse(fields[0]),BookingHttp.id(fields[1]));
            if(!cursor(result).equals(value))throw new BookingHttp.Invalid();return result;
        } catch(IllegalArgumentException | DateTimeException invalid) { throw new BookingHttp.Invalid(); }
    }
    static String page(Page page) {
        var body=JSON.createObjectNode().put("serverNow",page.observedAt().toString()).put("businessZone","Europe/Brussels");
        body.put("nextCursor",page.next()==null?null:cursor(page.next()));var entries=body.putArray("entries");
        for(var row:page.entries())entries.addObject().put("id",row.id().toString()).put("reference",row.reference()).put("version",row.version()).put("state",row.state().name())
                .put("startInstant",row.start().toString()).put("sessionEndInstant",row.sessionEnd().toString()).put("serviceName",row.serviceName());
        return body.toString();
    }
    static String detail(Detail detail) {
        var b=detail.booking();var snapshot=b.snapshot();var service=snapshot.service();var policy=snapshot.policy();var intake=snapshot.intake();
        ObjectNode body=JSON.createObjectNode().put("id",b.id().toString()).put("reference",b.reference()).put("version",b.version()).put("state",b.state().name()).put("origin",b.origin().name())
                .put("createdAt",b.createdAt().toString()).put("startInstant",b.start().toString()).put("sessionEndInstant",b.sessionEnd().toString()).put("reservationEndInstant",b.reservedEnd().toString())
                .put("pendingExpiresAt",b.expiresAt()==null?null:b.expiresAt().toString()).put("pendingExpired",b.state()==com.purpuragenda.domain.booking.Booking.State.PENDING_CONFIRMATION && !b.livePending(detail.observedAt()))
                .put("deliveryEmail",b.deliveryEmail()).put("meetingUrl",b.meeting()==null?null:b.meeting().participantUrl()).put("calendarRevision",b.calendarRevision())
                .put("confirmationBasis",b.confirmationBasis()==null?null:b.confirmationBasis().name()).put("serverNow",detail.observedAt().toString());
        var frozen=body.putObject("snapshot").put("creationEmail",snapshot.creationEmail()).put("displayZone",snapshot.displayZone()).put("noticeVersion",snapshot.privacyNoticeVersion());
        frozen.putObject("service").put("id",service.id().toString()).put("version",service.version()).put("name",service.name()).put("durationMinutes",service.durationMinutes())
                .put("priceMinor",service.price().minor()).put("currency",service.price().currency()).put("active",service.active());
        frozen.putObject("policy").put("version",policy.version()).put("gridMinutes",policy.gridMinutes()).put("dailyCapacity",policy.dailyCapacity()).put("minimumNoticeSeconds",policy.minimumNotice().getSeconds())
                .put("pendingLifetimeSeconds",policy.pendingLifetime().getSeconds()).put("expiryBufferSeconds",policy.expiryBuffer().getSeconds()).put("customerCutoffSeconds",policy.customerCutoff().getSeconds());
        frozen.putObject("intake").put("firstName",intake.firstName()).put("lastName",intake.lastName()).put("telephone",intake.telephone()).put("birthDate",intake.birthDate().toString())
                .put("birthTime",intake.birthTime().toString()).put("birthplace",intake.birthplace()).put("message",intake.message());
        var payment=body.putObject("payment");var summary=detail.ledger().summary(service.price());
        payment.put("status",summary.status().name()).put("receivedMinor",summary.receivedMinor()).put("refundedMinor",summary.refundedMinor()).put("remainingMinor",summary.remainingMinor());
        var due=payment.putObject("refundDueByCurrency");new TreeMap<>(summary.refundDueByCurrency()).forEach(due::put);
        var received=payment.putObject("receivedByCurrency");new TreeMap<>(summary.receivedByCurrency()).forEach(received::put);
        var entries=payment.putArray("entries");
        for(var entry:detail.ledger().entries())entries.addObject().put("id",entry.id().toString()).put("kind",entry.kind().name()).put("amountMinor",entry.amount().minor()).put("currency",entry.amount().currency())
                .put("method",entry.method()).put("reference",entry.reference()).put("verifiedAt",entry.verifiedAt().toString()).put("receiptId",entry.receiptId()==null?null:entry.receiptId().toString());
        var obligations=payment.putArray("refundObligations");
        for(var obligation:detail.ledger().obligations())obligations.addObject().put("receiptId",obligation.receiptId().toString()).put("dueMinor",obligation.dueMinor()).put("reason",obligation.reason());
        return body.toString();
    }
}
