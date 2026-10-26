package com.purpuragenda.application;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.FinanceUnitOfWork;
import com.purpuragenda.application.port.out.FinanceUnitOfWork.Command;
import com.purpuragenda.application.port.out.FinanceUnitOfWork.Finance;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.*;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.payment.PaymentLedger;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdministratorFinanceTest {
    final Fixture f=new Fixture();
    final AdministratorFinance finance=new AdministratorFinance(f,Set.of("zoom.us"));
    final SessionBinding session=new SessionBinding(UUID.randomUUID(),"c".repeat(64));

    @Test void livePendingReceiptIsAnIndependentFactThatNeverChangesTheBooking() {
        Booking before=f.current;
        Receipt result=finance.recordReceipt(session,before.id(),"key",new AdministratorFinance.ReceiptRequest(new Money(500,"EUR"),"bank","ref-1",f.now.minusSeconds(5)));
        assertTrue(result.success());assertEquals(before.version(),result.version());assertSame(before,f.current);
        assertEquals(1,f.ledger.entries().size());assertEquals(result.entityId(),f.ledger.entries().getFirst().id());
        assertFalse(f.late);assertEquals(List.of("expiry","attempt","authorized","reference","ledger","record"),f.steps);
        assertEquals(FinanceUnitOfWork.Operation.RECORD_RECEIPT,f.command.operation());assertSame(session,f.command.session());
        assertEquals(PaymentLedger.Status.PAID_EXTERNAL,f.ledger.summary(before.snapshot().service().price()).status());
    }

    @Test void receiptsOnTerminalBookingsAreLateAndCarryTheirReconciliationObligation() {
        for(Booking.State state:List.of(Booking.State.CANCELLED,Booking.State.REJECTED,Booking.State.EXPIRED)) {
            f.reset(terminal(state));
            var r=finance.recordReceipt(session,f.current.id(),"k",new AdministratorFinance.ReceiptRequest(new Money(700,"USD"),"bank","late-"+state,f.now.minusSeconds(1)));
            assertTrue(r.success());assertTrue(f.late,state.name());
            assertEquals(Map.of("USD",700L),f.ledger.requireRefunds("X").summary(f.current.snapshot().service().price()).refundDueByCurrency());
        }
    }
    Booking terminal(Booking.State state) {
        Booking value=f.pending(f.now.plusSeconds(7200));
        return switch(state) { case REJECTED -> value.reject(); case CANCELLED -> value.cancel(f.now,false); default -> value.expire(value.expiresAt()); };
    }

    @Test void dueHoldIsReclaimedBeforeTheReceiptSoItIsLateAtExpiryEquality() {
        Booking pending=f.pending(f.now.plusSeconds(7200));f.reset(pending);f.now=pending.expiresAt();
        assertTrue(finance.recordReceipt(session,pending.id(),"k",new AdministratorFinance.ReceiptRequest(new Money(100,"EUR"),"bank","edge",f.now)).success());
        assertEquals(Booking.State.EXPIRED,f.current.state());assertTrue(f.late);assertEquals(1,f.expiryEffects);
        f.reset(pending);f.now=pending.expiresAt().minusNanos(1000);
        assertTrue(finance.recordReceipt(session,pending.id(),"k",new AdministratorFinance.ReceiptRequest(new Money(100,"EUR"),"bank","before",f.now)).success());
        assertFalse(f.late);assertEquals(Booking.State.PENDING_CONFIRMATION,f.current.state());
    }

    @Test void duplicateFutureAndOverflowingFactsAreExpectedFailuresThatRecordNothing() {
        var base=new AdministratorFinance.ReceiptRequest(new Money(100,"EUR"),"bank","dup",f.now);
        assertTrue(finance.recordReceipt(session,f.current.id(),"one",base).success());
        f.referenceUsed=true;
        assertEquals(Receipt.failure(Code.DUPLICATE_PAYMENT),finance.recordReceipt(session,f.current.id(),"two",base));
        f.referenceUsed=false;
        assertEquals(Receipt.failure(Code.INVALID_PAYMENT),finance.recordReceipt(session,f.current.id(),"three",
                new AdministratorFinance.ReceiptRequest(new Money(100,"EUR"),"bank","future",f.now.plusNanos(1000))));
        assertEquals(Receipt.failure(Code.INVALID_PAYMENT),finance.recordReceipt(session,f.current.id(),"four",
                new AdministratorFinance.ReceiptRequest(new Money(Long.MAX_VALUE,"EUR"),"bank","huge",f.now)));
        assertEquals(1,f.ledger.entries().size());assertEquals(1,f.recorded);
    }

    @Test void foreignCurrencyAndLargeAmountsAreRecordedAsActualMoneyWithoutConversion() {
        var big=new AdministratorFinance.ReceiptRequest(new Money(250_000_000L,"JPY"),"bank","jpy",f.now);
        assertTrue(finance.recordReceipt(session,f.current.id(),"k",big).success());
        var status=f.ledger.summary(f.current.snapshot().service().price());
        assertEquals(PaymentLedger.Status.UNPAID,status.status());assertEquals(Map.of("JPY",250_000_000L),status.receivedByCurrency());
    }

    @Test void requestsRejectNonPositiveMissingAndSubMicrosecondFacts() {
        for(Supplier<AdministratorFinance.ReceiptRequest> invalid:List.<Supplier<AdministratorFinance.ReceiptRequest>>of(
                ()->new AdministratorFinance.ReceiptRequest(new Money(0,"EUR"),"bank","r",f.now),
                ()->new AdministratorFinance.ReceiptRequest(null,"bank","r",f.now),
                ()->new AdministratorFinance.ReceiptRequest(new Money(1,"EUR"),"bank","r",null),
                ()->new AdministratorFinance.ReceiptRequest(new Money(1,"EUR"),"","r",f.now),
                ()->new AdministratorFinance.ReceiptRequest(new Money(1,"EUR"),"bank"," ",f.now),
                ()->new AdministratorFinance.ReceiptRequest(new Money(1,"EUR"),"bank","r",f.now.plusNanos(1))))
            assertThrows(DomainFailure.class,invalid::get);
        assertEquals("ReceiptRequest[REDACTED]",new AdministratorFinance.ReceiptRequest(new Money(1,"EUR"),"bank","secret",f.now).toString());
    }

    @Test void fingerprintCoversEveryFinancialFieldButNotTheServerGeneratedEntry() {
        var a=new AdministratorFinance.ReceiptRequest(new Money(100,"EUR"),"bank","r",f.now);
        finance.recordReceipt(session,f.current.id(),"k",a);String first=f.command.fingerprint();
        f.reset(f.current);finance.recordReceipt(session,f.current.id(),"k",a);assertEquals(first,f.command.fingerprint());
        for(var changed:List.of(new AdministratorFinance.ReceiptRequest(new Money(101,"EUR"),"bank","r",f.now),new AdministratorFinance.ReceiptRequest(new Money(100,"USD"),"bank","r",f.now),
                new AdministratorFinance.ReceiptRequest(new Money(100,"EUR"),"other","r",f.now),new AdministratorFinance.ReceiptRequest(new Money(100,"EUR"),"bank","s",f.now),
                new AdministratorFinance.ReceiptRequest(new Money(100,"EUR"),"bank","r",f.now.minusNanos(1000)))) {
            f.reset(f.current);finance.recordReceipt(session,f.current.id(),"k",changed);assertNotEquals(first,f.command.fingerprint());
        }
        assertEquals("FinanceCommand[REDACTED]",f.command.toString());
        assertThrows(NullPointerException.class,()->finance.recordReceipt(session,f.current.id(),"k",null));
        assertThrows(DomainFailure.class,()->new Command(session,null,FinanceUnitOfWork.Operation.RECORD_RECEIPT,"k",first));
    }

    static final String URL="https://zoom.us/j/123456789";
    AdministratorFinance.Confirmation confirmation(String url) { return new AdministratorFinance.Confirmation(f.current.version(),url); }

    @Test void manualApprovalConfirmsAfterVersionStateAndMeetingChecksWithoutReadingPayment() {
        Booking before=f.current;var result=finance.approve(session,before.id(),"k",confirmation(URL));
        assertEquals(new Receipt(Code.OK,before.id(),before.version()+1),result);
        assertEquals(Booking.State.CONFIRMED,f.current.state());assertEquals(Booking.ConfirmationBasis.MANUAL_APPROVAL,f.current.confirmationBasis());
        assertEquals(List.of("expiry","attempt","authorized","meeting","save","confirm-effects"),f.steps);assertEquals(1,f.confirmations);
        assertEquals(FinanceUnitOfWork.Operation.APPROVE_BOOKING,f.command.operation());assertEquals(0,f.recorded);
    }

    @Test void paymentConfirmationRequiresSufficientNetEurWithNoRefundDueAndRecordsItsBasis() {
        Booking before=f.current;long price=before.snapshot().service().price().minor();
        assertEquals(Receipt.failure(Code.PAYMENT_INSUFFICIENT),finance.confirmExternalPayment(session,before.id(),"k",confirmation(URL)));
        for(var entry:List.of(entry(before,price-1,"EUR","a"),entry(before,price*5,"USD","b"))) f.ledger=f.ledger.append(entry);
        assertEquals(Receipt.failure(Code.PAYMENT_INSUFFICIENT),finance.confirmExternalPayment(session,before.id(),"k",confirmation(URL)));
        f.ledger=f.ledger.append(entry(before,1,"EUR","c"));
        var result=finance.confirmExternalPayment(session,before.id(),"k",confirmation(URL));
        assertTrue(result.success());assertEquals(Booking.ConfirmationBasis.EXTERNAL_PAYMENT,f.current.confirmationBasis());
        assertEquals(3,f.ledger.entries().size());
        f.reset(before);f.ledger=f.ledger.append(entry(before,price,"EUR","d")).requireRefunds("X");
        assertEquals(Receipt.failure(Code.PAYMENT_INSUFFICIENT),finance.confirmExternalPayment(session,before.id(),"k",confirmation(URL)));
        assertEquals(Booking.State.PENDING_CONFIRMATION,f.current.state());
    }
    PaymentLedger.Entry entry(Booking b,long minor,String currency,String reference) {
        return new PaymentLedger.Entry(UUID.randomUUID(),b.id(),PaymentLedger.Kind.RECEIPT,new Money(minor,currency),"bank",reference,f.now,null);
    }

    @Test void staleTerminalExpiredInvalidAndSharedMeetingsAreExpectedFailuresWithoutEffects() {
        Booking before=f.current;
        assertEquals(Receipt.failure(Code.STALE_VERSION),finance.approve(session,before.id(),"k",new AdministratorFinance.Confirmation(before.version()+1,URL)));
        assertEquals(Receipt.failure(Code.INVALID_MEETING),finance.approve(session,before.id(),"k",confirmation("http://zoom.us/j/123456789")));
        f.meetingInUse=true;assertEquals(Receipt.failure(Code.MEETING_IN_USE),finance.approve(session,before.id(),"k",confirmation(URL)));
        assertSame(before,f.current);assertEquals(0,f.confirmations);
        f.reset(terminal(Booking.State.CANCELLED));
        assertEquals(Receipt.failure(Code.INVALID_TRANSITION),finance.approve(session,f.current.id(),"k",confirmation(URL)));
        Booking pending=f.pending(f.now.plusSeconds(7200));f.reset(pending);f.now=pending.expiresAt();
        // Reclamation itself advanced the version, so the reviewed version is stale; the expired hold never revives.
        assertEquals(Receipt.failure(Code.STALE_VERSION),finance.approve(session,pending.id(),"k",confirmation(URL)));
        assertEquals(Receipt.failure(Code.INVALID_TRANSITION),finance.approve(session,pending.id(),"k2",confirmation(URL)));
        assertEquals(Booking.State.EXPIRED,f.current.state());assertEquals(1,f.expiryEffects);assertEquals(0,f.confirmations);
    }

    @Test void configuredLocalDomainAndConfirmationShapeAreValidated() {
        var local=new AdministratorFinance(f,Set.of("zoom.us","meet.example.test"));
        assertTrue(local.approve(session,f.current.id(),"k",confirmation("https://meet.example.test/j/123456789")).success());
        f.reset(f.current);
        assertThrows(DomainFailure.class,()->new AdministratorFinance(f,Set.of()));
        assertThrows(DomainFailure.class,()->new AdministratorFinance(f,null));
        assertThrows(DomainFailure.class,()->new AdministratorFinance.Confirmation(0,URL));
        assertThrows(DomainFailure.class,()->new AdministratorFinance.Confirmation(1,null));
        assertEquals("Confirmation[REDACTED]",confirmation(URL).toString());
        assertThrows(NullPointerException.class,()->finance.approve(session,f.current.id(),"k",null));
        String first=capture(confirmation(URL));assertNotEquals(first,capture(new AdministratorFinance.Confirmation(f.current.version()+1,URL)));
        assertNotEquals(first,capture(confirmation("https://zoom.us/j/123456780")));
    }
    String capture(AdministratorFinance.Confirmation request) { finance.approve(session,f.current.id(),"k",request);return f.command.fingerprint(); }

    static class Fixture extends PublicSchedulingTest.Fixture implements FinanceUnitOfWork,Finance {
        Booking current;Command command;PaymentLedger ledger=PaymentLedger.empty();boolean late,referenceUsed,attempting,meetingInUse;int confirmations;
        int recorded,expiryEffects;final List<String> steps=new ArrayList<>();
        Fixture(){current=pending(now.plusSeconds(7200));}
        void reset(Booking value){current=value;ledger=PaymentLedger.empty();late=referenceUsed=attempting=meetingInUse=false;recorded=expiryEffects=confirmations=0;steps.clear();now=Instant.parse("2026-01-01T00:00:00Z");}
        public Receipt execute(Command supplied,Function<Finance,Receipt> work){command=supplied;return work.apply(this);}
        public Optional<Booking> booking(UUID id){return Optional.of(current);}
        public Booking authorizedBooking(){steps.add("authorized");assertTrue(attempting);return current;}
        public PaymentLedger ledger(){steps.add("ledger");return ledger;}
        public boolean referenceUsed(PaymentLedger.Kind kind,String method,String reference){steps.add("reference");return referenceUsed;}
        public void receiptRecorded(PaymentLedger.Entry entry,boolean isLate){steps.add("record");recorded++;late=isLate;ledger=ledger.append(entry);if(isLate)ledger=ledger.requireRefund(entry.id(),"LATE_RECEIPT");}
        public boolean meetingInUse(Meeting meeting,UUID booking){steps.add("meeting");return meetingInUse;}
        public void confirmedEffects(Booking original,Booking confirmed){steps.add("confirm-effects");confirmations++;}
        public void save(Booking value){steps.add(attempting?"save":"reclaim");current=value;}
        public List<Booking> duePending(){steps.add("expiry");return current.state()==Booking.State.PENDING_CONFIRMATION&&!now.isBefore(current.expiresAt())?List.of(current):List.of();}
        public void expiryEffects(UUID id,long version){expiryEffects++;}
        public Receipt attempt(Supplier<Receipt> mutation){steps.add("attempt");attempting=true;try{return mutation.get();}finally{attempting=false;}}
        public com.purpuragenda.application.scheduling.CustomerQuota quota(UUID customer){throw new AssertionError();}
        public boolean chargeCreated(Booking value){throw new AssertionError();}
    }
}
