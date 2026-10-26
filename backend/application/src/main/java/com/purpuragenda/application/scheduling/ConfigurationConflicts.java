package com.purpuragenda.application.scheduling;

import com.purpuragenda.application.port.out.SchedulingUnitOfWork;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.scheduling.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import static com.purpuragenda.domain.DomainFailure.require;
import static com.purpuragenda.domain.scheduling.SchedulingPolicy.BUSINESS_ZONE;

/**
 * Read-only assessment of FUTURE occupying bookings that a proposed schedule, exception, blackout or policy change would
 * newly conflict with. Nothing is written and the caller must authorize it (P22); submission-time rules remain authoritative.
 * Service changes never conflict: bookings keep immutable service snapshots. Notice, horizon and pending-lifetime changes only
 * affect new requests. Conflicts already present under the current configuration are not reported again.
 */
public final class ConfigurationConflicts {
    public enum Reason { START_RULES, HARD_BLACKOUT, DAILY_CAPACITY }
    /** Null members keep the current value. A full proposed calendar carries weekly windows, date exceptions and blackouts. */
    public record Proposal(AvailabilityCalendar calendar,SchedulingPolicy policy) {
        public Proposal { require(calendar!=null || policy!=null,"INVALID_PROPOSAL"); }
    }
    public record Conflict(UUID bookingId,String reference,Instant start,Booking.State state,Set<Reason> reasons,Set<UUID> blackoutIds) {
        public Conflict { reasons=Set.copyOf(reasons);blackoutIds=Set.copyOf(blackoutIds); }
    }
    public record Assessment(Instant assessedAt,long calendarVersion,long policyVersion,List<Conflict> conflicts) {
        public Assessment { conflicts=List.copyOf(conflicts); }
    }
    private static final Duration SCAN=Duration.ofDays(3650);
    private final SchedulingUnitOfWork unit;
    public ConfigurationConflicts(SchedulingUnitOfWork unit) { this.unit=Objects.requireNonNull(unit); }
    public Assessment assess(Proposal proposal) {
        Objects.requireNonNull(proposal);
        return unit.read(view -> evaluate(view,proposal));
    }
    /** Evaluates inside an already open view or locked transaction, so a mutation reviews the exact state it will change. */
    public static Assessment evaluate(SchedulingUnitOfWork.View view,Proposal proposal) {
        Objects.requireNonNull(view);Objects.requireNonNull(proposal);
        {
            Instant now=view.now();SchedulingPolicy currentPolicy=view.policy();AvailabilityCalendar currentCalendar=view.calendar();
            SchedulingPolicy policy=proposal.policy()==null?currentPolicy:proposal.policy();
            AvailabilityCalendar calendar=proposal.calendar()==null?currentCalendar:proposal.calendar();
            // Capacity counts every occupying booking of a business date, including earlier ones today.
            Instant from=now.atZone(BUSINESS_ZONE).toLocalDate().atStartOfDay(BUSINESS_ZONE).toInstant();
            List<Booking> occupying=view.bookings(new ReservationInterval(from,now.plus(SCAN))).stream()
                    .filter(b -> b.occupies(now)).sorted(Comparator.comparing(Booking::createdAt).thenComparing(Booking::id)).toList();
            Map<UUID,Set<Reason>> proposed=violations(occupying,calendar,policy),existing=violations(occupying,currentCalendar,currentPolicy);
            List<Conflict> conflicts=new ArrayList<>();
            for(Booking booking:occupying) {
                if(!booking.start().isAfter(now))continue;
                Set<Reason> reasons=new TreeSet<>(proposed.getOrDefault(booking.id(),Set.of()));
                reasons.removeAll(existing.getOrDefault(booking.id(),Set.of()));
                if(reasons.isEmpty())continue;
                Set<UUID> blackouts=reasons.contains(Reason.HARD_BLACKOUT)?intersecting(booking,calendar):Set.of();
                conflicts.add(new Conflict(booking.id(),booking.reference(),booking.start(),booking.state(),reasons,blackouts));
            }
            conflicts.sort(Comparator.comparing(Conflict::start).thenComparing(Conflict::bookingId));
            return new Assessment(now,calendar.version(),policy.version(),conflicts);
        }
    }
    private static Map<UUID,Set<Reason>> violations(List<Booking> bookings,AvailabilityCalendar calendar,SchedulingPolicy policy) {
        Map<UUID,Set<Reason>> result=new HashMap<>();
        Map<LocalDate,Integer> perDay=new HashMap<>();
        for(Booking booking:bookings) {
            Set<Reason> reasons=EnumSet.noneOf(Reason.class);
            if(!calendar.permitsStart(booking.start(),policy.gridMinutes()))reasons.add(Reason.START_RULES);
            if(!intersecting(booking,calendar).isEmpty())reasons.add(Reason.HARD_BLACKOUT);
            // Earlier-created bookings keep their place; later ones over the limit are the excess.
            if(perDay.merge(booking.businessDate(),1,Integer::sum)>policy.dailyCapacity())reasons.add(Reason.DAILY_CAPACITY);
            result.put(booking.id(),reasons);
        }
        return result;
    }
    private static Set<UUID> intersecting(Booking booking,AvailabilityCalendar calendar) {
        return calendar.blackouts().stream().filter(b -> b.interval().intersects(booking.interval()))
                .map(AvailabilityCalendar.Blackout::id).collect(Collectors.toSet());
    }
}
