package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.usecase.BookingService.BookingView;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.customer.Intake;
import java.util.*;

/** Explicit transport projection keeps mutable persistence models out of HTTP. */
public final class ApiViews {
    private ApiViews(){}
    public static Map<String,Object> service(ServiceDefinition service){return Map.of("id",service.id(),"version",service.version(),"name",service.name(),"durationMinutes",service.durationMinutes(),"priceMinor",service.price().minor(),"currency",service.price().currency(),"active",service.active());}
    public static Map<String,Object> intake(Intake intake){return Map.of("firstName",intake.firstName(),"lastName",intake.lastName(),"telephone",intake.telephone(),"birthDate",intake.birthDate(),"birthTime",intake.birthTime(),"birthplace",intake.birthplace(),"message",intake.message());}
    public static Map<String,Object> booking(BookingView view){
        Booking b=view.booking();Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",b.id());result.put("reference",b.reference());result.put("status",b.state());result.put("origin",b.origin());result.put("version",b.version());
        result.put("service",service(b.snapshot().service()));result.put("intake",intake(b.snapshot().intake()));result.put("deliveryEmail",b.deliveryEmail());
        result.put("displayZone",b.snapshot().displayZone());result.put("businessZone","Europe/Brussels");result.put("startInstant",b.start());result.put("sessionEndInstant",b.sessionEnd());result.put("reservedEndInstant",b.reservedEnd());
        result.put("expiresAt",b.expiresAt());result.put("cancellationDeadline",b.start().minus(b.snapshot().policy().customerCutoff()));result.put("payment",view.payment());result.put("permittedActions",view.permittedActions());
        result.put("participantUrl",b.state()==Booking.State.CONFIRMED&&b.meeting()!=null?b.meeting().participantUrl():null);result.put("calendarRevision",b.calendarRevision());result.put("confirmationBasis",b.confirmationBasis());
        return result;
    }
}
