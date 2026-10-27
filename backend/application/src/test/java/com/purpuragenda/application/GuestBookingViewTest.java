package com.purpuragenda.application;

import com.purpuragenda.application.port.out.CustomerBookingQuery.Authorized;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.scheduling.GuestBookingView;
import com.purpuragenda.domain.payment.PaymentLedger;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuestBookingViewTest {
    @Test void derivesOnlyTheAuthoritativeTargetAndUsesTheCustomerProjection() {
        var fixture=new CustomerBookingChangesTest.Fixture();
        var session=new SessionBinding(UUID.randomUUID(),"b".repeat(64));
        var useCase=new GuestBookingView(binding->{
            assertSame(session,binding);
            return Optional.of(new Authorized(fixture.current,PaymentLedger.empty(),fixture.now));
        });
        var view=useCase.find(session).orElseThrow();
        assertEquals(fixture.current.id(),view.id());assertEquals(fixture.current.reference(),view.reference());
        assertEquals(fixture.current.snapshot().service().price(),view.service().price());
        assertEquals(PaymentLedger.Status.UNPAID,view.payment().status());
        assertEquals("CustomerBookingView[REDACTED]",view.toString());
    }
    @Test void absentScopeCannotFallBackToHistory() {
        var useCase=new GuestBookingView(binding->Optional.empty());
        assertTrue(useCase.find(new SessionBinding(UUID.randomUUID(),"b".repeat(64))).isEmpty());
        assertThrows(NullPointerException.class,()->useCase.find(null));
        assertThrows(NullPointerException.class,()->new GuestBookingView(null));
    }
}
