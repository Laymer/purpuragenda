package com.purpuragenda.application.scheduling;

import com.purpuragenda.application.port.out.CustomerBookingQuery;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.payment.PaymentLedger;
import java.time.Instant;
import java.util.*;

/** Customer status projection; intake, account identifiers and external payment references stay internal. */
public final class CustomerBookingView {
    private final CustomerBookingQuery query;
    public CustomerBookingView(CustomerBookingQuery query) { this.query=Objects.requireNonNull(query); }
    public enum Action { CANCEL, RESCHEDULE }
    public record Service(String name,int durationMinutes,Money price) { }
    public record View(UUID id,String reference,long version,Booking.State state,boolean pendingExpired,
                       Service service,Instant start,Instant sessionEnd,String displayZone,String deliveryEmail,
                       Instant pendingExpiresAt,Instant customerChangesUntil,Instant observedAt,
                       String meetingUrl,PaymentLedger.Summary payment,Set<Action> allowedActions) {
        public View { allowedActions=Set.copyOf(allowedActions); }
        @Override public String toString() { return "CustomerBookingView[REDACTED]"; }
    }
    public Optional<View> find(SessionBinding session,UUID bookingId) {
        Objects.requireNonNull(session);Objects.requireNonNull(bookingId);
        return query.find(session,bookingId).map(value->{
            Booking b=value.booking();var service=b.snapshot().service();Instant now=value.now();
            boolean expired=b.state()==Booking.State.PENDING_CONFIRMATION && !b.livePending(now);
            return new View(b.id(),b.reference(),b.version(),b.state(),expired,
                    new Service(service.name(),service.durationMinutes(),service.price()),b.start(),b.sessionEnd(),
                    b.snapshot().displayZone(),b.deliveryEmail(),b.expiresAt(),b.start().minus(b.snapshot().policy().customerCutoff()),now,
                    b.state()==Booking.State.CONFIRMED?b.meeting().participantUrl():null,value.ledger().summary(service.price()),
                    b.customerMayChange(now)?Set.of(Action.CANCEL,Action.RESCHEDULE):Set.of());
        });
    }
}
