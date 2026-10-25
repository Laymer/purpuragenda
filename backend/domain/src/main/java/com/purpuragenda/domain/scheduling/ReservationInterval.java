package com.purpuragenda.domain.scheduling;

import static com.purpuragenda.domain.DomainFailure.require;
import java.time.*;

/** Half-open [start,end). Adjacent intervals do not intersect. */
public record ReservationInterval(Instant start, Instant end) {
    public ReservationInterval { require(start != null && end != null && start.isBefore(end), "INVALID_INTERVAL"); }
    public static ReservationInterval reserve(Instant start) { return new ReservationInterval(start, start.plus(Duration.ofMinutes(120))); }
    public boolean intersects(ReservationInterval other) { return start.isBefore(other.end) && other.start.isBefore(end); }
}
