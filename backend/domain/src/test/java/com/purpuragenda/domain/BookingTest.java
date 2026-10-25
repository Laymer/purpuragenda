package com.purpuragenda.domain;

import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.customer.*;
import com.purpuragenda.domain.scheduling.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class BookingTest {
    static final Instant NOW=Instant.parse("2027-01-10T09:00:00Z"), START=Instant.parse("2027-01-12T14:30:00Z");
    static final Meeting MEETING=Meeting.parse("https://us02web.zoom.us/j/12345678901?pwd=abc123",Set.of("zoom.us"));
    static Booking pending(int minutes) {
        var service=new ServiceDefinition(UUID.randomUUID(),1,"Synthetic reading",minutes,Money.eur(1500),true);
        var intake=new Intake("Alex","Example","+32 (400) 123-456",LocalDate.of(1990,5,1),LocalTime.of(10,30,15),"Test town","Synthetic example");
        var snapshot=new Booking.Snapshot(service,SchedulingPolicy.defaults(),intake,"alex@example.test","America/Los_Angeles","local-draft");
        return Booking.pending(UUID.randomUUID(),"TEST-123456",UUID.randomUUID(),snapshot,Booking.Origin.CUSTOMER,NOW,START);
    }
    @ParameterizedTest @ValueSource(ints={1,30,60,120}) void actualSessionDiffersFromReservation(int minutes) {
        Booking booking=pending(minutes);
        assertEquals(minutes,Duration.between(booking.start(),booking.sessionEnd()).toMinutes());
        assertEquals(120,Duration.between(booking.start(),booking.reservedEnd()).toMinutes());
        assertEquals(LocalDate.of(2027,1,12),booking.businessDate());
    }
    @Test void approvalRequiresLiveHoldAndMeetingAndTerminalStatesNeverRevive() {
        Booking b=pending(30);assertTrue(b.livePending(b.expiresAt().minusNanos(1000)));
        assertFalse(b.livePending(b.expiresAt()));
        assertEquals("HOLD_EXPIRED",assertThrows(DomainFailure.class,()->b.approve(b.expiresAt(),MEETING,Booking.ConfirmationBasis.MANUAL_APPROVAL)).code());
        assertThrows(DomainFailure.class,()->b.approve(NOW,null,Booking.ConfirmationBasis.MANUAL_APPROVAL));
        var confirmed=b.approve(NOW,MEETING,Booking.ConfirmationBasis.MANUAL_APPROVAL);
        assertEquals(Booking.State.CONFIRMED,confirmed.state());assertTrue(confirmed.previouslyConfirmed());
        assertEquals(Booking.ConfirmationBasis.MANUAL_APPROVAL,confirmed.confirmationBasis());
        var cancelled=confirmed.cancel(NOW,true);assertFalse(cancelled.occupies(NOW));assertEquals(1,cancelled.calendarRevision());
        assertThrows(DomainFailure.class,()->cancelled.approve(NOW,MEETING,Booking.ConfirmationBasis.MANUAL_APPROVAL));
        assertEquals(Booking.State.EXPIRED,b.expire(b.expiresAt()).state());
        assertEquals(Booking.State.REJECTED,b.reject().state());
    }
    @Test void inclusiveCutoffHasMillisecondBoundaries() {
        var confirmed=pending(30).approve(NOW,MEETING,Booking.ConfirmationBasis.MANUAL_APPROVAL);
        Instant cutoff=START.minus(Duration.ofHours(24));
        assertTrue(confirmed.customerMayChange(cutoff.minusMillis(1)));assertTrue(confirmed.customerMayChange(cutoff));assertFalse(confirmed.customerMayChange(cutoff.plusMillis(1)));
        assertDoesNotThrow(()->confirmed.cancel(cutoff,true));assertThrows(DomainFailure.class,()->confirmed.cancel(cutoff.plusMillis(1),true));
        assertDoesNotThrow(()->confirmed.cancel(START,false));
    }
    @Test void pendingMoveNeverExtendsExpiryAndKeepsSnapshots() {
        var original=pending(30);var later=original.reschedule(NOW,START.plus(Duration.ofDays(1)),true);
        assertSame(original.snapshot(),later.snapshot());assertEquals(original.expiresAt(),later.expiresAt());
        Instant target=NOW.plus(Duration.ofHours(2));var earlier=original.reschedule(NOW,target,false);
        assertEquals(NOW.plus(Duration.ofHours(1)),earlier.expiresAt());
        assertThrows(DomainFailure.class,()->original.reschedule(NOW,NOW.plusSeconds(3600),false));
    }
    @Test void outcomesRetainDailyCapacityAndEntireRecoveryButNotActiveAllowance() {
        var confirmed=pending(60).approve(NOW,MEETING,Booking.ConfirmationBasis.MANUAL_APPROVAL);
        assertThrows(DomainFailure.class,()->confirmed.outcome(START.minusSeconds(1),Booking.State.COMPLETED,""));
        assertThrows(DomainFailure.class,()->confirmed.outcome(START,Booking.State.NO_SHOW,""));
        var outcome=confirmed.outcome(START,Booking.State.NO_SHOW,"Customer did not attend");
        assertTrue(outcome.occupies(START));assertFalse(outcome.active(START));assertEquals(confirmed.interval(),outcome.interval());
        assertFalse(confirmed.active(confirmed.sessionEnd()));
        assertDoesNotThrow(()->confirmed.outcome(confirmed.sessionEnd(),Booking.State.COMPLETED,""));
    }
    @Test void impossiblePendingCanBeImmediatelyConfirmedByAdmin() {
        var original=pending(30);
        assertThrows(DomainFailure.class,()->Booking.pending(UUID.randomUUID(),"TEST-1234",original.customerId(),original.snapshot(),Booking.Origin.ADMINISTRATOR,NOW,NOW.plusSeconds(1800)));
        assertDoesNotThrow(()->Booking.confirmed(UUID.randomUUID(),"TEST-1234",original.customerId(),original.snapshot(),Booking.Origin.ADMINISTRATOR,NOW,NOW.plusSeconds(1800),MEETING));
    }
    @Test void inputNormalizationAndInvalidMeetingSafety() {
        assertEquals("alex+label@xn--bcher-kva.example",Input.email(" Alex+Label@Bücher.example "));
        assertNotEquals(Input.email("alex+label@example.test"),Input.email("alex@example.test"));
        assertEquals("é",Input.text(" e\u0301 ",1,100));
        for (String email:List.of("a..b@example.test","x@bad_domain.test","x@example.test\nBcc:evil@example.test")) assertThrows(DomainFailure.class,()->Input.email(email));
        for(String url:List.of("http://zoom.us/j/12345678901","https://zoom.us.evil.test/j/12345678901","https://user@zoom.us/j/12345678901","https://zoom.us/s/12345678901","https://zoom.us/j/12345678901?zak=secret","https://zoom.us/j/12345678901#secret")) assertThrows(DomainFailure.class,()->Meeting.parse(url,Set.of("zoom.us")));
        assertThrows(DomainFailure.class,()->Input.phone("0400123456"));
        assertThrows(DomainFailure.class,()->new ServiceDefinition(UUID.randomUUID(),1,"Too long",121,Money.eur(1500),true));
        assertThrows(DomainFailure.class,()->Money.eur(-1));
    }
}
