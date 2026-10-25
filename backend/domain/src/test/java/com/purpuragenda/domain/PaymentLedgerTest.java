package com.purpuragenda.domain;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.payment.PaymentLedger;
import com.purpuragenda.domain.payment.PaymentLedger.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaymentLedgerTest {
    final UUID booking=UUID.randomUUID();
    Entry receipt(long amount,String currency,String reference) { return new Entry(UUID.randomUUID(),booking,Kind.RECEIPT,new Money(amount,currency),"BANK",reference,Instant.parse("2027-01-10T09:00:00Z"),null); }
    @Test void partialMoneyCurrencyAndApprovalNeverInventPayment() {
        var empty=PaymentLedger.empty();assertEquals(Status.UNPAID,empty.summary(Money.eur(1500)).status());
        var usd=empty.append(receipt(1500,"USD","usd1"));assertFalse(usd.sufficient(Money.eur(1500)));
        var partial=usd.append(receipt(500,"EUR","eur1"));assertEquals(1000,partial.summary(Money.eur(1500)).remainingMinor());assertEquals(Status.UNPAID,partial.summary(Money.eur(1500)).status());
        var paid=partial.append(receipt(1000,"EUR","eur2"));assertEquals(Status.PAID_EXTERNAL,paid.summary(Money.eur(1500)).status());
        var due=paid.requireRefunds("CANCELLED");assertEquals(Status.REFUND_DUE,due.summary(Money.eur(1500)).status());assertEquals(Map.of("EUR",1500L,"USD",1500L),due.summary(Money.eur(1500)).refundDueByCurrency());
    }
    @Test void refundsCannotExceedReceiptsAndLateReceiptsCreateNewFollowup() {
        Entry r=receipt(1500,"EUR","ref1");var ledger=PaymentLedger.empty().append(r).requireRefunds("EXPIRED");
        Entry refund=new Entry(UUID.randomUUID(),booking,Kind.REFUND,Money.eur(1500),"BANK","refund1",r.verifiedAt(),r.id());
        var settled=ledger.append(refund);assertEquals(Status.REFUNDED_EXTERNAL,settled.summary(Money.eur(1500)).status());
        assertThrows(DomainFailure.class,()->settled.append(new Entry(UUID.randomUUID(),booking,Kind.REFUND,Money.eur(1),"BANK","refund2",r.verifiedAt(),r.id())));
        assertEquals(Status.REFUND_DUE,settled.append(receipt(1,"EUR","late1")).requireRefunds("LATE_RECEIPT").summary(Money.eur(1500)).status());
        assertThrows(DomainFailure.class,()->ledger.append(r));
        assertThrows(DomainFailure.class,()->ledger.append(receipt(1500,"EUR","ref1")));
    }
    @Test void lateMoneyDoesNotMakeEarlierNonrefundableMoneyRefundableAndZeroPriceDoesNotInventReceipt(){
        assertEquals(Status.UNPAID,PaymentLedger.empty().summary(Money.eur(0)).status());
        Entry original=receipt(1500,"EUR","nonrefundable"),late=receipt(100,"EUR","late");
        var ledger=PaymentLedger.empty().append(original).append(late).requireRefund(late.id(),"LATE_RECEIPT");
        assertEquals(Map.of("EUR",100L),ledger.summary(Money.eur(1500)).refundDueByCurrency());
        assertEquals(1,ledger.requireRefund(late.id(),"LATE_RECEIPT").obligations().size());
    }

    Entry refund(Entry receipt,long amount,String reference) {
        return new Entry(UUID.randomUUID(),booking,Kind.REFUND,new Money(amount,receipt.amount().currency()),"BANK",reference,receipt.verifiedAt(),receipt.id());
    }
    @Test void settledLateRefundKeepsEarlierRetainedMoneyPaid() {
        var original=receipt(1500,"EUR","original");var late=receipt(100,"EUR","late-extra");
        var ledger=PaymentLedger.empty().append(original).append(late).requireRefund(late.id(),"LATE_RECEIPT");
        assertEquals(Status.REFUND_DUE,ledger.summary(Money.eur(1500)).status());
        var settled=ledger.append(refund(late,100,"late-return"));
        assertEquals(Status.PAID_EXTERNAL,settled.summary(Money.eur(1500)).status());
        assertEquals(1500,settled.net("EUR"));assertTrue(settled.summary(Money.eur(1500)).refundDueByCurrency().isEmpty());
    }
    @Test void partialRefundsAndCurrenciesRemainIndependent() {
        var eur=receipt(2000,"EUR","eur");var usd=receipt(1000,"USD","usd");
        var ledger=PaymentLedger.empty().append(eur).append(usd).requireRefunds("CANCELLED");
        var partial=ledger.append(refund(eur,500,"partial"));
        assertEquals(Map.of("EUR",1500L,"USD",1000L),partial.summary(Money.eur(1500)).refundDueByCurrency());
        assertEquals(2000,partial.summary(Money.eur(1500)).receivedMinor());
        assertEquals(500,partial.summary(Money.eur(1500)).refundedMinor());
        assertEquals(Map.of("EUR",2000L,"USD",1000L),partial.summary(Money.eur(1500)).receivedByCurrency());
        var euroReturned=partial.append(refund(eur,1500,"euro-rest"));
        assertEquals(Status.REFUND_DUE,euroReturned.summary(Money.eur(1500)).status());
        var settled=euroReturned.append(refund(usd,1000,"dollars-rest"));
        assertEquals(Status.REFUNDED_EXTERNAL,settled.summary(Money.eur(1500)).status());
        assertEquals(1500,settled.summary(Money.eur(1500)).remainingMinor());
        var noObligation=PaymentLedger.empty().append(eur).append(refund(eur,2000,"voluntary"));
        assertEquals(Status.REFUNDED_EXTERNAL,noObligation.summary(Money.eur(0)).status());
    }
    @Test void ledgerNeverCombinesDifferentBookingsOrDelimiterCollisions() {
        var receipt=receipt(1500,"EUR","same");
        var other=new Entry(UUID.randomUUID(),UUID.randomUUID(),Kind.RECEIPT,Money.eur(1500),"BANK","other",receipt.verifiedAt(),null);
        assertThrows(DomainFailure.class,()->PaymentLedger.empty().append(receipt).append(other));
        var first=new Entry(UUID.randomUUID(),booking,Kind.RECEIPT,Money.eur(1),"BANK|a","b",receipt.verifiedAt(),null);
        var second=new Entry(UUID.randomUUID(),booking,Kind.RECEIPT,Money.eur(1),"BANK","a|b",receipt.verifiedAt(),null);
        assertEquals(2,PaymentLedger.empty().append(first).append(second).net("EUR"));
        var list=new ArrayList<>(List.of(receipt));var ledger=new PaymentLedger(list,List.of());list.clear();
        assertEquals(1,ledger.entries().size());assertThrows(UnsupportedOperationException.class,()->ledger.entries().clear());
        assertThrows(UnsupportedOperationException.class,()->ledger.summary(Money.eur(1500)).receivedByCurrency().clear());
    }
    @Test void receiptAmountsAreActualFactsRatherThanServicePriceBounds() {
        var large=receipt(100_000_001,"EUR","large");
        assertEquals(100_000_001,PaymentLedger.empty().append(large).summary(Money.eur(1500)).receivedMinor());
        var max=receipt(Long.MAX_VALUE,"EUR","maximum");
        var overflow=PaymentLedger.empty().append(max).append(receipt(1,"EUR","one-more"));
        assertThrows(ArithmeticException.class,()->overflow.net("EUR"));
    }
    @Test void malformedEntriesAndRefundObligationsAreRejected() {
        var r=receipt(1500,"EUR","valid");
        assertThrows(DomainFailure.class,()->new Entry(null,booking,Kind.RECEIPT,Money.eur(1),"BANK","r",r.verifiedAt(),null));
        assertThrows(DomainFailure.class,()->new Entry(UUID.randomUUID(),null,Kind.RECEIPT,Money.eur(1),"BANK","r",r.verifiedAt(),null));
        assertThrows(DomainFailure.class,()->new Entry(UUID.randomUUID(),booking,null,Money.eur(1),"BANK","r",r.verifiedAt(),null));
        assertThrows(DomainFailure.class,()->new Entry(UUID.randomUUID(),booking,Kind.RECEIPT,null,"BANK","r",r.verifiedAt(),null));
        assertThrows(DomainFailure.class,()->new Entry(UUID.randomUUID(),booking,Kind.RECEIPT,Money.eur(0),"BANK","r",r.verifiedAt(),null));
        assertThrows(DomainFailure.class,()->new Entry(UUID.randomUUID(),booking,Kind.RECEIPT,Money.eur(1),"BANK","r",null,null));
        assertThrows(DomainFailure.class,()->new Entry(UUID.randomUUID(),booking,Kind.RECEIPT,Money.eur(1),"BANK","r",r.verifiedAt(),r.id()));
        assertThrows(DomainFailure.class,()->new Entry(UUID.randomUUID(),booking,Kind.REFUND,Money.eur(1),"BANK","r",r.verifiedAt(),null));
        assertThrows(DomainFailure.class,()->new Obligation(null,1,"reason"));
        assertThrows(DomainFailure.class,()->new Obligation(r.id(),0,"reason"));
        assertThrows(DomainFailure.class,()->new Obligation(r.id(),1,""));
        assertThrows(DomainFailure.class,()->PaymentLedger.empty().requireRefund(UUID.randomUUID(),"UNKNOWN"));
        assertThrows(DomainFailure.class,()->PaymentLedger.empty().append(refund(r,1,"missing")));
        var ledger=PaymentLedger.empty().append(r);
        var wrongCurrency=new Entry(UUID.randomUUID(),booking,Kind.REFUND,new Money(1,"USD"),"BANK","wrong-currency",r.verifiedAt(),r.id());
        assertThrows(DomainFailure.class,()->ledger.append(wrongCurrency));
        assertThrows(DomainFailure.class,()->new PaymentLedger(List.of(r),List.of(new Obligation(r.id(),1501,"TOO_MUCH"))));
        assertThrows(DomainFailure.class,()->new PaymentLedger(List.of(r),List.of(new Obligation(UUID.randomUUID(),1,"UNKNOWN"))));
        assertThrows(DomainFailure.class,()->new PaymentLedger(List.of(r),List.of(new Obligation(r.id(),1,"FIRST"),new Obligation(r.id(),1,"SECOND"))));
        assertEquals(1,ledger.requireRefunds("CANCELLED").requireRefunds("REPLAY").obligations().size());
    }
}
