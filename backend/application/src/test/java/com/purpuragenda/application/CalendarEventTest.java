package com.purpuragenda.application;

import com.purpuragenda.application.calendar.CalendarEvent;
import com.purpuragenda.application.calendar.CalendarEvent.*;
import com.purpuragenda.domain.booking.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CalendarEventTest {
    final CustomerBookingChangesTest.Fixture f=new CustomerBookingChangesTest.Fixture();

    @Test void everyBookingStateMapsWithoutInferringPaymentOrChangingRevision() {
        Booking confirmed=f.current, pending=f.pending(confirmed.start());
        var cases=new LinkedHashMap<Booking,Status>();
        cases.put(pending,Status.TENTATIVE);
        cases.put(confirmed,Status.CONFIRMED);
        cases.put(pending.reject(),Status.CANCELLED);
        cases.put(pending.expire(pending.expiresAt()),Status.CANCELLED);
        cases.put(confirmed.cancel(f.now,false),Status.CANCELLED);
        cases.put(confirmed.outcome(confirmed.sessionEnd(),Booking.State.COMPLETED,""),Status.CONFIRMED);
        cases.put(confirmed.outcome(confirmed.sessionEnd(),Booking.State.NO_SHOW,""),Status.CONFIRMED);
        for(var entry:cases.entrySet())for(Audience audience:Audience.values()) {
            Booking booking=entry.getKey();var event=CalendarEvent.from(booking,audience);
            assertEquals(entry.getValue(),event.status());assertEquals(booking.id(),event.bookingId());
            assertEquals(audience,event.audience());assertEquals(booking.calendarRevision(),event.revision());
            assertEquals(booking.start(),event.start());assertEquals(booking.sessionEnd(),event.end());
            assertEquals(booking.state()==Booking.State.CONFIRMED,event.meetingUrl().isPresent());
            assertEquals("CalendarEvent[REDACTED]",event.toString());
        }
    }

    @Test void revisionsComeFromPersistedCalendarHistoryRatherThanBookingVersion() {
        Booking moved=f.current.reschedule(f.now,f.current.start().plusSeconds(3600),false)
                .updateMeeting(Meeting.parse("https://zoom.us/j/987654321",Set.of("zoom.us")))
                .deliveryContact("changed@example.test");
        var event=CalendarEvent.from(moved,Audience.CUSTOMER);
        assertEquals(2,event.revision());assertEquals(4,moved.version());
        assertEquals("https://zoom.us/j/987654321",event.meetingUrl().orElseThrow());
        assertEquals(3,CalendarEvent.from(moved.cancel(f.now,false),Audience.OWNER).revision());
        assertThrows(NullPointerException.class,()->CalendarEvent.from(null,Audience.CUSTOMER));
        assertThrows(NullPointerException.class,()->CalendarEvent.from(moved,null));
    }

    @Test void pendingMeetingAndErasedTerminalMeetingAreNotExported() {
        var pending=f.pending(f.current.start()).updateMeeting(f.current.meeting());
        assertTrue(CalendarEvent.from(pending,Audience.OWNER).meetingUrl().isEmpty());
        var b=f.current.cancel(f.now,false);
        var erased=new Booking(b.id(),b.reference(),b.customerId(),b.snapshot(),b.deliveryEmail(),b.state(),b.origin(),
                b.createdAt(),b.start(),b.expiresAt(),null,b.confirmationBasis(),b.version(),b.calendarRevision(),true,true);
        assertTrue(CalendarEvent.from(erased,Audience.CUSTOMER).meetingUrl().isEmpty());
    }

    @Test void bothServiceDurationsRemainElapsedAcrossBrusselsGapAndOverlap() {
        for(int minutes:List.of(30,60))for(String start:List.of("2026-03-29T00:45:00Z","2026-10-25T00:45:00Z")) {
            f.service=new ServiceDefinition(f.service.id(),1,"Synthetic",minutes,new Money(1500,"EUR"),true);
            Booking booking=f.confirmed(Instant.parse(start));
            var event=CalendarEvent.from(booking,Audience.CUSTOMER);
            assertEquals(Duration.ofMinutes(minutes),Duration.between(event.start(),event.end()));
            assertNotEquals(booking.reservedEnd(),event.end());
            ZoneId zone=ZoneId.of("Europe/Brussels");
            assertNotEquals(event.start().atZone(zone).getOffset(),event.end().atZone(zone).getOffset());
        }
    }
}
