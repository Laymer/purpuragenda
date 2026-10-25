package com.purpuragenda.application.scheduling;

import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.scheduling.SchedulingPolicy;
import java.time.*;
import java.util.Objects;

/** Stable identity usage; delivery contact and display timezone do not affect either allowance. */
public record CustomerQuota(int active, int requests, LocalDate submissionDay) {
    public CustomerQuota {
        DomainFailure.require(active>=0 && requests>=0 && requests<=SchedulingPolicy.CUSTOMER_LIMIT && submissionDay!=null,"INVALID_QUOTA");
    }
    public static LocalDate dayAt(Instant now) {
        return Objects.requireNonNull(now).atZone(SchedulingPolicy.BUSINESS_ZONE).toLocalDate();
    }
}
