package com.purpuragenda.application.port.out;

import com.purpuragenda.application.calendar.CalendarEvent;
import java.time.Instant;

/** Private UTF-8 iCalendar bytes; caller supplies the captured export timestamp. Never log the output. */
public interface CalendarWriter {
    byte[] write(CalendarEvent event, Instant timestamp);
}
