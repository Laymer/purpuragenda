package com.purpuragenda.adapters.out.calendar;

import com.purpuragenda.application.calendar.CalendarEvent;
import com.purpuragenda.application.calendar.CalendarEvent.Audience;
import com.purpuragenda.application.port.out.CalendarWriter;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.customer.Intake;
import com.purpuragenda.domain.scheduling.SchedulingPolicy;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.Sequence;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ICalendarWriterTest {
    static final Instant STAMP=Instant.parse("2026-01-01T12:34:56.123456Z");
    static final UUID ID=UUID.fromString("00000000-0000-4000-8000-000000000037");
    final CalendarWriter writer=new ICalendarWriter();

    Booking booking(Instant start,int minutes) {
        var service=new ServiceDefinition(UUID.randomUUID(),1,"Synthetic private service",minutes,new Money(1500,"EUR"),true);
        var intake=new Intake("SyntheticFirst","SyntheticLast","+32123456789",LocalDate.parse("2000-01-01"),
                LocalTime.NOON,"SyntheticBirthplace","Synthetic private intake");
        var snapshot=new Booking.Snapshot(service,SchedulingPolicy.defaults(),intake,"synthetic@example.test","Europe/Brussels","v1");
        return Booking.confirmed(ID,"SYNTHETIC37",UUID.randomUUID(),snapshot,Booking.Origin.CUSTOMER,
                start.minus(Duration.ofDays(2)),start,Meeting.parse("https://zoom.us/j/123456789?pwd=SyntheticOnly",Set.of("zoom.us")));
    }
    Booking booking() { return booking(Instant.parse("2026-03-29T00:45:00Z"),30); }
    byte[] write(Booking booking,Audience audience) { return writer.write(CalendarEvent.from(booking,audience),STAMP); }
    Calendar parse(byte[] bytes) throws Exception { return new CalendarBuilder().build(new ByteArrayInputStream(bytes)); }
    VEvent event(Calendar calendar) { assertEquals(1,calendar.getComponents().size());return (VEvent)calendar.getComponents().getFirst(); }
    String value(VEvent event,String name) { return event.getProperty(name).orElseThrow().getValue(); }
    String text(byte[] bytes) { return new String(bytes,StandardCharsets.UTF_8); }

    @Test void stableAudienceDistinctUidsExactSequenceAndExplicitTimestamp() throws Exception {
        Booking original=booking();
        byte[] first=write(original,Audience.CUSTOMER);
        assertArrayEquals(first,write(original,Audience.CUSTOMER));
        var customer=event(parse(first));var owner=event(parse(write(original,Audience.OWNER)));
        assertNotEquals(value(customer,"UID"),value(owner,"UID"));
        assertEquals(ID+"-customer@purpuragenda",value(customer,"UID"));
        assertEquals("20260101T123456Z",value(customer,"DTSTAMP"));
        assertEquals("0",value(customer,"SEQUENCE"));
        Booking moved=original.reschedule(original.createdAt(),original.start().plusSeconds(3600),false)
                .updateMeeting(Meeting.parse("https://zoom.us/j/987654321",Set.of("zoom.us")))
                .deliveryContact("replacement@example.test");
        var revised=event(parse(write(moved,Audience.CUSTOMER)));
        assertEquals(value(customer,"UID"),value(revised,"UID"));assertEquals("2",value(revised,"SEQUENCE"));
        var cancelled=event(parse(write(moved.cancel(moved.createdAt(),false),Audience.CUSTOMER)));
        assertEquals(value(customer,"UID"),value(cancelled,"UID"));assertEquals("3",value(cancelled,"SEQUENCE"));
        assertEquals("CANCELLED",value(cancelled,"STATUS"));
        var later=event(parse(writer.write(CalendarEvent.from(original,Audience.CUSTOMER),STAMP.plusSeconds(7))));
        assertEquals("20260101T123503Z",value(later,"DTSTAMP"));assertEquals(value(customer,"UID"),value(later,"UID"));
        assertThrows(NullPointerException.class,()->writer.write(null,STAMP));
        assertThrows(NullPointerException.class,()->writer.write(CalendarEvent.from(original,Audience.CUSTOMER),null));
    }

    @Test void distinctBookingsNeverShareAnAudienceUid() throws Exception {
        Booking b=booking();
        Booking other=new Booking(UUID.fromString("00000000-0000-4000-8000-000000000038"),b.reference(),b.customerId(),
                b.snapshot(),b.deliveryEmail(),b.state(),b.origin(),b.createdAt(),b.start(),b.expiresAt(),b.meeting(),
                b.confirmationBasis(),b.version(),b.calendarRevision(),b.previouslyConfirmed());
        var identities=new HashSet<String>();
        for(Booking booking:List.of(b,other))for(Audience audience:Audience.values())
            assertTrue(identities.add(value(event(parse(write(booking,audience))),"UID")));
        assertEquals(4,identities.size());
    }

    @Test void utcActualSessionsRoundTripAcrossBothDstTransitionsAndDurations() throws Exception {
        for(int minutes:List.of(30,60))for(String start:List.of("2026-03-29T00:45:00Z","2026-10-25T00:45:00Z")) {
            Booking booking=booking(Instant.parse(start),minutes);var calendar=parse(write(booking,Audience.OWNER));
            assertFalse(calendar.validate().hasErrors());var event=event(calendar);
            assertEquals(booking.start(),Instant.from(event.getStartDate().orElseThrow().getDate()));
            assertEquals(booking.sessionEnd(),Instant.from(event.getEndDate().orElseThrow().getDate()));
            assertNotEquals(booking.reservedEnd(),Instant.from(event.getEndDate().orElseThrow().getDate()));
            assertTrue(value(event,"DTSTART").endsWith("Z"));assertTrue(value(event,"DTEND").endsWith("Z"));
        }
    }

    @Test void exactPropertyAllowlistExcludesPrivateFieldsAndInvitationSemantics() throws Exception {
        Booking b=booking();
        for(Audience audience:Audience.values()) {
            byte[] bytes=write(b,audience);var calendar=parse(bytes);var event=event(calendar);
            assertEquals(Set.of("PRODID","VERSION"),new HashSet<>(calendar.getProperties().stream().map(p->p.getName()).toList()));
            assertEquals(Set.of("UID","DTSTAMP","SEQUENCE","DTSTART","DTEND","STATUS","SUMMARY","DESCRIPTION"),
                    new HashSet<>(event.getProperties().stream().map(p->p.getName()).toList()));
            assertEquals(8,event.getProperties().size());assertTrue(event.getComponents().isEmpty());
            assertEquals("Consultation",value(event,"SUMMARY"));
            assertTrue(value(event,"DESCRIPTION").contains("Meeting link: "+b.meeting().participantUrl()));
            assertTrue(value(event,"DESCRIPTION").contains("manual updates or removal"));
            for(String secret:List.of(b.reference(),b.customerId().toString(),b.deliveryEmail(),b.snapshot().service().name(),
                    "SyntheticFirst","SyntheticLast","+32123456789","2000-01-01","SyntheticBirthplace","Synthetic private intake",
                    "METHOD:","RSVP", "ATTENDEE:","ORGANIZER:","ATTACH:","URL:","LOCATION:"))assertFalse(text(bytes).contains(secret));
            assertWire(bytes);
        }
    }

    @Test void pendingAndAllTerminalStatesKeepCaveatAndNeverExposeMeeting() throws Exception {
        Booking b=booking();Booking pending=Booking.pending(b.id(),b.reference(),b.customerId(),b.snapshot(),b.origin(),b.createdAt(),b.start());
        for(Booking state:List.of(pending.updateMeeting(b.meeting()),pending.reject(),pending.expire(pending.expiresAt()),
                b.cancel(b.createdAt(),false),b.outcome(b.sessionEnd(),Booking.State.COMPLETED,""),b.outcome(b.sessionEnd(),Booking.State.NO_SHOW,""))) {
            var event=event(parse(write(state,Audience.CUSTOMER)));
            String expected=switch(state.state()) {
                case PENDING_CONFIRMATION -> "TENTATIVE";
                case COMPLETED,NO_SHOW -> "CONFIRMED";
                default -> "CANCELLED";
            };
            assertEquals(expected,value(event,"STATUS"));assertFalse(value(event,"DESCRIPTION").contains("https://"));
            assertTrue(value(event,"DESCRIPTION").contains("manual updates or removal"));
        }
    }

    @Test void escapedTextAndUnicodeAtEveryFoldingBoundarySurviveRoundTrip() throws Exception {
        // Test adapter serialization directly: user-controlled text is deliberately absent from the export projection.
        for(String character:List.of("a","é","€","🪻"))for(int boundary=68;boundary<=77;boundary++) {
            var calendar=parse(write(booking(),Audience.CUSTOMER));var event=event(calendar);
            String description="a".repeat(boundary)+character.repeat(90)+"\\comma,semicolon;\nEND:VEVENT\nBEGIN:VEVENT\nSynthetic text";
            event.removeAll("DESCRIPTION");event.add(new Description(description));
            byte[] bytes=ICalendarWriter.serialize(calendar);assertWire(bytes);
            assertEquals(description,value(event(parse(bytes)),"DESCRIPTION"));
            String unfolded=text(bytes).replace("\r\n ","");
            assertTrue(unfolded.contains("\\\\comma\\,semicolon\\;\\nEND:VEVENT\\nBEGIN:VEVENT"));
        }
    }

    @Test void asciiFoldIncludesContinuationSpaceInSeventyFiveOctetLimit() throws Exception {
        var calendar=parse(write(booking(),Audience.CUSTOMER));var event=event(calendar);
        event.removeAll("DESCRIPTION");event.add(new Description("a".repeat(160)));
        String output=text(ICalendarWriter.serialize(calendar));
        assertTrue(output.contains("DESCRIPTION:"+"a".repeat(63)+"\r\n "+"a".repeat(74)+"\r\n "+"a".repeat(23)+"\r\n"));
    }

    @Test void invalidCalendarFailsWithFixedMessageWithoutPrivateValidationDetails() throws Exception {
        var calendar=parse(write(booking(),Audience.CUSTOMER));event(calendar).add(new Sequence(99));
        var error=assertThrows(IllegalStateException.class,()->ICalendarWriter.serialize(calendar));
        assertEquals("CALENDAR_SERIALIZATION_FAILED",error.getMessage());assertNull(error.getCause());
    }

    void assertWire(byte[] bytes) throws Exception {
        String output=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        assertTrue(output.endsWith("\r\n"));assertFalse(output.replace("\r\n","").contains("\n"));
        assertFalse(output.replace("\r\n","").contains("\r"));
        for(String line:output.split("\r\n"))assertTrue(line.getBytes(StandardCharsets.UTF_8).length<=75,"UTF-8 fold exceeds 75 octets");
        assertFalse(output.contains("\uFFFD"));
    }
}
