package com.purpuragenda.application.scheduling;

import com.purpuragenda.application.port.out.ConfigurationUnitOfWork;
import com.purpuragenda.application.port.out.ConfigurationUnitOfWork.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.ConfigurationConflicts.*;
import com.purpuragenda.domain.customer.Input;
import com.purpuragenda.domain.scheduling.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import static com.purpuragenda.domain.DomainFailure.require;
import static com.purpuragenda.domain.scheduling.SchedulingPolicy.BUSINESS_ZONE;

/**
 * Administrator schedule and policy commands. Existing bookings are never invalidated, moved or deleted and keep their
 * immutable policy snapshots; a change that newly conflicts with future bookings proceeds only when the administrator
 * acknowledges exactly those bookings with a reason. Authority and persistence belong to the authorized transaction.
 */
public final class AdministratorConfiguration {
    public static final UUID CALENDAR_ID=UUID.nameUUIDFromBytes("purpuragenda/configuration/calendar".getBytes(StandardCharsets.UTF_8));
    public static final UUID POLICY_ID=UUID.nameUUIDFromBytes("purpuragenda/configuration/policy".getBytes(StandardCharsets.UTF_8));
    private final ConfigurationUnitOfWork unit;
    public AdministratorConfiguration(ConfigurationUnitOfWork unit) { this.unit=Objects.requireNonNull(unit); }

    /** Reason (10-500 characters) for the change and the exact set of conflicting future bookings that was reviewed. */
    public record Review(String reason,Set<UUID> acknowledgedBookingIds) {
        public Review { reason=Input.reason(reason);acknowledgedBookingIds=Set.copyOf(acknowledgedBookingIds); }
        @Override public String toString() { return "ConfigurationReview[REDACTED]"; }
        List<String> fields() {
            return List.of(reason,acknowledgedBookingIds.stream().map(UUID::toString).sorted().collect(Collectors.joining(",")));
        }
    }
    public record PolicyValues(int gridMinutes,int dailyCapacity,Duration minimumNotice,Duration pendingLifetime,Duration expiryBuffer,Duration customerCutoff) {
        SchedulingPolicy next(long version) {
            return new SchedulingPolicy(version,gridMinutes,dailyCapacity,minimumNotice,pendingLifetime,expiryBuffer,customerCutoff);
        }
        boolean sameAs(SchedulingPolicy p) {
            return gridMinutes==p.gridMinutes() && dailyCapacity==p.dailyCapacity() && minimumNotice.equals(p.minimumNotice())
                    && pendingLifetime.equals(p.pendingLifetime()) && expiryBuffer.equals(p.expiryBuffer()) && customerCutoff.equals(p.customerCutoff());
        }
        List<String> fields() {
            return List.of(Integer.toString(gridMinutes),Integer.toString(dailyCapacity),minimumNotice.toString(),pendingLifetime.toString(),
                    expiryBuffer.toString(),customerCutoff.toString());
        }
    }

