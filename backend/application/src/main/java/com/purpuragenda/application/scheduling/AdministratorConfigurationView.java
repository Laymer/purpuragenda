package com.purpuragenda.application.scheduling;

import com.purpuragenda.application.port.out.ConfigurationQuery;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.View;
import com.purpuragenda.application.scheduling.ConfigurationConflicts.*;
import com.purpuragenda.domain.scheduling.*;
import java.time.*;
import java.util.*;

/**
 * Current schedule and policy with their versions, and advisory conflict previews of a proposed full calendar or policy.
 * A preview reports the current versions it was based on; the authorized command re-evaluates under the resource lock.
 */
public final class AdministratorConfigurationView {
    private final ConfigurationQuery query;
    public AdministratorConfigurationView(ConfigurationQuery query) { this.query=Objects.requireNonNull(query); }
    public record Current(AvailabilityCalendar calendar,SchedulingPolicy policy,Instant observedAt) { }
    public record Preview(Instant assessedAt,long calendarVersion,long policyVersion,List<Conflict> conflicts) {
        public Preview { conflicts=List.copyOf(conflicts); }
        @Override public String toString() { return "ConfigurationPreview[REDACTED]"; }
    }
    public Current current(SessionBinding session) {
        return query.read(Objects.requireNonNull(session),view -> new Current(view.calendar(),view.policy(),view.now()));
    }
    /** A day missing from the weekly map is closed, exactly as replaceWeekly would store it. */
    public Preview calendarConflicts(SessionBinding session,Map<DayOfWeek,List<AvailabilityCalendar.Window>> weekly,
                                     Map<LocalDate,AvailabilityCalendar.DateException> exceptions,List<AvailabilityCalendar.Blackout> blackouts) {
        Objects.requireNonNull(weekly);Objects.requireNonNull(exceptions);Objects.requireNonNull(blackouts);
        Map<DayOfWeek,List<AvailabilityCalendar.Window>> days=new EnumMap<>(DayOfWeek.class);
        for(DayOfWeek day:DayOfWeek.values())days.put(day,weekly.getOrDefault(day,List.of()));
        return query.read(Objects.requireNonNull(session),view ->
                preview(view,new Proposal(new AvailabilityCalendar(days,exceptions,blackouts,view.calendar().version()+1),null)));
    }
    public Preview policyConflicts(SessionBinding session,AdministratorConfiguration.PolicyValues values) {
        Objects.requireNonNull(values);
        return query.read(Objects.requireNonNull(session),view -> preview(view,new Proposal(null,values.next(view.policy().version()+1))));
    }
    private static Preview preview(View view,Proposal proposal) {
        long calendar=view.calendar().version(),policy=view.policy().version();
        Assessment assessment=ConfigurationConflicts.evaluate(view,proposal);
        return new Preview(assessment.assessedAt(),calendar,policy,assessment.conflicts());
    }
}
