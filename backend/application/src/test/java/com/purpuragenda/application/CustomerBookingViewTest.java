package com.purpuragenda.application;

import com.purpuragenda.application.port.out.CustomerBookingQuery.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.scheduling.CustomerBookingView;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.payment.PaymentLedger;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CustomerBookingViewTest {
    final CustomerBookingChangesTest.Fixture f=new CustomerBookingChangesTest.Fixture();
    final SessionBinding session=new SessionBinding(UUID.randomUUID(),"b".repeat(64));
    CustomerBookingView.View view(Booking b,Instant now,PaymentLedger money) {
        return new CustomerBookingView((s,id)->{
            assertSame(session,s);assertEquals(b.id(),id);return Optional.of(new Authorized(b,money,now));
        }).find(session,b.id()).orElseThrow();
    }
    @Test void viewUsesOriginalSnapshotsActualSessionDurationAndSeparateMoney() {
        Booking b=f.current.deliveryContact("delivery@example.test");
        var v=view(b,f.now,PaymentLedger.empty());
        assertEquals(b.version(),v.version());assertEquals(b.reference(),v.reference());
        assertEquals(b.snapshot().service().name(),v.service().name());assertEquals(b.snapshot().service().durationMinutes(),v.service().durationMinutes());
        assertEquals(b.snapshot().service().price(),v.service().price());assertEquals(b.sessionEnd(),v.sessionEnd());
        assertEquals(b.start(),v.start());assertEquals(b.snapshot().displayZone(),v.displayZone());
        assertEquals(b.deliveryEmail(),v.deliveryEmail());assertEquals(b.meeting().participantUrl(),v.meetingUrl());
        assertEquals(PaymentLedger.Status.UNPAID,v.payment().status());assertFalse(v.pendingExpired());
        assertEquals(f.now,v.observedAt());assertEquals(b.start().minus(Duration.ofHours(24)),v.customerChangesUntil());
        assertEquals("CustomerBookingView[REDACTED]",v.toString());
        assertEquals("AuthorizedBooking[REDACTED]",new Authorized(b,PaymentLedger.empty(),f.now).toString());
        assertThrows(UnsupportedOperationException.class,()->v.allowedActions().clear());
    }
    @Test void cutoffAndExpiryAreInclusiveAndStrictRespectivelyWithoutChangingStoredVersion() {
        Booking b=f.current;
        for(long micros:new long[]{-1,0,1}) {
            var v=view(b,b.start().minus(Duration.ofHours(24)).plusNanos(micros*1000),PaymentLedger.empty());
            assertEquals(micros<=0?Set.of(CustomerBookingView.Action.CANCEL,CustomerBookingView.Action.RESCHEDULE):Set.of(),v.allowedActions());
        }
        Booking pending=f.pending(b.start());
        for(long micros:new long[]{-1,0,1}) {
            var v=view(pending,pending.expiresAt().plusNanos(micros*1000),PaymentLedger.empty());
            assertEquals(micros>=0,v.pendingExpired());assertEquals(pending.version(),v.version());
            assertEquals(Booking.State.PENDING_CONFIRMATION,v.state());assertEquals(pending.expiresAt(),v.pendingExpiresAt());
            assertNull(v.meetingUrl());if(micros>=0)assertTrue(v.allowedActions().isEmpty());
        }
    }
    @Test void terminalStatesExposeNoMutationOrMeetingAndRefundFactsRemainIndependent() {
        Booking b=f.current,p=f.pending(b.start());
        for(Booking terminal:List.of(b.cancel(f.now,false),p.reject(),p.expire(p.expiresAt()),
                b.outcome(b.sessionEnd(),Booking.State.COMPLETED,""),b.outcome(b.sessionEnd(),Booking.State.NO_SHOW,""))) {
            var v=view(terminal,f.now,PaymentLedger.empty());assertTrue(v.allowedActions().isEmpty());assertNull(v.meetingUrl());assertFalse(v.pendingExpired());
        }
        var receipt=new PaymentLedger.Entry(UUID.randomUUID(),b.id(),PaymentLedger.Kind.RECEIPT,new Money(1500,"EUR"),"SYNTHETIC","synthetic-reference",f.now,null);
        var ledger=PaymentLedger.empty().append(receipt).requireRefunds("synthetic-cancel");
        var v=view(b.cancel(f.now,false),f.now,ledger);
        assertEquals(PaymentLedger.Status.REFUND_DUE,v.payment().status());assertEquals(Map.of("EUR",1500L),v.payment().refundDueByCurrency());
    }
    @Test void missingAuthorityAndInvalidInputsNeverProduceAView() {
        var query=new CustomerBookingView((s,id)->Optional.empty());
        assertTrue(query.find(session,UUID.randomUUID()).isEmpty());
        assertThrows(NullPointerException.class,()->query.find(null,UUID.randomUUID()));
        assertThrows(NullPointerException.class,()->query.find(session,null));
        assertThrows(NullPointerException.class,()->new CustomerBookingView(null));
        assertThrows(NullPointerException.class,()->new Authorized(null,PaymentLedger.empty(),f.now));
        assertThrows(NullPointerException.class,()->new Authorized(f.current,null,f.now));
        assertThrows(NullPointerException.class,()->new Authorized(f.current,PaymentLedger.empty(),null));
    }
}
