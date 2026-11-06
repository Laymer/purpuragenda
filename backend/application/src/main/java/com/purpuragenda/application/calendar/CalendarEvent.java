package com.purpuragenda.application.calendar;

import com.purpuragenda.domain.booking.Booking;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Minimal export projection. The caller must authorize the booking before projecting it. */
public final class CalendarEvent {
    public enum Audience { CUSTOMER, OWNER }
    public enum Status { TENTATIVE, CONFIRMED, CANCELLED }

    private final UUID bookingId;
    private final Audience audience;
    private final int revision;
    private final Instant start, end;
    private final Status status;
    private final Optional<String> meetingUrl;

    private CalendarEvent(Booking booking, Audience audience) {
        this.bookingId=booking.id();
        this.audience=Objects.requireNonNull(audience);
        this.revision=booking.calendarRevision();
        this.start=booking.start();
        this.end=booking.sessionEnd();
        this.status=switch(booking.state()) {
            case PENDING_CONFIRMATION -> Status.TENTATIVE;
            case CANCELLED, REJECTED, EXPIRED -> Status.CANCELLED;
            case CONFIRMED, COMPLETED, NO_SHOW -> Status.CONFIRMED;
        };
        // Match authorized booking views: pending and terminal events expose no meeting link.
        this.meetingUrl=booking.state()==Booking.State.CONFIRMED
                ? Optional.of(booking.meeting().participantUrl()) : Optional.empty();
    }

    public static CalendarEvent from(Booking booking, Audience audience) {
        return new CalendarEvent(Objects.requireNonNull(booking),audience);
    }
    public UUID bookingId() { return bookingId; }
    public Audience audience() { return audience; }
    public int revision() { return revision; }
    public Instant start() { return start; }
    public Instant end() { return end; }
    public Status status() { return status; }
    public Optional<String> meetingUrl() { return meetingUrl; }
    @Override public String toString() { return "CalendarEvent[REDACTED]"; }
}
