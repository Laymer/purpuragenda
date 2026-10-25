package com.purpuragenda.domain.scheduling;

import static com.purpuragenda.domain.DomainFailure.require;
import java.time.*;
import java.util.*;

public record AvailabilityCalendar(Map<DayOfWeek, List<Window>> weekly, Map<LocalDate, DateException> exceptions,
                                   List<Blackout> blackouts, long version) {
    public AvailabilityCalendar {
        require(weekly != null && exceptions != null && blackouts != null, "INVALID_CALENDAR");
        Map<DayOfWeek,List<Window>> safe = new EnumMap<>(DayOfWeek.class);
        weekly.forEach((day, windows) -> {
            require(day != null, "INVALID_CALENDAR");
            safe.put(day, merge(windows));
        });
        weekly = Collections.unmodifiableMap(safe); exceptions = Map.copyOf(exceptions); blackouts = List.copyOf(blackouts);
        require(version > 0, "INVALID_VERSION");
    }
    public record Window(int startMinute, int endMinute) {
        public Window { require(startMinute >= 0 && startMinute < endMinute && endMinute <= 1440, "INVALID_WINDOW"); }
        public boolean contains(int minute) { return minute >= startMinute && minute < endMinute; }
    }
    public enum Mode { REPLACE, ADD, CLOSED }
    public record DateException(Mode mode, List<Window> windows) {
        public DateException { require(mode != null && windows != null && (mode != Mode.CLOSED || windows.isEmpty()), "INVALID_EXCEPTION"); windows = merge(windows); }
    }
    public record Blackout(UUID id, ReservationInterval interval, String reason) {
        public Blackout { require(id != null && interval != null, "INVALID_BLACKOUT"); reason = com.purpuragenda.domain.customer.Input.reason(reason); }
    }
    private static List<Window> merge(List<Window> windows) {
        require(windows != null && windows.stream().noneMatch(Objects::isNull), "INVALID_WINDOW");
        List<Window> sorted = windows.stream().sorted(Comparator.comparingInt(Window::startMinute)).toList();
        List<Window> merged = new ArrayList<>();
        for (Window window : sorted) {
            if (merged.isEmpty() || merged.getLast().endMinute() < window.startMinute()) {
                merged.add(window);
            } else {
                Window previous = merged.removeLast();
                merged.add(new Window(previous.startMinute(), Math.max(previous.endMinute(), window.endMinute())));
            }
        }
        return List.copyOf(merged);
    }
    public static AvailabilityCalendar defaults() {
        Map<DayOfWeek,List<Window>> weekly = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) weekly.put(day, List.of(new Window(540,1200)));
        return new AvailabilityCalendar(weekly, Map.of(), List.of(), 1);
    }
    public List<Window> windows(LocalDate date) {
        List<Window> inherited = weekly.getOrDefault(date.getDayOfWeek(), List.of());
        DateException exception = exceptions.get(date);
        if (exception == null) return inherited;
        return switch (exception.mode) {
            case CLOSED -> List.of(); case REPLACE -> exception.windows;
            case ADD -> { List<Window> all = new ArrayList<>(inherited); all.addAll(exception.windows); yield merge(all); }
        };
    }
    public boolean permitsStart(Instant instant, int grid) {
        require(grid >= 1 && grid <= 120, "INVALID_POLICY");
        ZonedDateTime local = instant.atZone(SchedulingPolicy.BUSINESS_ZONE);
        int minute = local.getHour()*60 + local.getMinute();
        return local.getSecond() == 0 && local.getNano() == 0 && minute % grid == 0 && windows(local.toLocalDate()).stream().anyMatch(w -> w.contains(minute));
    }
    public List<Instant> candidates(LocalDate date, int grid) {
        require(grid >= 1 && grid <= 120, "INVALID_POLICY");
        SortedSet<Instant> starts = new TreeSet<>();
        List<Window> effectiveWindows = windows(date);
        for (int minute = 0; minute < 1440; minute += grid) {
            final int value = minute;
            if (effectiveWindows.stream().noneMatch(w -> w.contains(value))) continue;
            LocalDateTime civil = date.atStartOfDay().plusMinutes(minute);
            for (ZoneOffset offset : SchedulingPolicy.BUSINESS_ZONE.getRules().getValidOffsets(civil)) starts.add(civil.toInstant(offset));
        }
        return List.copyOf(starts);
    }
}
