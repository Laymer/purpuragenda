package com.purpuragenda.application.scheduling;

import com.purpuragenda.application.port.out.SchedulingUnitOfWork;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.customer.Input;
import com.purpuragenda.domain.scheduling.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.purpuragenda.domain.DomainFailure.require;
import static com.purpuragenda.domain.scheduling.SchedulingPolicy.BUSINESS_ZONE;

/** Anonymous advisory queries; submission rechecks every invariant under the resource lock. */
public final class PublicScheduling {
    private final SchedulingUnitOfWork unit;
    public PublicScheduling(SchedulingUnitOfWork unit) { this.unit=Objects.requireNonNull(unit); }
    public List<ServiceDefinition> services() {
        return unit.read(v -> v.services().stream().filter(ServiceDefinition::active).toList());
    }
    public record Slot(Instant start, Instant sessionEnd, Instant reservationEnd, LocalDate businessDate,
                       String displayLocal, String displayOffset) {}
    public record Availability(Instant serverNow, String businessZone, String displayZone, Instant horizon,
                               long policyVersion, List<Slot> slots, List<String> restrictions) {
        public Availability { slots=List.copyOf(slots); restrictions=List.copyOf(restrictions); }
    }
    public Availability availability(UUID serviceId, LocalDate from, LocalDate to, String displayZone) {
        ZoneId zone=Input.zone(displayZone);
        require(serviceId!=null,"SERVICE_UNAVAILABLE");
        require(from!=null && to!=null && !to.isBefore(from) && ChronoUnit.DAYS.between(from,to)<31,"INVALID_DATE_RANGE");
        Instant lower,upper;
        try { lower=from.atStartOfDay(zone).toInstant(); upper=to.plusDays(1).atStartOfDay(zone).toInstant(); }
        catch(DateTimeException invalid) { throw new DomainFailure("INVALID_DATE_RANGE"); }
        return unit.read(v -> {
            ServiceDefinition service=v.services().stream().filter(s -> s.id().equals(serviceId) && s.active())
                    .findFirst().orElseThrow(() -> new DomainFailure("SERVICE_UNAVAILABLE"));
            Instant now=v.now(); SchedulingPolicy policy=v.policy(); Instant horizon=policy.horizon(now);
            List<Slot> slots=new ArrayList<>();
            if(lower.isBefore(upper) && upper.isAfter(now) && !lower.isAfter(horizon)
                    && policy.minimumNotice().compareTo(Duration.between(now,horizon))<=0
                    && policy.expiryBuffer().compareTo(Duration.between(now,horizon))<0) {
                Instant first=lower.isAfter(now)?lower:now;
                Instant last=upper.minusNanos(1).isBefore(horizon)?upper.minusNanos(1):horizon;
                LocalDate firstDay=first.atZone(BUSINESS_ZONE).toLocalDate(), lastDay=last.atZone(BUSINESS_ZONE).toLocalDate();
                // Count whole business dates and include next-date bookings intersecting a late reservation.
                List<Booking> occupied=v.bookings(new ReservationInterval(firstDay.atStartOfDay(BUSINESS_ZONE).toInstant(),
                        lastDay.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant().plusSeconds(7200))).stream().filter(b -> b.occupies(now)).toList();
                AvailabilityCalendar calendar=v.calendar();
                for(LocalDate day=firstDay; !day.isAfter(lastDay); day=day.plusDays(1)) {
                    LocalDate current=day;
                    int daily=(int)occupied.stream().filter(b -> b.businessDate().equals(current)).count();
                    for(Instant start:calendar.candidates(day,policy.gridMinutes())) {
                        if(start.isBefore(lower) || !start.isBefore(upper) || !start.isAfter(now)
                                || !start.isAfter(now.plus(policy.expiryBuffer()))
                                || !SchedulingRules.violations(start,now,policy,calendar,daily,0,0).isEmpty()
                                || occupied.stream().anyMatch(b -> b.interval().intersects(ReservationInterval.reserve(start)))) continue;
                        ZonedDateTime displayed=start.atZone(zone);
                        slots.add(new Slot(start,start.plusSeconds(service.durationMinutes()*60L),start.plusSeconds(7200),
                                day,displayed.toLocalDateTime().toString(),displayed.getOffset().toString()));
                    }
                }
            }
            slots.sort(Comparator.comparing(Slot::start));
            return new Availability(now,BUSINESS_ZONE.getId(),zone.getId(),horizon,policy.version(),slots,List.of());
        });
    }
}
