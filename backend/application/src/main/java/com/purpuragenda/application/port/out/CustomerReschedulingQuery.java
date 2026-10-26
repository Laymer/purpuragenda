package com.purpuragenda.application.port.out;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.scheduling.*;
import java.time.Instant;
import java.util.*;

/** Authority and scheduling facts from one read-only snapshot; no session or scheduling mutations. */
public interface CustomerReschedulingQuery {
    Optional<Context> find(SessionBinding session,UUID bookingId,ReservationInterval range);
    record Context(Booking booking,SchedulingPolicy policy,AvailabilityCalendar calendar,List<Booking> occupied,
                   int active,Instant now) {
        public Context {
            Objects.requireNonNull(booking);Objects.requireNonNull(policy);Objects.requireNonNull(calendar);
            occupied=List.copyOf(occupied);Objects.requireNonNull(now);
            if(active<0)throw new IllegalArgumentException("INVALID_ACTIVE_COUNT");
        }
        @Override public String toString() { return "ReschedulingContext[REDACTED]"; }
    }
}
