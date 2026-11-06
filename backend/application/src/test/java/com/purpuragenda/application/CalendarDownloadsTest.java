package com.purpuragenda.application;

import com.purpuragenda.application.calendar.*;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.AdministratorBookingQuery.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.payment.PaymentLedger;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CalendarDownloadsTest {
    final CustomerBookingChangesTest.Fixture f=new CustomerBookingChangesTest.Fixture();
    final SessionBinding session=new SessionBinding(UUID.randomUUID(),"a".repeat(64));
    CalendarEvent seen;Instant stamp;boolean missing;
    AdministratorBookingQuery owners(Booking booking,Instant now) {
        return new AdministratorBookingQuery() {
            public Page agenda(SessionBinding s,Window w,Cursor c,int limit) { throw new AssertionError("No history export"); }
            public Optional<Detail> detail(SessionBinding s,UUID id) {
                assertSame(session,s);assertEquals(booking.id(),id);return missing?Optional.empty():Optional.of(new Detail(booking,PaymentLedger.empty(),now));
            }
        };
    }
    CalendarDownloads downloads(Booking booking,Instant now) {
        return new CalendarDownloads((s,id)->{
            assertSame(session,s);assertEquals(booking.id(),id);return missing?Optional.empty():Optional.of(new CustomerBookingQuery.Authorized(booking,PaymentLedger.empty(),now));
        },owners(booking,now),(event,time)->{seen=event;stamp=time;return new byte[]{1,2};});
    }
    @Test void fixedAudienceAndDatabaseTimestampReachWriterOnlyAfterAuthorization() {
        var query=downloads(f.current,f.now);
        assertArrayEquals(new byte[]{1,2},query.customer(session,f.current.id()).orElseThrow());
        assertEquals(CalendarEvent.Audience.CUSTOMER,seen.audience());assertEquals(f.now,stamp);
        assertArrayEquals(new byte[]{1,2},query.owner(session,f.current.id()).orElseThrow());assertEquals(CalendarEvent.Audience.OWNER,seen.audience());
        missing=true;seen=null;
        assertTrue(query.customer(session,f.current.id()).isEmpty());assertTrue(query.owner(session,f.current.id()).isEmpty());assertNull(seen);
    }
    @Test void elapsedPendingHoldIsCancelledAtEqualityWithoutInventingPersistedRevision() {
        Booking pending=f.pending(f.current.start());
        for(long nanos:new long[]{-1000,0,1000}) {
            var now=pending.expiresAt().plusNanos(nanos);var query=downloads(pending,now);
            query.customer(session,pending.id());assertEquals(nanos<0?CalendarEvent.Status.TENTATIVE:CalendarEvent.Status.CANCELLED,seen.status());
            query.owner(session,pending.id());assertEquals(nanos<0?CalendarEvent.Status.TENTATIVE:CalendarEvent.Status.CANCELLED,seen.status());
            assertEquals(pending.calendarRevision(),seen.revision());assertEquals(now,stamp);
        }
        assertEquals(Booking.State.PENDING_CONFIRMATION,pending.state());assertEquals(1,pending.version());
    }
    @Test void nullAuthorityAndTargetNeverReachQueries() {
        var query=downloads(f.current,f.now);
        assertThrows(NullPointerException.class,()->query.customer(null,f.current.id()));
        assertThrows(NullPointerException.class,()->query.owner(null,f.current.id()));
        assertThrows(NullPointerException.class,()->query.customer(session,null));
        assertThrows(NullPointerException.class,()->query.owner(session,null));assertNull(seen);
        assertThrows(NullPointerException.class,()->new CalendarDownloads(null,owners(f.current,f.now),(e,t)->new byte[0]));
        assertThrows(NullPointerException.class,()->new CalendarDownloads((s,id)->Optional.empty(),null,(e,t)->new byte[0]));
        assertThrows(NullPointerException.class,()->new CalendarDownloads((s,id)->Optional.empty(),owners(f.current,f.now),null));
    }
}
