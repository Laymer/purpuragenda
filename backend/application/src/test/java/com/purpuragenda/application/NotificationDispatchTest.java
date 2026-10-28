package com.purpuragenda.application;

import com.purpuragenda.application.notification.*;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.BookingNotificationStore.Facts;
import com.purpuragenda.application.port.out.MailDelivery.Result;
import com.purpuragenda.application.port.out.OutboxStore.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NotificationDispatchTest {
    static final Instant NOW=Instant.parse("2026-11-02T10:00:00Z"),START=Instant.parse("2026-11-10T08:00:00Z");
    static final String ZOOM="https://zoom.us/j/123456789";

    // ---- planner: an explicit handler for every outbox type ----
    @Test void everyKnownOutboxTypeHasAnExplicitHandlerAndOthersAreUnknown() {
        Map<String,List<String>> expected=new LinkedHashMap<>();
        expected.put("REQUESTED",List.of("CUSTOMER:BOOKING_REQUESTED","OWNER:OWNER_REQUEST_RECEIVED"));
        expected.put("ADMIN_CREATED",List.of("CUSTOMER:BOOKING_CREATED","OWNER:OWNER_BOOKING_CREATED"));
        expected.put("APPROVED",List.of("CUSTOMER:BOOKING_CONFIRMED","OWNER:OWNER_BOOKING_CONFIRMED"));
        expected.put("CONFIRMED_EXTERNAL_PAYMENT",List.of("CUSTOMER:BOOKING_CONFIRMED","OWNER:OWNER_BOOKING_CONFIRMED"));
        expected.put("REJECTED",List.of("CUSTOMER:BOOKING_REJECTED","OWNER:OWNER_BOOKING_REJECTED"));
        expected.put("EXPIRED",List.of("CUSTOMER:BOOKING_EXPIRED","OWNER:OWNER_BOOKING_EXPIRED"));
        expected.put("CANCELLED",List.of("CUSTOMER:BOOKING_CANCELLED","OWNER:OWNER_BOOKING_CANCELLED"));
        expected.put("RESCHEDULED",List.of("CUSTOMER:BOOKING_RESCHEDULED","OWNER:OWNER_BOOKING_RESCHEDULED"));
        expected.put("PAYMENT_RECORDED",List.of("CUSTOMER:PAYMENT_RECORDED","OWNER:OWNER_PAYMENT_RECORDED"));
        expected.put("REFUND_RECORDED",List.of("CUSTOMER:REFUND_RECORDED","OWNER:OWNER_REFUND_RECORDED"));
        expected.put("PAYMENT_RECONCILIATION_REQUIRED",List.of("OWNER:OWNER_ACTION_RECONCILIATION"));
        expected.put("REFUND_REQUIRED",List.of("OWNER:OWNER_ACTION_REFUND"));
        expected.put("CONTACT_CHANGED",List.of("CUSTOMER:CONTACT_CHANGED","CUSTOMER:CONTACT_CHANGED_OLD"));
        expected.put("MEETING_CHANGED",List.of("CUSTOMER:BOOKING_MEETING_CHANGED","OWNER:OWNER_BOOKING_MEETING_CHANGED"));
        expected.put("CONFIGURATION_CONFLICT",List.of());
        for(String type:List.of("ADMIN_CREATED","APPROVED","CONFIRMED_EXTERNAL_PAYMENT","RESCHEDULED")) {
            var all=new ArrayList<>(expected.get(type));all.add("CUSTOMER:REMINDER_48H");all.add("CUSTOMER:REMINDER_2H");expected.put(type,all);
        }
        expected.forEach((type,specs)->{
            var plan=assertInstanceOf(Jobs.class,NotificationPlanner.plan(type),type);
            assertEquals(specs,plan.specs().stream().map(s->s.audience()+":"+s.template()).toList(),type);
        });
        for(String type:new String[]{"SURPRISE","requested","",null})assertInstanceOf(Unknown.class,NotificationPlanner.plan(type));
        assertThrows(IllegalArgumentException.class,()->new Spec("ADMIN","X"));assertThrows(IllegalArgumentException.class,()->new Spec("OWNER","bad template"));
    }

    // ---- expander ----
    static final class FakeOutbox implements OutboxStore {
        final Deque<String> events=new ArrayDeque<>();final List<Plan> plans=new ArrayList<>();boolean fail;
        public Outcome expandNext(Function<String,Plan> planner) {
            if(fail)throw new IllegalArgumentException("SYNTHETIC_PRIVATE");
            if(events.isEmpty())return Outcome.IDLE;
            var plan=planner.apply(events.poll());plans.add(plan);
            return plan instanceof Unknown?Outcome.UNKNOWN_TYPE:((Jobs)plan).specs().isEmpty()?Outcome.NO_NOTIFICATION:Outcome.EXPANDED;
        }
    }
    @Test void unknownAndSilentTypesNeverBlockLaterEventsAndStorageFailuresAreFixedAndCauseFree() {
        var store=new FakeOutbox();store.events.addAll(List.of("SURPRISE","CONFIGURATION_CONFLICT","REQUESTED"));
        var expander=new OutboxExpander(store);
        assertEquals(3,expander.drain(10));assertEquals(3,store.plans.size());assertInstanceOf(Unknown.class,store.plans.getFirst());
        assertEquals(OutboxStore.Outcome.IDLE,expander.tick());
        store.events.addAll(List.of("A","B","C"));assertEquals(2,expander.drain(2));
        store.fail=true;var failure=assertThrows(IllegalStateException.class,expander::tick);
        assertEquals("OUTBOX_EXPANSION_FAILED",failure.getMessage());assertNull(failure.getCause());
        assertThrows(IllegalArgumentException.class,()->expander.drain(0));assertThrows(IllegalArgumentException.class,()->expander.drain(1001));
        assertThrows(NullPointerException.class,()->new OutboxExpander(null));
    }

    // ---- content: relevance and privacy ----
    Facts facts(String audience,String template,String state,Set<String> later,Instant expires,String recipient) {
        return new Facts(UUID.randomUUID(),audience,template,3,recipient,"B-SYNTH-1","Synthetic service",state,START,expires,ZOOM,later,NOW);
    }
    NotificationContent.Prepared prepare(String audience,String template,String state,Set<String> later) {
        return NotificationContent.prepare(facts(audience,template,state,later,state.equals("PENDING_CONFIRMATION")?NOW.plusSeconds(3600):null,
                audience.equals("CUSTOMER")?"customer@example.test":"owner@example.test"));
    }
    String code(NotificationContent.Prepared p){return assertInstanceOf(NotificationContent.Suppress.class,p).code();}
    MailDelivery.Message message(NotificationContent.Prepared p){return assertInstanceOf(NotificationContent.Send.class,p).message();}

    @Test void contactSecurityNoticesSurviveLaterChangesWithoutLeakingCurrentBookingDetails() {
        for(String template:List.of("CONTACT_CHANGED","CONTACT_CHANGED_OLD")) {
            var notice=message(prepare("CUSTOMER",template,"CANCELLED",Set.of("CONTACT_CHANGED","RESCHEDULED","CANCELLED")));
            assertEquals("Booking delivery contact changed",notice.subject());assertTrue(notice.text().contains("B-SYNTH-1"));assertTrue(notice.text().contains("does not change booking ownership"));
            for(String privateValue:List.of("Synthetic service",ZOOM,START.toString(),"customer@example.test"))assertFalse(notice.text().contains(privateValue));
            assertEquals("INVALID_CONTACT_AUDIENCE",code(prepare("OWNER",template,"CONFIRMED",Set.of())));
            assertEquals("RECIPIENT_UNAVAILABLE",code(NotificationContent.prepare(facts("CUSTOMER",template,"CONFIRMED",Set.of(),null,null))));
        }
    }

    @Test void staleSupersededAndExpiredJobsAreSuppressedWithFixedCodes() {
        var none=Set.<String>of();
        assertEquals("BOOKING_NOT_PENDING",code(prepare("CUSTOMER","BOOKING_REQUESTED","CONFIRMED",none)));
        assertEquals("BOOKING_NOT_PENDING",code(NotificationContent.prepare(facts("OWNER","OWNER_REQUEST_RECEIVED","PENDING_CONFIRMATION",none,NOW,"o@example.test"))));   // hold expiry equality is expired
        assertEquals("SUPERSEDED",code(prepare("CUSTOMER","BOOKING_CREATED","PENDING_CONFIRMATION",Set.of("CANCELLED"))));
        assertEquals("BOOKING_NOT_ACTIVE",code(prepare("CUSTOMER","BOOKING_CREATED","CANCELLED",none)));
        assertEquals("SUPERSEDED",code(prepare("CUSTOMER","BOOKING_CONFIRMED","CONFIRMED",Set.of("RESCHEDULED"))));
        assertEquals("SUPERSEDED",code(prepare("OWNER","OWNER_BOOKING_CONFIRMED","CANCELLED",Set.of("CANCELLED"))));
        assertEquals("BOOKING_NOT_CONFIRMED",code(prepare("CUSTOMER","BOOKING_CONFIRMED","PENDING_CONFIRMATION",none)));
        assertEquals("SUPERSEDED",code(prepare("CUSTOMER","BOOKING_RESCHEDULED","CONFIRMED",Set.of("RESCHEDULED"))));
        assertEquals("BOOKING_NOT_ACTIVE",code(prepare("CUSTOMER","BOOKING_RESCHEDULED","EXPIRED",none)));
        assertEquals("BOOKING_NOT_CANCELLED",code(prepare("CUSTOMER","BOOKING_CANCELLED","CONFIRMED",none)));
        assertEquals("BOOKING_NOT_EXPIRED",code(prepare("CUSTOMER","BOOKING_EXPIRED","CONFIRMED",none)));
        assertEquals("BOOKING_NOT_REJECTED",code(prepare("CUSTOMER","BOOKING_REJECTED","CONFIRMED",none)));
        for(String later:List.of("CANCELLED","RESCHEDULED","MEETING_CHANGED"))
            assertEquals("SUPERSEDED",code(prepare("CUSTOMER","BOOKING_MEETING_CHANGED","CONFIRMED",Set.of(later))));
        assertEquals("BOOKING_NOT_ACTIVE",code(prepare("OWNER","OWNER_BOOKING_MEETING_CHANGED","COMPLETED",none)));
        var meeting=message(prepare("CUSTOMER","BOOKING_MEETING_CHANGED","CONFIRMED",Set.of("PAYMENT_RECORDED")));
        assertEquals("Meeting link changed",meeting.subject());assertTrue(meeting.text().contains("Meeting link: "+ZOOM));
        assertFalse(message(prepare("CUSTOMER","BOOKING_MEETING_CHANGED","PENDING_CONFIRMATION",none)).text().contains("Meeting link:"));
        assertEquals("Meeting link changed",message(prepare("OWNER","OWNER_BOOKING_MEETING_CHANGED","CONFIRMED",none)).subject());
        assertEquals("UNKNOWN_TEMPLATE",code(prepare("CUSTOMER","SURPRISE","CONFIRMED",none)));
        assertEquals("RECIPIENT_UNAVAILABLE",code(NotificationContent.prepare(facts("OWNER","OWNER_ACTION_REFUND","CANCELLED",none,null,null))));
    }

    @Test void supersededRequestsAndCompletedSessionsNeverSendActiveBookingNotices() {
        for(String audience:List.of("CUSTOMER","OWNER")) {
            String requested=audience.equals("CUSTOMER")?"BOOKING_REQUESTED":"OWNER_REQUEST_RECEIVED";
            assertEquals("SUPERSEDED",code(prepare(audience,requested,"PENDING_CONFIRMATION",Set.of("RESCHEDULED"))));
            String created=audience.equals("CUSTOMER")?"BOOKING_CREATED":"OWNER_BOOKING_CREATED";
            assertEquals("SUPERSEDED",code(prepare(audience,created,"PENDING_CONFIRMATION",Set.of("RESCHEDULED"))));
            assertEquals("SUPERSEDED",code(prepare(audience,created,"CONFIRMED",Set.of("RESCHEDULED"))));
            for(String state:List.of("COMPLETED","NO_SHOW")) {
                assertEquals("BOOKING_NOT_PENDING",code(prepare(audience,requested,state,Set.of())));
                String prefix=audience.equals("OWNER")?"OWNER_":"";
                assertEquals("BOOKING_NOT_ACTIVE",code(prepare(audience,prefix+"BOOKING_CREATED",state,Set.of())));
                assertEquals("BOOKING_NOT_CONFIRMED",code(prepare(audience,prefix+"BOOKING_CONFIRMED",state,Set.of())));
                assertEquals("BOOKING_NOT_ACTIVE",code(prepare(audience,prefix+"BOOKING_RESCHEDULED",state,Set.of())));
            }
        }
        assertEquals("Refund recorded",message(prepare("OWNER","OWNER_REFUND_RECORDED","CANCELLED",Set.of("CANCELLED"))).subject());
        assertEquals("Booking created",message(prepare("OWNER","OWNER_BOOKING_CREATED","CONFIRMED",Set.of())).subject());
        assertEquals("Booking created",message(prepare("OWNER","OWNER_BOOKING_CREATED","PENDING_CONFIRMATION",Set.of())).subject());
    }

    @Test void currentStateDecidesWhichMessageIsSentAndMessagesCarryOnlySafeFacts() {
        var none=Set.<String>of();
        var requested=message(prepare("CUSTOMER","BOOKING_REQUESTED","PENDING_CONFIRMATION",none));
        assertEquals("Booking request received",requested.subject());assertEquals("customer@example.test",requested.recipient());
        assertTrue(requested.text().contains("B-SYNTH-1")&&requested.text().contains("Synthetic service")&&requested.text().contains("2026-11-10T08:00:00Z")&&requested.text().contains("09:00"));
        assertFalse(requested.text().contains(ZOOM));
        var confirmed=message(prepare("CUSTOMER","BOOKING_CONFIRMED","CONFIRMED",Set.of("PAYMENT_RECORDED")));
        assertEquals("Booking confirmed",confirmed.subject());assertTrue(confirmed.text().contains("Meeting link: "+ZOOM));
        assertTrue(message(prepare("CUSTOMER","BOOKING_CREATED","CONFIRMED",none)).text().contains("created and confirmed"));
        assertEquals("A booking request was created for you",message(prepare("CUSTOMER","BOOKING_CREATED","PENDING_CONFIRMATION",none)).subject());
        assertFalse(message(prepare("OWNER","OWNER_BOOKING_CONFIRMED","CONFIRMED",none)).text().contains("Meeting link"));
        assertTrue(message(prepare("OWNER","OWNER_REQUEST_RECEIVED","PENDING_CONFIRMATION",none)).text().contains("administrator area"));
        for(String[] row:new String[][]{{"BOOKING_RESCHEDULED","CONFIRMED","Booking time changed"},{"BOOKING_CANCELLED","CANCELLED","Booking cancelled"},
                {"BOOKING_EXPIRED","EXPIRED","Booking request expired"},{"BOOKING_REJECTED","REJECTED","Booking request declined"}})
            assertEquals(row[2],message(prepare("CUSTOMER",row[0],row[1],none)).subject());
        // Factual money notices and owner action alerts are never stale, whatever the booking became.
        assertEquals("Payment recorded",message(prepare("CUSTOMER","PAYMENT_RECORDED","CANCELLED",Set.of("CANCELLED"))).subject());
        assertEquals("Refund recorded",message(prepare("CUSTOMER","REFUND_RECORDED","EXPIRED",none)).subject());
        assertEquals("Action needed: refund",message(prepare("OWNER","OWNER_ACTION_REFUND","CANCELLED",none)).subject());
        assertEquals("Action needed: payment reconciliation",message(prepare("OWNER","OWNER_ACTION_RECONCILIATION","EXPIRED",none)).subject());
        assertEquals("Payment recorded",message(prepare("OWNER","OWNER_PAYMENT_RECORDED","CONFIRMED",none)).subject());
        // Facts have no field for intake, notes or tokens: names, telephone and birth data cannot reach a body.
        assertEquals(List.of("jobId","audience","template","jobVersion","recipient","reference","serviceName","state","start","expiresAt","meetingUrl","laterEventTypes","now","scheduledAt","graceSeconds"),
                Arrays.stream(Facts.class.getRecordComponents()).map(c->c.getName()).toList());
        var sample=facts("CUSTOMER","BOOKING_REQUESTED","CONFIRMED",none,null,"a@example.test");
        assertEquals("NotificationFacts[job="+sample.jobId()+", content=REDACTED]",sample.toString());
    }

    // ---- worker ----
    static class Ports implements NotificationQueue,BookingNotificationStore,MailDelivery {
        final List<String> calls=Collections.synchronizedList(new ArrayList<>());final AtomicInteger renewals=new AtomicInteger();
        Runnable onSend=()->{},onHeartbeat=()->{},onLoad=()->{};
        final Lease lease=new Lease(UUID.randomUUID(),UUID.randomUUID(),Instant.MAX);
        Optional<Lease> claimed=Optional.of(lease);Optional<Facts> facts;Result result=Result.SENT;
        boolean renewed=true,acknowledged=true,transportThrows,storeThrows;String suppressedWith;
        Ports(){facts=Optional.of(new Facts(lease.id(),"CUSTOMER","BOOKING_REQUESTED",1,"customer@example.test","B-SYNTH-1","Synthetic service","PENDING_CONFIRMATION",START,NOW.plusSeconds(3600),null,Set.of(),NOW));}
        @Override public Optional<Lease> claim() { throw new AssertionError("The general claim could lease AUTH jobs"); }
        @Override public Optional<Lease> claimAuth() { throw new AssertionError("Authentication jobs are not booking notifications"); }
        @Override public Optional<Lease> claimBooking() { calls.add("claim");return claimed; }
        @Override public boolean renew(Lease l) { calls.add("renew");if(renewals.incrementAndGet()>2)onHeartbeat.run();return renewed; }
        @Override public boolean sent(Lease l) { calls.add("sent");return acknowledged; }
        @Override public boolean failed(Lease l) { calls.add("failed");return acknowledged; }
        @Override public boolean suppressed(Lease l,String code) { calls.add("suppressed");suppressedWith=code;return acknowledged; }
        @Override public Optional<Facts> load(Lease l) { calls.add("load");onLoad.run();if(storeThrows)throw new IllegalArgumentException("SYNTHETIC_PRIVATE");return facts; }
        @Override public Result send(Message m) { calls.add("send");onSend.run();if(transportThrows)throw new IllegalArgumentException("SYNTHETIC_PRIVATE");return result; }
        BookingNotificationWorker worker(boolean suppressed) { return new BookingNotificationWorker(this,this,this,suppressed); }
    }
    @Test void successfulTickClaimsBookingJobsRenewsLoadsCurrentFactsThenSendsAndAcknowledges() {
        var p=new Ports();assertEquals(BookingNotificationWorker.Outcome.SENT,p.worker(false).tick());
        assertEquals(List.of("claim","renew","load","renew","send","sent"),p.calls);assertTrue(p.worker(false).toString().contains("REDACTED"));
    }
    @Test void idleOperatorSuppressionLostLeaseVanishedBookingAndStaleJobsNeverSend() {
        var p=new Ports();assertEquals(BookingNotificationWorker.Outcome.SUPPRESSED_BY_OPERATOR,p.worker(true).tick());assertEquals(List.of(),p.calls);
        p=new Ports();p.claimed=Optional.empty();assertEquals(BookingNotificationWorker.Outcome.IDLE,p.worker(false).tick());
        p=new Ports();p.renewed=false;assertEquals(BookingNotificationWorker.Outcome.STALE,p.worker(false).tick());assertEquals(List.of("claim","renew"),p.calls);
        p=new Ports();p.facts=Optional.empty();assertEquals(BookingNotificationWorker.Outcome.STALE,p.worker(false).tick());assertEquals(List.of("claim","renew","load"),p.calls);
        p=new Ports();p.facts=Optional.of(new Facts(p.lease.id(),"CUSTOMER","BOOKING_CANCELLED",2,"customer@example.test","B-SYNTH-1","Synthetic service","CONFIRMED",START,null,null,Set.of(),NOW));
        assertEquals(BookingNotificationWorker.Outcome.DISCARDED,p.worker(false).tick());assertEquals("BOOKING_NOT_CANCELLED",p.suppressedWith);assertEquals(List.of("claim","renew","load","suppressed"),p.calls);
        p.acknowledged=false;assertEquals(BookingNotificationWorker.Outcome.STALE,p.worker(false).tick());
    }
    @Test void suppressionOrLeaseLossDuringPreparationPreventsNetworkDelivery() {
        var p=new Ports();p.onLoad=()->p.renewed=false;
        assertEquals(BookingNotificationWorker.Outcome.STALE,p.worker(false).tick());
        assertEquals(List.of("claim","renew","load","renew"),p.calls);
    }

    @Test void everyNonSentOutcomeUsesTheDurableFailurePolicyAndFailuresAreFixedAndCauseFree() {
        for(var result:Result.values())if(result!=Result.SENT) {
            var p=new Ports();p.result=result;assertEquals(BookingNotificationWorker.Outcome.RETRY_LATER,p.worker(false).tick());assertEquals("failed",p.calls.getLast());
        }
        var p=new Ports();p.transportThrows=true;assertEquals(BookingNotificationWorker.Outcome.RETRY_LATER,p.worker(false).tick());
        var thrower=new Ports();thrower.storeThrows=true;var failure=assertThrows(IllegalStateException.class,()->thrower.worker(false).tick());
        assertEquals("NOTIFICATION_WORKER_FAILED",failure.getMessage());assertNull(failure.getCause());
        p=new Ports();p.acknowledged=false;assertEquals(BookingNotificationWorker.Outcome.STALE,p.worker(false).tick());
        p=new Ports();p.acknowledged=false;p.result=Result.RETRY_LATER;assertEquals(BookingNotificationWorker.Outcome.STALE,p.worker(false).tick());
    }
    @Test void heartbeatRenewsDuringABlockedSendAndALostHeartbeatPreventsAcknowledgement() throws Exception {
        var p=new Ports();var renewed=new CountDownLatch(1);p.onHeartbeat=renewed::countDown;
        p.onSend=()->{try{assertTrue(renewed.await(2,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException();}};
        assertEquals(BookingNotificationWorker.Outcome.SENT,new BookingNotificationWorker(p,p,p,false,Duration.ofMillis(10)).tick());assertTrue(p.renewals.get()>=2);
        var q=new Ports();var lost=new CountDownLatch(1);q.onHeartbeat=()->{q.renewed=false;lost.countDown();};
        q.onSend=()->{try{assertTrue(lost.await(2,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException();}};
        assertEquals(BookingNotificationWorker.Outcome.STALE,new BookingNotificationWorker(q,q,q,false,Duration.ofMillis(10)).tick());assertFalse(q.calls.contains("sent"));
        for(Duration bad:new Duration[]{null,Duration.ZERO,Duration.ofSeconds(-1),Duration.ofSeconds(21)})
            assertThrows(IllegalArgumentException.class,()->new BookingNotificationWorker(p,p,p,false,bad));
        assertThrows(NullPointerException.class,()->new BookingNotificationWorker(null,p,p,false));
    }

    @Test void planSpecsAreImmutableCopies() {
        var specs=new ArrayList<>(List.of(new Spec("OWNER","OWNER_ACTION_REFUND")));var jobs=new Jobs(specs);specs.clear();
        assertEquals(1,jobs.specs().size());assertThrows(UnsupportedOperationException.class,()->jobs.specs().clear());
    }

    @Test void everySendableTemplateCarriesThePrivacyLineAndNoRecipientAddressInTheBody() {
        var none=Set.<String>of();
        for(String[] row:new String[][]{{"CUSTOMER","BOOKING_REQUESTED","PENDING_CONFIRMATION"},{"CUSTOMER","BOOKING_CONFIRMED","CONFIRMED"},{"OWNER","OWNER_BOOKING_CANCELLED","CANCELLED"},
                {"CUSTOMER","PAYMENT_RECORDED","CONFIRMED"},{"OWNER","OWNER_ACTION_REFUND","CANCELLED"}}) {
            var m=message(prepare(row[0],row[1],row[2],none));
            assertTrue(m.text().endsWith("This message deliberately contains no personal intake details.\n"),row[1]);
            assertFalse(m.text().contains(m.recipient()),row[1]);
        }
    }
}
