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

    @Test void terminalStatesCannotBeRevivedOrEdited() {
        var pending=pending(30);
        var confirmed=pending.approve(NOW,MEETING,Booking.ConfirmationBasis.MANUAL_APPROVAL);
        var terminal=List.of(pending.cancel(NOW,false),pending.reject(),pending.expire(pending.expiresAt()),
                confirmed.cancel(NOW,false),confirmed.outcome(confirmed.sessionEnd(),Booking.State.COMPLETED,""),
                confirmed.outcome(confirmed.sessionEnd(),Booking.State.NO_SHOW,""));
        for(var booking:terminal) {
            assertFalse(booking.active(START));assertFalse(booking.customerMayChange(NOW));
            assertThrows(DomainFailure.class,()->booking.approve(NOW,MEETING,Booking.ConfirmationBasis.MANUAL_APPROVAL));
            assertThrows(DomainFailure.class,()->booking.reject());
            assertThrows(DomainFailure.class,()->booking.expire(NOW));
            assertThrows(DomainFailure.class,()->booking.cancel(NOW,false));
            assertThrows(DomainFailure.class,()->booking.reschedule(NOW,START.plusSeconds(86400),false));
            assertThrows(DomainFailure.class,()->booking.updateMeeting(MEETING));
            assertThrows(DomainFailure.class,()->booking.outcome(START,Booking.State.COMPLETED,""));
        }
        assertThrows(DomainFailure.class,()->pending.expire(pending.expiresAt().minusNanos(1000)));
        assertThrows(DomainFailure.class,()->pending.outcome(START,Booking.State.COMPLETED,""));
        assertThrows(DomainFailure.class,()->confirmed.outcome(START,Booking.State.CANCELLED,""));
        assertThrows(DomainFailure.class,()->pending.approve(NOW,MEETING,null));
    }

    @Test void reschedulingAndContactChangesPreserveSnapshotsAndIdentity() {
        var pending=pending(30);var original=pending.snapshot();
        assertEquals("alex@example.test",original.creationEmail());assertEquals("America/Los_Angeles",original.displayZone());
        assertEquals("local-draft",original.privacyNoticeVersion());assertEquals("Alex",original.intake().firstName());
        var changed=pending.deliveryContact("new@example.test");
        assertSame(original,changed.snapshot());assertEquals(pending.customerId(),changed.customerId());
        assertEquals("new@example.test",changed.deliveryEmail());assertEquals(2,changed.version());
        assertEquals(0,changed.calendarRevision());assertEquals(pending.expiresAt(),changed.expiresAt());
        var meeting=pending.updateMeeting(MEETING);assertEquals(Booking.State.PENDING_CONFIRMATION,meeting.state());
        assertEquals(0,meeting.calendarRevision());
        assertThrows(DomainFailure.class,()->pending.updateMeeting(null));
        var confirmed=meeting.approve(NOW,MEETING,Booking.ConfirmationBasis.EXTERNAL_PAYMENT);
        assertEquals(Booking.ConfirmationBasis.EXTERNAL_PAYMENT,confirmed.confirmationBasis());
        var moved=confirmed.reschedule(NOW,START.plusSeconds(86400),true);
        assertSame(original,moved.snapshot());assertEquals(confirmed.state(),moved.state());
        assertEquals(confirmed.confirmationBasis(),moved.confirmationBasis());assertEquals(1,moved.calendarRevision());
        assertEquals(1,confirmed.updateMeeting(MEETING).calendarRevision());
        assertThrows(DomainFailure.class,()->confirmed.reschedule(START.minusSeconds(3600),START.plusSeconds(86400),true));
        assertThrows(DomainFailure.class,()->confirmed.reschedule(NOW,NOW,false));
        assertFalse(pending.livePending(pending.expiresAt()));assertFalse(pending.occupies(pending.expiresAt()));
        assertFalse(pending.active(pending.expiresAt()));
        assertThrows(DomainFailure.class,()->pending.reschedule(pending.expiresAt(),START.plusSeconds(86400),false));
        assertThrows(DomainFailure.class,()->pending.cancel(pending.expiresAt(),false));
        assertThrows(DomainFailure.class,()->Booking.confirmed(UUID.randomUUID(),"TEST-1234",pending.customerId(),original,Booking.Origin.ADMINISTRATOR,NOW,NOW,MEETING));
        assertThrows(DomainFailure.class,()->Booking.confirmed(UUID.randomUUID(),"TEST-1234",pending.customerId(),original,Booking.Origin.ADMINISTRATOR,NOW,START,null));
    }

    private void invalidRecord(Booking source, Map<String,Object> replacements) throws Exception {
        var components=Booking.class.getRecordComponents();var values=new Object[components.length];var types=new Class<?>[components.length];
        for(int i=0;i<components.length;i++) {
            var component=components[i];types[i]=component.getType();
            values[i]=replacements.containsKey(component.getName())?replacements.get(component.getName()):component.getAccessor().invoke(source);
        }
        var error=assertThrows(java.lang.reflect.InvocationTargetException.class,()->Booking.class.getDeclaredConstructor(types).newInstance(values));
        assertInstanceOf(DomainFailure.class,error.getCause());
    }

    @Test void reconstructedBookingsRejectInvalidFieldsAndConfirmationHistory() throws Exception {
        var pending=pending(30);var confirmed=pending.approve(NOW,MEETING,Booking.ConfirmationBasis.MANUAL_APPROVAL);
        for(String field:List.of("id","customerId","snapshot","state","origin","createdAt","start","expiresAt")) {
            var nullValue=new HashMap<String,Object>();nullValue.put(field,null);invalidRecord(pending,nullValue);
        }
        invalidRecord(pending,Map.of("version",0L));invalidRecord(pending,Map.of("calendarRevision",-1));
        invalidRecord(pending,Map.of("start",pending.createdAt()));
        invalidRecord(pending,Map.of("expiresAt",pending.createdAt()));
        invalidRecord(pending,Map.of("expiresAt",NOW.plusSeconds(86401)));
        invalidRecord(pending,Map.of("start",NOW.plusSeconds(7200)));
        invalidRecord(pending,Map.of("confirmationBasis",Booking.ConfirmationBasis.MANUAL_APPROVAL));
        invalidRecord(pending,Map.of("previouslyConfirmed",true));
        invalidRecord(confirmed,Map.of("state",Booking.State.PENDING_CONFIRMATION));
        invalidRecord(confirmed,Map.of("previouslyConfirmed",false));
        var missingMeeting=new HashMap<String,Object>();missingMeeting.put("meeting",null);invalidRecord(confirmed,missingMeeting);
        var missingBasis=new HashMap<String,Object>();missingBasis.put("confirmationBasis",null);invalidRecord(confirmed,missingBasis);
        invalidRecord(pending,Map.of("state",Booking.State.CONFIRMED));
        var original=pending.snapshot();
        assertThrows(DomainFailure.class,()->new Booking.Snapshot(null,original.policy(),original.intake(),"alex@example.test","Europe/Brussels","v1"));
        assertThrows(DomainFailure.class,()->new Booking.Snapshot(original.service(),null,original.intake(),"alex@example.test","Europe/Brussels","v1"));
        assertThrows(DomainFailure.class,()->new Booking.Snapshot(original.service(),original.policy(),null,"alex@example.test","Europe/Brussels","v1"));
    }
}
