package com.purpuragenda.application.port.out;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.payment.PaymentLedger;
import java.util.*;
import java.util.function.Function;
import static com.purpuragenda.domain.DomainFailure.require;
import static com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;

/**
 * One resource-locked administrator financial transaction. The adapter derives the administrator only from the
 * persisted ADMIN session (enabled, enrolled, unrevoked, fresh MFA, current security epoch) before any receipt replay.
 */
public interface FinanceUnitOfWork {
    Receipt execute(Command command,Function<Finance,Receipt> work);
    enum Operation { RECORD_RECEIPT }
    record Command(SessionBinding session,UUID bookingId,Operation operation,String key,String fingerprint) {
        public Command {
            Objects.requireNonNull(session);
            require(bookingId!=null && operation!=null,"INVALID_FINANCE_COMMAND");
            new CommandKey(new Scope(session.sessionId(),operation.name(),bookingId),key,fingerprint);
        }
        @Override public String toString() { return "FinanceCommand[REDACTED]"; }
    }
    interface Finance extends Transaction {
        /** Locked target booking, refreshed after synchronous expiry reclamation. Unknown bookings fail NOT_FOUND. */
        Booking authorizedBooking();
        /** Every receipt, refund and obligation of the locked booking. */
        PaymentLedger ledger();
        /** True when a financial fact with this kind/method/reference exists on any booking. */
        boolean referenceUsed(PaymentLedger.Kind kind,String method,String reference);
        /** Persist one verified receipt; a late receipt on a terminal booking also records its reconciliation obligation. */
        void receiptRecorded(PaymentLedger.Entry receipt,boolean late);
    }
}
