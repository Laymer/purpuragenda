package com.purpuragenda.application.port.out;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.payment.PaymentLedger;
import java.time.*;
import java.util.*;

/** Administrator authority and data share one read-only consistent snapshot; no session touch or resource lock. */
public interface AdministratorBookingQuery {
    Page agenda(SessionBinding session,Window window,Cursor after,int limit);
    Optional<Detail> detail(SessionBinding session,UUID bookingId);
    record Window(Instant from,Instant until) {
        public Window { Objects.requireNonNull(from);Objects.requireNonNull(until);if(!from.isBefore(until))throw new IllegalArgumentException("INVALID_AGENDA_WINDOW"); }
        @Override public String toString() { return "AgendaWindow[REDACTED]"; }
    }
    record Cursor(Instant start,UUID id) {
        public Cursor { Objects.requireNonNull(start);Objects.requireNonNull(id); }
        @Override public String toString() { return "AgendaCursor[REDACTED]"; }
    }
    /** Minimal agenda intentionally excludes intake, customer identifiers, contacts, meeting links and money references. */
    record Entry(UUID id,String reference,long version,Booking.State state,Instant start,Instant sessionEnd,String serviceName) {
        @Override public String toString() { return "AdministratorAgendaEntry[REDACTED]"; }
    }
    record Page(List<Entry> entries,Cursor next,Instant observedAt) {
        public Page { entries=List.copyOf(entries);Objects.requireNonNull(observedAt); }
        @Override public String toString() { return "AdministratorAgendaPage[REDACTED]"; }
    }
    /** Full immutable booking and independent payment facts for the private owner detail projection. */
    record Detail(Booking booking,PaymentLedger ledger,Instant observedAt,com.purpuragenda.domain.customer.Intake effectiveIntake) {
        public Detail(Booking booking,PaymentLedger ledger,Instant observedAt){this(booking,ledger,observedAt,booking.snapshot().intake());}
        public Detail { Objects.requireNonNull(booking);Objects.requireNonNull(ledger);Objects.requireNonNull(observedAt);Objects.requireNonNull(effectiveIntake); }
        @Override public String toString() { return "AdministratorBookingDetail[REDACTED]"; }
    }
}
