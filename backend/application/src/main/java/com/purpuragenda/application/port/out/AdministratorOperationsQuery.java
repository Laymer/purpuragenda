package com.purpuragenda.application.port.out;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import java.time.Instant;
import java.util.*;
import static com.purpuragenda.domain.DomainFailure.require;

/** Private operational projections; financial facts and delivery work remain in their original ledgers. */
public interface AdministratorOperationsQuery {
    Page<Refund> reconciliation(SessionBinding session,UUID bookingId,Request request);
    /** Booking notifications only: authentication delivery envelopes and recipients are never exposed. */
    Page<Notification> notifications(SessionBinding session,UUID bookingId,NotificationState state,Request request);
    enum NotificationState { QUEUED, LEASED, SENT, SUPPRESSED, FAILED }
    record Request(UUID after,int limit) {
        public Request { require(limit>=1 && limit<=100,"INVALID_OPERATIONS_PAGE"); }
        @Override public String toString(){return "OperationsRequest[REDACTED]";}
    }
    record Page<T>(List<T> entries,UUID next,Instant observedAt) {
        public Page { entries=List.copyOf(entries);Objects.requireNonNull(observedAt); }
        @Override public String toString(){return "OperationsPage[REDACTED]";}
    }
    /** dueMinor is the full obligation; outstandingMinor subtracts immutable linked refunds. */
    record Refund(UUID receiptId,UUID bookingId,String reference,String state,String currency,long receivedMinor,long refundedMinor,long dueMinor,long outstandingMinor,String reason,Instant verifiedAt) {
        @Override public String toString(){return "ReconciliationRefund[REDACTED]";}
    }
    record Notification(UUID id,UUID bookingId,String reference,String audience,String template,long version,NotificationState state,int attempts,Instant dueAt,String safeCode) {
        @Override public String toString(){return "NotificationFollowUp[REDACTED]";}
    }
}
