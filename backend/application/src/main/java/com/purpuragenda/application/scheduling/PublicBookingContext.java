package com.purpuragenda.application.scheduling;

import com.purpuragenda.application.port.out.SchedulingUnitOfWork;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.ServiceDefinition;
import com.purpuragenda.domain.scheduling.SchedulingPolicy;
import java.time.Instant;
import java.util.*;

/** One anonymous read snapshot for review. This is neither a hold nor permission to bypass command revalidation. */
public final class PublicBookingContext {
    private final SchedulingUnitOfWork unit;
    private final BookingNotice notice;
    public PublicBookingContext(SchedulingUnitOfWork unit,BookingNotice notice) {
        this.unit=Objects.requireNonNull(unit);this.notice=Objects.requireNonNull(notice);
    }
    public record Context(Instant serverNow,Instant horizon,ServiceDefinition service,SchedulingPolicy policy,BookingNotice notice) {}
    public Context find(UUID serviceId) {
        if(serviceId==null)throw new DomainFailure("SERVICE_UNAVAILABLE");
        return unit.read(view -> {
            var service=view.services().stream().filter(value -> value.active() && value.id().equals(serviceId))
                    .findFirst().orElseThrow(() -> new DomainFailure("SERVICE_UNAVAILABLE"));
            var now=view.now();var policy=view.policy();
            return new Context(now,policy.horizon(now),service,policy,notice);
        });
    }
}
