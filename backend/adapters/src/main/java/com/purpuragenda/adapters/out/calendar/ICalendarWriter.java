package com.purpuragenda.adapters.out.calendar;

import com.purpuragenda.application.calendar.CalendarEvent;
import com.purpuragenda.application.port.out.CalendarWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.*;
import net.fortuna.ical4j.model.property.immutable.ImmutableVersion;

/** No invitation semantics, attendees, or private booking details. No network or system clock reads. */
public final class ICalendarWriter implements CalendarWriter {
    @Override public byte[] write(CalendarEvent event, Instant timestamp) {
        Objects.requireNonNull(event);
        Objects.requireNonNull(timestamp);
        var calendar=new Calendar();
        calendar.add(new ProdId("-//Purpuragenda//Private consultation//EN"));
        calendar.add(ImmutableVersion.VERSION_2_0);
        // false prevents iCal4j from inserting a system-clock DTSTAMP.
        var appointment=new VEvent(false);
        appointment.add(new Uid(event.bookingId()+"-"+event.audience().name().toLowerCase(Locale.ROOT)+"@purpuragenda"));
        appointment.add(new DtStamp(timestamp));
        appointment.add(new Sequence(event.revision()));
        appointment.add(new DtStart<>(event.start()));
        appointment.add(new DtEnd<>(event.end()));
        appointment.add(new Status(event.status().name()));
        appointment.add(new Summary("Consultation"));
        String description="Private consultation.\nImported events may require manual updates or removal.";
        if(event.meetingUrl().isPresent())description+="\nMeeting link: "+event.meetingUrl().orElseThrow();
        appointment.add(new Description(description));
        calendar.add(appointment);
        return serialize(calendar);
    }

    static byte[] serialize(Calendar calendar) {
        // CalendarOutputter only warns on invalid components and folds Java characters, not UTF-8 octets.
        // Validate without logging values; iCal4j owns the property syntax and escaping.
        if(calendar.validate().hasErrors())throw new IllegalStateException("CALENDAR_SERIALIZATION_FAILED");
        var output=new StringBuilder();
        for(String line:calendar.toString().split("\r\n")) {
            int octets=0;
            for(int offset=0;offset<line.length();) {
                int codePoint=line.codePointAt(offset);
                String character=new String(Character.toChars(codePoint));
                int size=character.getBytes(StandardCharsets.UTF_8).length;
                if(octets+size>75) {
                    output.append("\r\n ");
                    octets=1;
                }
                output.append(character);
                octets+=size;
                offset+=Character.charCount(codePoint);
            }
            output.append("\r\n");
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }
}
