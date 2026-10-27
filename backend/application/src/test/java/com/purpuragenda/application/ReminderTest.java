package com.purpuragenda.application;

import com.purpuragenda.application.notification.*;
import com.purpuragenda.application.port.out.BookingNotificationStore.Facts;
import com.purpuragenda.application.port.out.OutboxStore.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReminderTest {
    static final Instant DUE=Instant.parse("2026-10-24T08:00:00Z");
    static final String MEETING="https://zoom.us/j/123456789";
    Spec spec(Reminder reminder){return new Spec("CUSTOMER",reminder.template(),reminder);}
    Facts facts(Reminder timing,Instant now,String state,Set<String> later,Instant due,Integer grace,String meeting,String audience) {
        return new Facts(UUID.randomUUID(),audience,timing.template(),3,"customer@example.test","B-SYNTH-REMINDER","Synthetic reading",
                state,DUE.plus(timing.lead()),null,meeting,later,now,due,grace);
    }
    Facts facts(Reminder timing,Instant now){return facts(timing,now,"CONFIRMED",Set.of(),DUE,timing.graceSeconds(),MEETING,"CUSTOMER");}
    String suppressed(Facts facts){return assertInstanceOf(NotificationContent.Suppress.class,NotificationContent.prepare(facts)).code();}
    void sends(Facts facts){assertInstanceOf(NotificationContent.Send.class,NotificationContent.prepare(facts));}

    @Test void onlyConfirmationCreationAndRescheduleHandlersAddTypedCustomerReminders() {
        for(String type:List.of("APPROVED","CONFIRMED_EXTERNAL_PAYMENT","ADMIN_CREATED","RESCHEDULED")) {
            var jobs=assertInstanceOf(Jobs.class,NotificationPlanner.plan(type));assertEquals(4,jobs.specs().size());
            assertEquals(List.of(Reminder.FORTY_EIGHT_HOURS,Reminder.TWO_HOURS),jobs.specs().stream().map(Spec::reminder).filter(Objects::nonNull).toList());
        }
        for(String type:List.of("REQUESTED","REJECTED","CANCELLED","EXPIRED","PAYMENT_RECORDED","REFUND_RECORDED","CONFIGURATION_CONFLICT"))
            assertTrue(((Jobs)NotificationPlanner.plan(type)).specs().stream().allMatch(s->s.reminder()==null));
        assertThrows(IllegalArgumentException.class,()->new Spec("OWNER","REMINDER_48H",Reminder.FORTY_EIGHT_HOURS));
        assertThrows(IllegalArgumentException.class,()->new Spec("CUSTOMER","REMINDER_2H",Reminder.FORTY_EIGHT_HOURS));
        assertThrows(IllegalArgumentException.class,()->new Spec("CUSTOMER","REMINDER_48H"));
    }
    @Test void generationSkipsPendingTerminalAndStrictlyPastDueButIncludesEquality() {
        for(Reminder timing:Reminder.values()) {
            Instant start=DUE.plus(timing.lead());
            for(String state:List.of("PENDING_CONFIRMATION","CANCELLED","REJECTED","EXPIRED","COMPLETED","NO_SHOW"))
                assertTrue(NotificationPlanner.schedule(spec(timing),state,start,DUE).isEmpty());
            for(Instant now:List.of(DUE.minusNanos(1000),DUE)) {
                var scheduled=NotificationPlanner.schedule(spec(timing),"CONFIRMED",start,now).orElseThrow();
                assertEquals(DUE,scheduled.dueAt());assertEquals(timing.graceSeconds(),scheduled.graceSeconds());
            }
            assertTrue(NotificationPlanner.schedule(spec(timing),"CONFIRMED",start,DUE.plusNanos(1000)).isEmpty());
        }
        var immediate=NotificationPlanner.schedule(new Spec("OWNER","OWNER_BOOKING_CREATED"),null,null,DUE).orElseThrow();
        assertEquals(DUE,immediate.dueAt());assertNull(immediate.graceSeconds());
    }
    @Test void delayedExpansionPreservesPreviouslyEligibleReminderButLateConfirmationStillSkipsIt() {
        var timing=Reminder.FORTY_EIGHT_HOURS;Instant start=DUE.plus(timing.lead()),dispatch=DUE.plusSeconds(1800);
        assertEquals(DUE,NotificationPlanner.schedule(spec(timing),"CONFIRMED",start,DUE.minusSeconds(1),dispatch).orElseThrow().dueAt());
        assertTrue(NotificationPlanner.schedule(spec(timing),"CONFIRMED",start,DUE.plusNanos(1000),dispatch).isEmpty());
        sends(facts(timing,dispatch));assertEquals("REMINDER_GRACE_EXPIRED",suppressed(facts(timing,DUE.plusSeconds(3600))));
    }
    String renderedTimeLine(Reminder timing,Instant start,Instant due) {
        var f=new Facts(UUID.randomUUID(),"CUSTOMER",timing.template(),3,"customer@example.test","B-SYNTH-REMINDER","Synthetic reading",
                "CONFIRMED",start,null,MEETING,Set.of(),due,due,timing.graceSeconds());
        var text=assertInstanceOf(NotificationContent.Send.class,NotificationContent.prepare(f)).message().text();
        return text.lines().filter(l->l.startsWith("Time: ")).reduce((a,b)->{throw new AssertionError("several Time lines");}).orElseThrow();
    }
    @Test void elapsedDeadlinesAndRenderedBrusselsWallClockStayExactAcrossDstAndLeapMonthEnds() {
        // start UTC, explicit 48h due, explicit 2h due, expected Brussels wall-clock Time line
        String[][] cases={
            {"2026-03-29T08:00:00Z","2026-03-27T08:00:00Z","2026-03-29T06:00:00Z","Time: Sunday 29 March 2026 at 10:00 (Brussels time; 2026-03-29T08:00:00Z UTC)"},
            {"2026-03-29T01:30:00Z","2026-03-27T01:30:00Z","2026-03-28T23:30:00Z","Time: Sunday 29 March 2026 at 03:30 (Brussels time; 2026-03-29T01:30:00Z UTC)"},
            {"2026-10-25T08:00:00Z","2026-10-23T08:00:00Z","2026-10-25T06:00:00Z","Time: Sunday 25 October 2026 at 09:00 (Brussels time; 2026-10-25T08:00:00Z UTC)"},
            {"2026-10-25T00:30:00Z","2026-10-23T00:30:00Z","2026-10-24T22:30:00Z","Time: Sunday 25 October 2026 at 02:30 (Brussels time; 2026-10-25T00:30:00Z UTC)"},
            {"2026-10-25T01:30:00Z","2026-10-23T01:30:00Z","2026-10-24T23:30:00Z","Time: Sunday 25 October 2026 at 02:30 (Brussels time; 2026-10-25T01:30:00Z UTC)"},
            {"2028-03-01T08:00:00Z","2028-02-28T08:00:00Z","2028-03-01T06:00:00Z","Time: Wednesday 1 March 2028 at 09:00 (Brussels time; 2028-03-01T08:00:00Z UTC)"}};
        for(String[] c:cases) {
            Instant start=Instant.parse(c[0]);
            Map<Reminder,Instant> due=Map.of(Reminder.FORTY_EIGHT_HOURS,Instant.parse(c[1]),Reminder.TWO_HOURS,Instant.parse(c[2]));
            for(Reminder timing:Reminder.values()) {
                var scheduled=NotificationPlanner.schedule(spec(timing),"CONFIRMED",start,start.minus(Duration.ofDays(3))).orElseThrow();
                assertEquals(due.get(timing),scheduled.dueAt(),c[0]+" "+timing);
                // Both reminders show the session's Brussels wall clock, never the due time or a shifted hour.
                assertEquals(c[3],renderedTimeLine(timing,start,scheduled.dueAt()),c[0]+" "+timing);
            }
        }
    }
    @Test void graceWindowsAreHalfOpenAndIndependentOfMutableRetryDueTimes() {
        for(Reminder timing:Reminder.values()) {
            assertEquals("REMINDER_NOT_DUE",suppressed(facts(timing,DUE.minusNanos(1000))));
            sends(facts(timing,DUE));sends(facts(timing,DUE.plusSeconds(timing.graceSeconds()).minusNanos(1000)));
            assertEquals("REMINDER_GRACE_EXPIRED",suppressed(facts(timing,DUE.plusSeconds(timing.graceSeconds()))));
            assertEquals("REMINDER_GRACE_EXPIRED",suppressed(facts(timing,DUE.plusSeconds(timing.graceSeconds()).plusNanos(1000))));
            assertEquals("SESSION_STARTED",suppressed(facts(timing,DUE.plus(timing.lead()))));
        }
    }
    @Test void currentStateRevisionRecipientAndMeetingDecideRelevanceWithoutSuppressingMoneyFacts() {
        var timing=Reminder.TWO_HOURS;
        assertEquals("BOOKING_NOT_CONFIRMED",suppressed(facts(timing,DUE,"PENDING_CONFIRMATION",Set.of(),DUE,900,MEETING,"CUSTOMER")));
        for(String later:List.of("RESCHEDULED","CANCELLED","EXPIRED","REJECTED","APPROVED","CONFIRMED_EXTERNAL_PAYMENT"))
            assertEquals("SUPERSEDED",suppressed(facts(timing,DUE,"CONFIRMED",Set.of(later),DUE,900,MEETING,"CUSTOMER")));
        sends(facts(timing,DUE,"CONFIRMED",Set.of("PAYMENT_RECORDED","REFUND_RECORDED"),DUE,900,MEETING,"CUSTOMER"));
        assertEquals("SUPERSEDED",suppressed(facts(timing,DUE,"CONFIRMED",Set.of(),DUE.plusSeconds(1),900,MEETING,"CUSTOMER")));
        assertEquals("INVALID_REMINDER_WINDOW",suppressed(facts(timing,DUE,"CONFIRMED",Set.of(),null,900,MEETING,"CUSTOMER")));
        for(Integer grace:Arrays.asList(null,0,3600))assertEquals("INVALID_REMINDER_WINDOW",suppressed(facts(timing,DUE,"CONFIRMED",Set.of(),DUE,grace,MEETING,"CUSTOMER")));
        assertEquals("MEETING_UNAVAILABLE",suppressed(facts(timing,DUE,"CONFIRMED",Set.of(),DUE,900,null,"CUSTOMER")));
        assertEquals("INVALID_REMINDER_AUDIENCE",suppressed(facts(timing,DUE,"CONFIRMED",Set.of(),DUE,900,MEETING,"OWNER")));
        var message=assertInstanceOf(NotificationContent.Send.class,NotificationContent.prepare(facts(timing,DUE.plusSeconds(800)))).message();
        assertEquals("Consultation reminder",message.subject());assertTrue(message.text().contains(MEETING));
        assertTrue(message.text().contains("B-SYNTH-REMINDER")&&message.text().contains("Brussels time"));
        assertFalse(message.text().contains("in 2 hours"));assertFalse(message.text().contains("birth"));
    }
}
