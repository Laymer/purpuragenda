package com.purpuragenda.domain.payment;

import static com.purpuragenda.domain.DomainFailure.require;
import com.purpuragenda.domain.booking.Money;
import com.purpuragenda.domain.customer.Input;
import java.time.Instant;
import java.util.*;

/** Append-only external financial facts; booking state never proves receipt of money. */
public record PaymentLedger(List<Entry> entries, List<Obligation> obligations) {
    public enum Kind { RECEIPT, REFUND }
    public enum Status { UNPAID, PAID_EXTERNAL, REFUND_DUE, REFUNDED_EXTERNAL }
    public record Entry(UUID id, UUID bookingId, Kind kind, Money amount, String method, String reference,
                        Instant verifiedAt, UUID receiptId) {
        public Entry {
            require(id != null && bookingId != null && kind != null && amount != null && amount.minor() > 0 && verifiedAt != null, "INVALID_PAYMENT");
            method = Input.text(method, 1, 80); reference = Input.text(reference, 1, 120);
            require((kind == Kind.REFUND) == (receiptId != null), "INVALID_PAYMENT");
        }
    }
    public record Obligation(UUID receiptId, long dueMinor, String reason) {
        public Obligation { require(receiptId != null && dueMinor > 0, "INVALID_REFUND"); reason = Input.text(reason, 1, 120); }
    }
    public record Summary(Status status, long receivedMinor, long refundedMinor, long remainingMinor,
                          Map<String, Long> refundDueByCurrency, Map<String, Long> receivedByCurrency) {}
    public PaymentLedger {
        entries = List.copyOf(entries); obligations = List.copyOf(obligations);
        require(entries.stream().map(Entry::id).distinct().count() == entries.size(), "DUPLICATE_PAYMENT");
        require(entries.stream().map(e -> e.kind+"|"+e.method+"|"+e.reference).distinct().count() == entries.size(), "DUPLICATE_PAYMENT");
        for (Entry entry : entries) if (entry.kind == Kind.REFUND) {
            Entry receipt = entries.stream().filter(r -> r.id.equals(entry.receiptId) && r.kind == Kind.RECEIPT).findFirst().orElseThrow(() -> new com.purpuragenda.domain.DomainFailure("INVALID_REFUND"));
            require(receipt.bookingId.equals(entry.bookingId) && receipt.amount.currency().equals(entry.amount.currency())
                    && refunded(receipt.id, entries) <= receipt.amount.minor(), "REFUND_EXCEEDS_RECEIPT");
        }
        require(obligations.stream().map(Obligation::receiptId).distinct().count() == obligations.size(), "INVALID_REFUND");
        for (Obligation obligation : obligations) require(entries.stream().anyMatch(e -> e.kind == Kind.RECEIPT && e.id.equals(obligation.receiptId) && obligation.dueMinor <= e.amount.minor()), "INVALID_REFUND");
    }
    public static PaymentLedger empty() { return new PaymentLedger(List.of(),List.of()); }
    public PaymentLedger append(Entry entry) {
        List<Entry> all = new ArrayList<>(entries); all.add(entry); return new PaymentLedger(all,obligations);
    }
    public PaymentLedger requireRefunds(String reason) {
        List<Obligation> due = new ArrayList<>(obligations);
        for (Entry entry : entries) if (entry.kind == Kind.RECEIPT && due.stream().noneMatch(o -> o.receiptId.equals(entry.id))) due.add(new Obligation(entry.id,entry.amount.minor(),reason));
        return new PaymentLedger(entries,due);
    }
    public PaymentLedger requireRefund(UUID receiptId,String reason) {
        var receipt=entries.stream().filter(e->e.id.equals(receiptId)&&e.kind==Kind.RECEIPT).findFirst().orElseThrow(()->new com.purpuragenda.domain.DomainFailure("INVALID_REFUND"));
        if(obligations.stream().anyMatch(o->o.receiptId.equals(receiptId)))return this;
        List<Obligation> due=new ArrayList<>(obligations);due.add(new Obligation(receiptId,receipt.amount.minor(),reason));return new PaymentLedger(entries,due);
    }
    public boolean sufficient(Money price) { return entries.stream().anyMatch(e->e.kind==Kind.RECEIPT&&e.amount.currency().equals(price.currency())) && net(price.currency()) >= price.minor(); }
    public long net(String currency) { return Math.subtractExact(received(currency), refunds(currency)); }
    public Summary summary(Money price) {
        Map<String,Long> due = new TreeMap<>(), received = new TreeMap<>();
        for (Entry entry : entries) if (entry.kind == Kind.RECEIPT) received.merge(entry.amount.currency(),entry.amount.minor(),Math::addExact);
        for (Obligation obligation : obligations) {
            Entry receipt = entries.stream().filter(e -> e.id.equals(obligation.receiptId)).findFirst().orElseThrow();
            long outstanding = Math.max(0,obligation.dueMinor-refunded(receipt.id,entries));
            if (outstanding > 0) due.merge(receipt.amount.currency(),outstanding,Math::addExact);
        }
        long receivedMinor = received(price.currency()), refundedMinor = refunds(price.currency());
        Status status = !due.isEmpty() ? Status.REFUND_DUE : !obligations.isEmpty() ? Status.REFUNDED_EXTERNAL : sufficient(price) ? Status.PAID_EXTERNAL : Status.UNPAID;
        return new Summary(status,receivedMinor,refundedMinor,Math.max(0,price.minor()-net(price.currency())),Map.copyOf(due),Map.copyOf(received));
    }
    private long received(String currency) { return entries.stream().filter(e -> e.kind==Kind.RECEIPT && e.amount.currency().equals(currency)).mapToLong(e -> e.amount.minor()).reduce(0,Math::addExact); }
    private long refunds(String currency) { return entries.stream().filter(e -> e.kind==Kind.REFUND && e.amount.currency().equals(currency)).mapToLong(e -> e.amount.minor()).reduce(0,Math::addExact); }
    private static long refunded(UUID receipt, List<Entry> entries) { return entries.stream().filter(e -> e.kind==Kind.REFUND && receipt.equals(e.receiptId)).mapToLong(e -> e.amount.minor()).reduce(0,Math::addExact); }
}
