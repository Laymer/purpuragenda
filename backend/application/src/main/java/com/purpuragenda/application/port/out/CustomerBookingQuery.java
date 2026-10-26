package com.purpuragenda.application.port.out;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.payment.PaymentLedger;
import java.time.Instant;
import java.util.*;

/** One read-only snapshot: persisted authority, booking, independent money and database time. */
public interface CustomerBookingQuery {
    /** Unknown, unowned, revoked and out-of-scope targets are indistinguishable. Never touches a session. */
    Optional<Authorized> find(SessionBinding session,UUID bookingId);
    record Authorized(Booking booking,PaymentLedger ledger,Instant now) {
        public Authorized { Objects.requireNonNull(booking);Objects.requireNonNull(ledger);Objects.requireNonNull(now); }
        @Override public String toString() { return "AuthorizedBooking[REDACTED]"; }
    }
}
