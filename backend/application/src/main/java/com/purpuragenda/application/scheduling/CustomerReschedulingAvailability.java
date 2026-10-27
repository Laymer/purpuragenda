package com.purpuragenda.application.scheduling;

import com.purpuragenda.application.port.out.CustomerReschedulingQuery;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.customer.Input;
import com.purpuragenda.domain.scheduling.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.purpuragenda.domain.DomainFailure.require;
import static com.purpuragenda.domain.scheduling.SchedulingPolicy.BUSINESS_ZONE;

/** Advisory changes retain booked terms; the command still rechecks all rules under the resource lock. */
public final class CustomerReschedulingAvailability {
    private final CustomerReschedulingQuery query;
    public CustomerReschedulingAvailability(CustomerReschedulingQuery query) { this.query=Objects.requireNonNull(query); }
    public Optional<PublicScheduling.Availability> find(SessionBinding session,UUID id,LocalDate from,LocalDate to,String displayZone) {
        Objects.requireNonNull(session);Objects.requireNonNull(id);ZoneId zone=Input.zone(displayZone);
        require(from!=null && to!=null && !to.isBefore(from) && ChronoUnit.DAYS.between(from,to)<31,"INVALID_DATE_RANGE");
        Instant lower,upper;ReservationInterval range;
        try {
            lower=from.atStartOfDay(zone).toInstant();upper=to.plusDays(1).atStartOfDay(zone).toInstant();
            range=new ReservationInterval(lower.atZone(BUSINESS_ZONE).toLocalDate().atStartOfDay(BUSINESS_ZONE).toInstant(),
                    upper.atZone(BUSINESS_ZONE).toLocalDate().plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant().plusSeconds(7200));
        } catch(DateTimeException | ArithmeticException invalid) { throw new DomainFailure("INVALID_DATE_RANGE"); }
        return query.find(session,id,range).map(context->{
            Booking original=context.booking();Instant now=context.now();var policy=context.policy();Instant horizon=policy.horizon(now);
            var slots=new ArrayList<PublicScheduling.Slot>();
            if(!original.customerMayChange(now))return result(now,zone,horizon,policy,slots,List.of("BOOKING_NOT_CHANGEABLE"));
            if(lower.isBefore(upper) && upper.isAfter(now) && !lower.isAfter(horizon)
                    && policy.minimumNotice().compareTo(Duration.between(now,horizon))<=0
                    && (original.state()!=Booking.State.PENDING_CONFIRMATION
                        || original.snapshot().policy().expiryBuffer().compareTo(Duration.between(now,horizon))<0)) {
                Instant first=lower.isAfter(now)?lower:now,last=upper.minusNanos(1).isBefore(horizon)?upper.minusNanos(1):horizon;
                var occupied=context.occupied().stream().filter(b->!b.id().equals(original.id()) && b.occupies(now)).toList();
                for(LocalDate day=first.atZone(BUSINESS_ZONE).toLocalDate(),end=last.atZone(BUSINESS_ZONE).toLocalDate();!day.isAfter(end);day=day.plusDays(1)) {
                    LocalDate businessDate=day;
                    int daily=(int)occupied.stream().filter(b->b.businessDate().equals(businessDate)).count();
                    for(Instant start:context.calendar().candidates(day,policy.gridMinutes())) {
                        if(start.isBefore(lower) || !start.isBefore(upper) || !start.isAfter(now)
                                || (original.state()==Booking.State.PENDING_CONFIRMATION && !start.isAfter(now.plus(original.snapshot().policy().expiryBuffer())))
                                || !SchedulingRules.violations(start,now,policy,context.calendar(),daily,Math.max(0,context.active()-1),0).isEmpty()
                                || occupied.stream().anyMatch(b->b.interval().intersects(ReservationInterval.reserve(start))))continue;
                        var local=start.atZone(zone);
                        slots.add(new PublicScheduling.Slot(start,start.plusSeconds(original.snapshot().service().durationMinutes()*60L),start.plusSeconds(7200),
                                day,local.toLocalDateTime().toString(),local.getOffset().toString()));
                    }
                }
            }
            slots.sort(Comparator.comparing(PublicScheduling.Slot::start));
            return result(now,zone,horizon,policy,slots,List.of());
        });
    }
    private static PublicScheduling.Availability result(Instant now,ZoneId zone,Instant horizon,SchedulingPolicy policy,List<PublicScheduling.Slot> slots,List<String> restrictions) {
        return new PublicScheduling.Availability(now,BUSINESS_ZONE.getId(),zone.getId(),horizon,policy.version(),slots,restrictions);
    }
}
