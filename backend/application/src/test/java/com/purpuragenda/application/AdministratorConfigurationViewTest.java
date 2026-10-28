package com.purpuragenda.application;

import com.purpuragenda.application.port.out.ConfigurationQuery;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.*;
import com.purpuragenda.application.scheduling.AdministratorConfiguration.PolicyValues;
import com.purpuragenda.application.scheduling.ConfigurationConflicts.Reason;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.scheduling.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdministratorConfigurationViewTest {
    final AdministratorConfigurationTest.Fixture f=new AdministratorConfigurationTest.Fixture();
    final SessionBinding session=new SessionBinding(UUID.randomUUID(),"e".repeat(64));
    final List<SessionBinding> reads=new ArrayList<>();
    RuntimeException denial;
    final AdministratorConfigurationView view=new AdministratorConfigurationView(new ConfigurationQuery() {
        @Override public <T> T read(SessionBinding binding,Function<View,T> work) { reads.add(binding);if(denial!=null)throw denial;return work.apply(f); }
    });
    Map<DayOfWeek,List<AvailabilityCalendar.Window>> monday(int from,int to) {
        return Map.of(DayOfWeek.MONDAY,List.of(new AvailabilityCalendar.Window(from,to)));
    }

    @Test void currentReturnsTheAuthorizedSnapshotVersionsAndTime() {
        var current=view.current(session);
        assertSame(f.calendar,current.calendar());assertSame(f.policy,current.policy());assertEquals(f.now,current.observedAt());assertEquals(List.of(session),reads);
        assertEquals(f.services(),current.services());assertThrows(UnsupportedOperationException.class,()->current.services().clear());
        assertThrows(NullPointerException.class,()->view.current(null));
    }

    @Test void calendarPreviewReportsNewConflictsAgainstTheCurrentVersionsWithoutWriting() {
        f.policy=new SchedulingPolicy(1,30,20,Duration.ZERO,Duration.ofDays(366),Duration.ZERO,Duration.ZERO);
        Booking booking=f.pending(Instant.parse("2026-01-05T08:00:00Z"));f.bookings=List.of(booking);
        // 09:00 Brussels on Monday is outside 10:00-12:00; omitted days are closed exactly as a replacement would store them.
        var preview=view.calendarConflicts(session,monday(600,720),Map.of(),List.of());
        assertEquals(1,preview.calendarVersion());assertEquals(1,preview.policyVersion());assertEquals(f.now,preview.assessedAt());
        assertEquals(List.of(booking.id()),preview.conflicts().stream().map(ConfigurationConflicts.Conflict::bookingId).toList());
        assertEquals(Set.of(Reason.START_RULES),preview.conflicts().getFirst().reasons());assertEquals("ConfigurationPreview[REDACTED]",preview.toString());
        var blackout=new AvailabilityCalendar.Blackout(UUID.randomUUID(),new ReservationInterval(booking.start(),booking.start().plusSeconds(60)),"Synthetic closure reason");
        var blocked=view.calendarConflicts(session,monday(0,1440),Map.of(),List.of(blackout));
        assertEquals(Set.of(blackout.id()),blocked.conflicts().getFirst().blackoutIds());
        assertTrue(view.calendarConflicts(session,monday(0,1440),Map.of(),List.of()).conflicts().isEmpty());
        assertEquals(0,f.saves);assertEquals(1,f.calendar.version());
    }

    @Test void policyPreviewEvaluatesTheNextVersionAndInvalidInputFailsBeforeAnyRead() {
        f.policy=new SchedulingPolicy(1,30,20,Duration.ZERO,Duration.ofDays(366),Duration.ZERO,Duration.ZERO);
        Booking first=f.pending(Instant.parse("2026-01-05T08:00:00Z")),second=f.pending(Instant.parse("2026-01-05T11:00:00Z"));f.bookings=List.of(first,second);
        var preview=view.policyConflicts(session,new PolicyValues(30,1,Duration.ZERO,Duration.ofHours(24),Duration.ZERO,Duration.ZERO));
        assertEquals(Set.of(Reason.DAILY_CAPACITY),preview.conflicts().getFirst().reasons());assertEquals(1,preview.policyVersion());
        assertThrows(DomainFailure.class,()->new PolicyValues(0,1,Duration.ZERO,Duration.ofHours(1),Duration.ZERO,Duration.ZERO));
        assertThrows(NullPointerException.class,()->view.policyConflicts(session,null));
        assertThrows(NullPointerException.class,()->view.calendarConflicts(session,null,Map.of(),List.of()));
        assertEquals(1,reads.size());
    }

    @Test void adapterDenialsPropagateUnchanged() {
        denial=new Failure(Failure.Kind.FORBIDDEN);
        assertSame(denial,assertThrows(Failure.class,()->view.current(session)));
        assertSame(denial,assertThrows(Failure.class,()->view.calendarConflicts(session,Map.of(),Map.of(),List.of())));
    }
}
