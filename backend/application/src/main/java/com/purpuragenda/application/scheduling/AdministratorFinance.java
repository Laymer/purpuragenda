package com.purpuragenda.application.scheduling;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.FinanceUnitOfWork;
import com.purpuragenda.application.port.out.FinanceUnitOfWork.*;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.customer.Input;
import com.purpuragenda.domain.payment.PaymentLedger;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.purpuragenda.domain.DomainFailure.require;

/**
 * Administrator external-money commands. Authority, receipt replay and persistence belong to the authorized
 * transaction. A recorded receipt is an independent fact: it never confirms, extends or revives a booking.
 */
public final class AdministratorFinance {
    private static final Set<Booking.State> NO_LIVE_HOLD=EnumSet.of(Booking.State.CANCELLED,Booking.State.REJECTED,Booking.State.EXPIRED);
    private final FinanceUnitOfWork unit;
    public AdministratorFinance(FinanceUnitOfWork unit) { this.unit=Objects.requireNonNull(unit); }

    /** Actual money in its own currency; the amount is never converted and has no service-price ceiling. */
    public record ReceiptRequest(Money amount,String method,String reference,Instant verifiedAt) {
        public ReceiptRequest {
            require(amount!=null && amount.minor()>0 && verifiedAt!=null,"INVALID_PAYMENT");
            method=Input.text(method,1,80);reference=Input.text(reference,1,120);
            require(verifiedAt.equals(verifiedAt.truncatedTo(ChronoUnit.MICROS)),"INVALID_PAYMENT");
        }
        @Override public String toString() { return "ReceiptRequest[REDACTED]"; }
    }

    /** Result entity is the new payment entry; version is the unchanged booking version. */
    public Receipt recordReceipt(SessionBinding session,UUID bookingId,String key,ReceiptRequest request) {
        Objects.requireNonNull(request);
        String fingerprint=CommandKey.fingerprint(List.of(request.amount().currency(),Long.toString(request.amount().minor()),
                request.method(),request.reference(),request.verifiedAt().toString()));
        return unit.execute(new Command(session,bookingId,Operation.RECORD_RECEIPT,key,fingerprint),
                tx -> SchedulingCommands.within(tx,() -> {
                    Booking booking=tx.authorizedBooking();
                    // The fact is independent of hold, meeting and state, but cannot be dated after the captured time.
                    require(!request.verifiedAt().isAfter(tx.now()),"INVALID_PAYMENT");
                    require(!tx.referenceUsed(PaymentLedger.Kind.RECEIPT,request.method(),request.reference()),"DUPLICATE_PAYMENT");
                    PaymentLedger ledger=tx.ledger();
                    var entry=new PaymentLedger.Entry(UUID.randomUUID(),booking.id(),PaymentLedger.Kind.RECEIPT,request.amount(),
                            request.method(),request.reference(),request.verifiedAt(),null);
                    boolean late=NO_LIVE_HOLD.contains(booking.state());
                    PaymentLedger updated=ledger.append(entry);
                    if(late)updated=updated.requireRefund(entry.id(),"LATE_RECEIPT");
                    try { updated.summary(booking.snapshot().service().price()); }
                    catch(ArithmeticException overflow) { throw new com.purpuragenda.domain.DomainFailure("INVALID_PAYMENT"); }
                    tx.receiptRecorded(entry,late);
                    return new Receipt(Code.OK,entry.id(),booking.version());
                }));
    }
}
