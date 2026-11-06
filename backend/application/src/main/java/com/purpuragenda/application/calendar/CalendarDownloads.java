package com.purpuragenda.application.calendar;

import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.domain.booking.Booking;
import java.time.Instant;
import java.util.*;

/** Authorize from persisted read snapshots before serializing a private calendar. No mutations or clock reads. */
public final class CalendarDownloads {
    private final CustomerBookingQuery customers;
    private final AdministratorBookingQuery owners;
    private final CalendarWriter writer;
    public CalendarDownloads(CustomerBookingQuery customers,AdministratorBookingQuery owners,CalendarWriter writer) {
        this.customers=Objects.requireNonNull(customers);this.owners=Objects.requireNonNull(owners);this.writer=Objects.requireNonNull(writer);
    }
    public Optional<byte[]> customer(SessionBinding session,UUID bookingId) {
        Objects.requireNonNull(session);Objects.requireNonNull(bookingId);
        return customers.find(session,bookingId).map(value->write(value.booking(),CalendarEvent.Audience.CUSTOMER,value.now()));
    }
    public Optional<byte[]> owner(SessionBinding session,UUID bookingId) {
        Objects.requireNonNull(session);Objects.requireNonNull(bookingId);
        return owners.detail(session,bookingId).map(value->write(value.booking(),CalendarEvent.Audience.OWNER,value.observedAt()));
    }
    private byte[] write(Booking booking,CalendarEvent.Audience audience,Instant now) {
        // Reads cannot reclaim a hold, but an elapsed hold must never look like a live tentative appointment.
        Booking current=booking.state()==Booking.State.PENDING_CONFIRMATION && !booking.livePending(now)?booking.expire(now):booking;
        return writer.write(CalendarEvent.from(current,audience),now);
    }
}
