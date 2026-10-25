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
}
