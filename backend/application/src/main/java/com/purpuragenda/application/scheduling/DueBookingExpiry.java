package com.purpuragenda.application.scheduling;

import com.purpuragenda.application.port.out.SchedulingMaintenanceUnitOfWork;
import java.util.Objects;

/** Periodic reclamation uses the same domain transition/effects as synchronous command reclamation. */
public final class DueBookingExpiry {
    private final SchedulingMaintenanceUnitOfWork unit;
    public DueBookingExpiry(SchedulingMaintenanceUnitOfWork unit) { this.unit=Objects.requireNonNull(unit); }
    public int tick() { return unit.maintain(ExpiryReclamation::reclaim); }
}
