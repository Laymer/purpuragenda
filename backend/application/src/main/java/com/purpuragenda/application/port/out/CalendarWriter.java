package com.purpuragenda.application.port.out;
import com.purpuragenda.domain.booking.Booking;
import java.time.Instant;
public interface CalendarWriter {
    byte[] write(Booking booking,String audience,Instant generatedAt);
}
