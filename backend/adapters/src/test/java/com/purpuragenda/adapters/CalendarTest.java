package com.purpuragenda.adapters;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.customer.Intake;
import com.purpuragenda.domain.scheduling.SchedulingPolicy;
import com.purpuragenda.adapters.out.calendar.IcalendarWriter;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import net.fortuna.ical4j.data.CalendarBuilder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CalendarTest {
    @Test void exportsAreParseableActualDurationStablePrivateAndCancelled()throws Exception{
        Instant now=Instant.parse("2027-01-10T09:00:00Z"),start=Instant.parse("2027-01-12T14:30:00Z");
        var snapshot=new Booking.Snapshot(new ServiceDefinition(UUID.randomUUID(),1,"Transit reading",60,Money.eur(3000),true),SchedulingPolicy.defaults(),new Intake("Synthetic","Example","+32400123456",LocalDate.of(1990,1,1),LocalTime.NOON,"PRIVATE_BIRTHPLACE","PRIVATE_MESSAGE"),"synthetic@example.test","Europe/Brussels","test");
        var b=Booking.confirmed(UUID.randomUUID(),"TEST-1234567",UUID.randomUUID(),snapshot,Booking.Origin.ADMINISTRATOR,now,start,Meeting.parse("https://zoom.us/j/98765432101?pwd=abc123",Set.of("zoom.us")));
        var writer=new IcalendarWriter();byte[] customer=writer.write(b,"CUSTOMER",now),owner=writer.write(b,"OWNER",now);
        String text=new String(customer,StandardCharsets.UTF_8);assertTrue(text.contains("DTSTART:20270112T143000Z"));assertTrue(text.contains("DTEND:20270112T153000Z"));
        assertFalse(text.contains("PRIVATE_"));assertFalse(text.contains("synthetic@example.test"));assertFalse(text.contains("METHOD:"));assertFalse(text.contains("ATTENDEE"));
        assertNotEquals(new String(owner,StandardCharsets.UTF_8),text);assertNotNull(new CalendarBuilder().build(new java.io.ByteArrayInputStream(customer)));
        String cancelled=new String(writer.write(b.cancel(now,false),"CUSTOMER",now),StandardCharsets.UTF_8);assertTrue(cancelled.contains("SEQUENCE:1"));assertTrue(cancelled.contains("STATUS:CANCELLED"));
        assertEquals(text.lines().filter(l->l.startsWith("UID:")).findFirst(),cancelled.lines().filter(l->l.startsWith("UID:")).findFirst());
        for(String line:text.split("\r\n"))assertTrue(line.getBytes(StandardCharsets.UTF_8).length<=75);
    }
}
