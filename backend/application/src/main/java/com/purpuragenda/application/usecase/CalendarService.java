package com.purpuragenda.application.usecase;
import com.purpuragenda.application.port.in.Actor;
import com.purpuragenda.application.port.out.*;
import static com.purpuragenda.domain.DomainFailure.require;
import java.time.Instant;
import java.util.UUID;

public final class CalendarService {
    private final BookingService bookings;private final CalendarWriter writer;
    public CalendarService(BookingService bookings,CalendarWriter writer){this.bookings=bookings;this.writer=writer;}
    public byte[] export(Actor actor,UUID id){var b=bookings.get(actor,id).booking();require(b.previouslyConfirmed(),"CALENDAR_UNAVAILABLE");return writer.write(b,actor.admin()?"OWNER":"CUSTOMER",Instant.now());}
}
