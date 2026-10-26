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
    private final Set<String> meetingHosts;
    /** Allowed participant-meeting domains; a nonresolving synthetic domain belongs only in local-mode configuration. */
    public AdministratorFinance(FinanceUnitOfWork unit,Set<String> meetingHosts) {
        this.unit=Objects.requireNonNull(unit);
        require(meetingHosts!=null && !meetingHosts.isEmpty(),"INVALID_MEETING");this.meetingHosts=Set.copyOf(meetingHosts);
    }

    /** Version the administrator reviewed and the participant URL; never logged. */
    public record Confirmation(long expectedVersion,String meetingUrl) {
        public Confirmation {
            require(expectedVersion>0 && meetingUrl!=null,"INVALID_CONFIRMATION");
        }
        @Override public String toString() { return "Confirmation[REDACTED]"; }
    }

    /** Manual approval needs a live hold and a unique validated meeting; it asserts nothing about payment. */
    public Receipt approve(SessionBinding session,UUID bookingId,String key,Confirmation request) {
        return confirm(session,bookingId,key,request,Operation.APPROVE_BOOKING,Booking.ConfirmationBasis.MANUAL_APPROVAL);
    }
    /** Confirmation by external payment additionally needs net sufficient matching-currency funds and no refund due. */
    public Receipt confirmExternalPayment(SessionBinding session,UUID bookingId,String key,Confirmation request) {
        return confirm(session,bookingId,key,request,Operation.CONFIRM_BOOKING,Booking.ConfirmationBasis.EXTERNAL_PAYMENT);
    }
    /** A refund of actual money against one receipt, in that receipt's own currency; nothing is converted. */
    public record RefundRequest(UUID receiptId,Money amount,String method,String reference,Instant verifiedAt) {
        public RefundRequest {
            require(receiptId!=null && amount!=null && amount.minor()>0 && verifiedAt!=null,"INVALID_REFUND");
            method=Input.text(method,1,80);reference=Input.text(reference,1,120);
            require(verifiedAt.equals(verifiedAt.truncatedTo(ChronoUnit.MICROS)),"INVALID_REFUND");
        }
        @Override public String toString() { return "RefundRequest[REDACTED]"; }
    }
    /** Mark one receipt as owing a refund (for example after a provider cancellation). */
    public record RefundRequirement(UUID receiptId,String reason) {
        public RefundRequirement {
            require(receiptId!=null,"INVALID_REFUND");reason=Input.reason(reason);
        }
        @Override public String toString() { return "RefundRequirement[REDACTED]"; }
    }

    /**
     * Records a refund bounded by what its receipt still holds. Refunds are independent money facts: they apply in any
     * booking state, need no booking version and consume outstanding obligations through the ledger projection.
     */
    public Receipt recordRefund(SessionBinding session,UUID bookingId,String key,RefundRequest request) {
        Objects.requireNonNull(request);
        String fingerprint=CommandKey.fingerprint(List.of(request.receiptId().toString(),request.amount().currency(),Long.toString(request.amount().minor()),
                request.method(),request.reference(),request.verifiedAt().toString()));
        return unit.execute(new Command(session,bookingId,Operation.RECORD_REFUND,key,fingerprint),
                tx -> SchedulingCommands.within(tx,() -> {
                    Booking booking=tx.authorizedBooking();
                    require(!request.verifiedAt().isAfter(tx.now()),"INVALID_REFUND");
                    require(!tx.referenceUsed(PaymentLedger.Kind.REFUND,request.method(),request.reference()),"DUPLICATE_PAYMENT");
                    PaymentLedger ledger=tx.ledger();
                    PaymentLedger.Entry receipt=receipt(ledger,request.receiptId());
                    // Exact per-currency bound: a different currency is never converted or inferred.
                    require(receipt.amount().currency().equals(request.amount().currency()),"INVALID_REFUND");
                    long returned=ledger.entries().stream().filter(e -> e.kind()==PaymentLedger.Kind.REFUND && request.receiptId().equals(e.receiptId()))
                            .mapToLong(e -> e.amount().minor()).reduce(0,Math::addExact);
                    require(request.amount().minor()<=receipt.amount().minor()-returned,"REFUND_EXCEEDS_RECEIPT");
                    var entry=new PaymentLedger.Entry(UUID.randomUUID(),booking.id(),PaymentLedger.Kind.REFUND,request.amount(),
                            request.method(),request.reference(),request.verifiedAt(),request.receiptId());
                    ledger.append(entry);
                    tx.refundRecorded(entry);
                    return new Receipt(Code.OK,entry.id(),booking.version());
                }));
    }

    /** Idempotent: a receipt that already owes a refund succeeds without a second obligation or event. */
    public Receipt requireRefund(SessionBinding session,UUID bookingId,String key,RefundRequirement request) {
        Objects.requireNonNull(request);
        String fingerprint=CommandKey.fingerprint(List.of(request.receiptId().toString(),request.reason()));
        return unit.execute(new Command(session,bookingId,Operation.REQUIRE_REFUND,key,fingerprint),
                tx -> SchedulingCommands.within(tx,() -> {
                    Booking booking=tx.authorizedBooking();
                    PaymentLedger ledger=tx.ledger();
                    receipt(ledger,request.receiptId());
                    if(ledger.obligations().stream().noneMatch(o -> o.receiptId().equals(request.receiptId())))
                        tx.refundRequired(request.receiptId(),request.reason());
                    return new Receipt(Code.OK,request.receiptId(),booking.version());
                }));
    }
    private static PaymentLedger.Entry receipt(PaymentLedger ledger,UUID id) {
        return ledger.entries().stream().filter(e -> e.kind()==PaymentLedger.Kind.RECEIPT && e.id().equals(id)).findFirst()
                .orElseThrow(() -> new com.purpuragenda.domain.DomainFailure("INVALID_REFUND"));
    }
    private Receipt confirm(SessionBinding session,UUID bookingId,String key,Confirmation request,Operation operation,Booking.ConfirmationBasis basis) {
        Objects.requireNonNull(request);
        String fingerprint=CommandKey.fingerprint(List.of(Long.toString(request.expectedVersion()),request.meetingUrl()));
        return unit.execute(new Command(session,bookingId,operation,key,fingerprint),
                tx -> SchedulingCommands.within(tx,() -> {
                    Booking booking=tx.authorizedBooking();
                    require(booking.version()==request.expectedVersion(),"STALE_VERSION");
                    require(booking.state()==Booking.State.PENDING_CONFIRMATION,"INVALID_TRANSITION");
                    if(basis==Booking.ConfirmationBasis.EXTERNAL_PAYMENT)
                        require(tx.ledger().summary(booking.snapshot().service().price()).status()==PaymentLedger.Status.PAID_EXTERNAL,"PAYMENT_INSUFFICIENT");
                    Meeting meeting=Meeting.parse(request.meetingUrl(),meetingHosts);
                    require(!tx.meetingInUse(meeting,booking.id()),"MEETING_IN_USE");
                    Booking confirmed=booking.approve(tx.now(),meeting,basis);
                    tx.save(confirmed);tx.confirmedEffects(booking,confirmed);
                    return new Receipt(Code.OK,confirmed.id(),confirmed.version());
                }));
    }

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