    /** Replaces all weekly windows; a day missing from the map becomes closed. */
    public Receipt replaceWeekly(SessionBinding session,String key,long expectedCalendarVersion,Map<DayOfWeek,List<AvailabilityCalendar.Window>> weekly,Review review) {
        Objects.requireNonNull(weekly);
        Map<DayOfWeek,List<AvailabilityCalendar.Window>> copy=new EnumMap<>(DayOfWeek.class);
        for(DayOfWeek day:DayOfWeek.values())copy.put(day,List.copyOf(weekly.getOrDefault(day,List.of())));
        List<String> fields=new ArrayList<>();
        copy.forEach((day,windows) -> { fields.add(day.name());windows.forEach(w -> fields.add(w.startMinute()+"-"+w.endMinute())); });
        return calendar(session,key,Operation.REPLACE_WEEKLY,expectedCalendarVersion,review,fields,
                (current,now) -> new AvailabilityCalendar(copy,current.exceptions(),current.blackouts(),current.version()+1));
    }
    /** Adds or replaces one date exception (REPLACE, ADD or CLOSED) for today or a future Brussels date. */
    public Receipt setDateException(SessionBinding session,String key,long expectedCalendarVersion,LocalDate date,AvailabilityCalendar.DateException exception,Review review) {
        require(date!=null && exception!=null,"INVALID_CONFIGURATION_REQUEST");
        List<String> fields=new ArrayList<>(List.of(date.toString(),exception.mode().name()));
        exception.windows().forEach(w -> fields.add(w.startMinute()+"-"+w.endMinute()));
        return calendar(session,key,Operation.SET_DATE_EXCEPTION,expectedCalendarVersion,review,fields,(current,now) -> {
            require(!date.isBefore(now.atZone(BUSINESS_ZONE).toLocalDate()),"INVALID_CONFIGURATION");
            Map<LocalDate,AvailabilityCalendar.DateException> exceptions=new HashMap<>(current.exceptions());exceptions.put(date,exception);
            return new AvailabilityCalendar(current.weekly(),exceptions,current.blackouts(),current.version()+1);
        });
    }
    public Receipt removeDateException(SessionBinding session,String key,long expectedCalendarVersion,LocalDate date,Review review) {
        require(date!=null,"INVALID_CONFIGURATION_REQUEST");
        return calendar(session,key,Operation.REMOVE_DATE_EXCEPTION,expectedCalendarVersion,review,List.of(date.toString()),(current,now) -> {
            require(current.exceptions().containsKey(date) && !date.isBefore(now.atZone(BUSINESS_ZONE).toLocalDate()),"INVALID_CONFIGURATION");
            Map<LocalDate,AvailabilityCalendar.DateException> exceptions=new HashMap<>(current.exceptions());exceptions.remove(date);
            return new AvailabilityCalendar(current.weekly(),exceptions,current.blackouts(),current.version()+1);
        });
    }
    /** The administrator supplies the blackout id so a retry or a later removal can name it; it must not exist and must end in the future. */
    public Receipt addBlackout(SessionBinding session,String key,long expectedCalendarVersion,AvailabilityCalendar.Blackout blackout,Review review) {
        Objects.requireNonNull(blackout);
        return calendar(session,key,Operation.ADD_BLACKOUT,expectedCalendarVersion,review,
                List.of(blackout.id().toString(),blackout.interval().start().toString(),blackout.interval().end().toString(),blackout.reason()),(current,now) -> {
            require(blackout.interval().end().isAfter(now) && current.blackouts().stream().noneMatch(b -> b.id().equals(blackout.id())),"INVALID_CONFIGURATION");
            List<AvailabilityCalendar.Blackout> all=new ArrayList<>(current.blackouts());all.add(blackout);
            return new AvailabilityCalendar(current.weekly(),current.exceptions(),all,current.version()+1);
        });
    }
    public Receipt removeBlackout(SessionBinding session,String key,long expectedCalendarVersion,UUID blackoutId,Review review) {
        require(blackoutId!=null,"INVALID_CONFIGURATION_REQUEST");
        return calendar(session,key,Operation.REMOVE_BLACKOUT,expectedCalendarVersion,review,List.of(blackoutId.toString()),(current,now) -> {
            List<AvailabilityCalendar.Blackout> remaining=current.blackouts().stream().filter(b -> !b.id().equals(blackoutId)).toList();
            require(remaining.size()==current.blackouts().size()-1,"INVALID_CONFIGURATION");
            return new AvailabilityCalendar(current.weekly(),current.exceptions(),remaining,current.version()+1);
        });
    }
    /** Appends the next immutable policy version. Existing bookings keep their snapshots; lowered limits only block new bookings. */
    public Receipt updatePolicy(SessionBinding session,String key,long expectedPolicyVersion,PolicyValues values,Review review) {
        Objects.requireNonNull(values);Objects.requireNonNull(review);require(expectedPolicyVersion>0,"INVALID_CONFIGURATION_REQUEST");
        values.next(expectedPolicyVersion+1);   // validates the values before any transaction
        List<String> fields=new ArrayList<>(List.of(Long.toString(expectedPolicyVersion)));fields.addAll(values.fields());fields.addAll(review.fields());
        return unit.execute(new Command(session,Operation.UPDATE_POLICY,key,CommandKey.fingerprint(fields)),
                tx -> SchedulingCommands.within(tx,() -> {
                    SchedulingPolicy current=tx.policy();require(current.version()==expectedPolicyVersion,"POLICY_CHANGED");
                    require(!values.sameAs(current),"INVALID_CONFIGURATION");
                    SchedulingPolicy next=values.next(current.version()+1);
                    List<Conflict> conflicts=reviewed(tx,new Proposal(null,next),review);
                    tx.savePolicy(next);tx.changedEffects(Operation.UPDATE_POLICY,next.version(),review.reason(),conflicts);
                    return new Receipt(Code.OK,POLICY_ID,next.version());
                }));
    }

    private Receipt calendar(SessionBinding session,String key,Operation operation,long expected,Review review,List<String> requestFields,
                             BiFunction<AvailabilityCalendar,Instant,AvailabilityCalendar> change) {
        Objects.requireNonNull(review);require(expected>0,"INVALID_CONFIGURATION_REQUEST");
        List<String> fields=new ArrayList<>(List.of(Long.toString(expected)));fields.addAll(requestFields);fields.add("|");fields.addAll(review.fields());
        return unit.execute(new Command(session,operation,key,CommandKey.fingerprint(fields)),
                tx -> SchedulingCommands.within(tx,() -> {
                    AvailabilityCalendar current=tx.calendar();require(current.version()==expected,"CALENDAR_CHANGED");
                    AvailabilityCalendar next=change.apply(current,tx.now());
                    require(!sameContent(current,next),"INVALID_CONFIGURATION");
                    List<Conflict> conflicts=reviewed(tx,new Proposal(next,null),review);
                    tx.saveCalendar(next);tx.changedEffects(operation,next.version(),review.reason(),conflicts);
                    return new Receipt(Code.OK,CALENDAR_ID,next.version());
                }));
    }
    /** The exact set of newly conflicting future bookings must have been acknowledged; stale or surplus reviews are refused. */
    private static List<Conflict> reviewed(Configuration tx,Proposal proposal,Review review) {
        List<Conflict> conflicts=ConfigurationConflicts.evaluate(tx,proposal).conflicts();
        require(conflicts.stream().map(Conflict::bookingId).collect(Collectors.toSet()).equals(review.acknowledgedBookingIds()),"CONFLICT_REVIEW_REQUIRED");
        return conflicts;
    }
    private static boolean sameContent(AvailabilityCalendar a,AvailabilityCalendar b) {
        return a.weekly().equals(b.weekly()) && a.exceptions().equals(b.exceptions()) && Set.copyOf(a.blackouts()).equals(Set.copyOf(b.blackouts()));
    }
}
