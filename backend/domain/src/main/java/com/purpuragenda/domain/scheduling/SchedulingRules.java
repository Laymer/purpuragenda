package com.purpuragenda.domain.scheduling;

import static com.purpuragenda.domain.DomainFailure.require;
import com.purpuragenda.domain.customer.Input;
import java.time.*;
import java.util.*;

public final class SchedulingRules {
    public enum Rule { START_RULES, NOTICE, HORIZON, DAILY_CAPACITY, ACTIVE_LIMIT, REQUEST_LIMIT, HARD_BLACKOUT }
    public record Override(Set<Rule> rules, String reason, Set<UUID> affectedBlackoutIds) {
        public Override { rules = Set.copyOf(rules); affectedBlackoutIds = Set.copyOf(affectedBlackoutIds); if (!rules.isEmpty()) reason = Input.reason(reason); }
        public static Override none() { return new Override(Set.of(), "", Set.of()); }
    }
    private SchedulingRules() {}
    public static Set<Rule> violations(Instant start, Instant now, SchedulingPolicy policy, AvailabilityCalendar calendar,
                                       int dailyCount, int activeCount, int requestCount) {
        Set<Rule> result = EnumSet.noneOf(Rule.class);
        if (!calendar.permitsStart(start, policy.gridMinutes())) result.add(Rule.START_RULES);
        if (start.isBefore(now.plus(policy.minimumNotice()))) result.add(Rule.NOTICE);
        if (start.isAfter(policy.horizon(now))) result.add(Rule.HORIZON);
        if (dailyCount >= policy.dailyCapacity()) result.add(Rule.DAILY_CAPACITY);
        if (activeCount >= SchedulingPolicy.CUSTOMER_LIMIT) result.add(Rule.ACTIVE_LIMIT);
        if (requestCount >= SchedulingPolicy.CUSTOMER_LIMIT) result.add(Rule.REQUEST_LIMIT);
        if (calendar.blackouts().stream().anyMatch(b -> b.interval().intersects(ReservationInterval.reserve(start)))) result.add(Rule.HARD_BLACKOUT);
        return Collections.unmodifiableSet(result);
    }
    public static void validate(Instant start, Instant now, SchedulingPolicy policy, AvailabilityCalendar calendar,
                                int dailyCount, int activeCount, int requestCount, Collection<ReservationInterval> occupied,
                                Override override) {
        require(start.isAfter(now), "START_NOT_FUTURE");
        require(occupied.stream().noneMatch(i -> i.intersects(ReservationInterval.reserve(start))), "SLOT_UNAVAILABLE");
        Set<Rule> violations = violations(start,now,policy,calendar,dailyCount,activeCount,requestCount);
        for (Rule rule : violations) {
            if (!override.rules.contains(rule)) throw new com.purpuragenda.domain.DomainFailure(switch (rule) {
                case START_RULES -> "START_NOT_AVAILABLE"; case NOTICE -> "MINIMUM_NOTICE"; case HORIZON -> "OUTSIDE_HORIZON";
                case DAILY_CAPACITY -> "DAILY_CAPACITY_REACHED"; case ACTIVE_LIMIT -> "ACTIVE_LIMIT_REACHED";
                case REQUEST_LIMIT -> "REQUEST_LIMIT_REACHED"; case HARD_BLACKOUT -> "HARD_BLACKOUT";
            });
        }
        if (violations.contains(Rule.HARD_BLACKOUT)) {
            require(calendar.blackouts().stream().filter(b -> b.interval().intersects(ReservationInterval.reserve(start)))
                    .allMatch(b -> override.affectedBlackoutIds.contains(b.id())), "BLACKOUT_OVERRIDE_REQUIRED");
        }
    }
}
