package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.email.*;
import com.purpuragenda.adapters.out.postgres.PostgresBookingChanges;
import com.purpuragenda.application.notification.*;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.MailDelivery.Result;
import com.purpuragenda.application.port.out.OutboxStore.Outcome;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL outbox expansion and booking-notification delivery with synthetic mail capture only. */
@Timeout(240)
@ResourceLock("postgres-migration-fixture")
class BookingNotificationDatabaseTest {
    final CustomerCreationPostgresTest c=new CustomerCreationPostgresTest();
    @TempDir Path directory;
    PostgresOutboxStore outbox;PostgresNotificationQueue queue;PostgresBookingNotificationStore store;OutboxExpander expander;Capture mail;
    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void setup() throws Exception {
        c.directory=directory;c.setup();
        outbox=new PostgresOutboxStore(c.f.source);queue=new PostgresNotificationQueue(c.f.source,false);store=new PostgresBookingNotificationStore(c.f.source);
        expander=new OutboxExpander(outbox);mail=new Capture();
        c.jdbc.update("INSERT INTO admin_user(id,email_key,password_hash,totp_secret,enrolled,enabled) VALUES(?,?,?,?,true,true)",
                UUID.randomUUID(),"owner@example.test","synthetic-hash","synthetic-secret");
    }
    @AfterEach void cleanup() { c.cleanup(); }

    static final class Capture implements MailDelivery {
        final List<Message> sent=Collections.synchronizedList(new ArrayList<>());volatile Result result=Result.SENT;
        public Result send(Message m) { sent.add(m);return result; }
    }
    BookingNotificationWorker worker(MailDelivery delivery) { return new BookingNotificationWorker(queue,store,delivery,false); }
    BookingNotificationWorker worker() { return worker(mail); }
    UUID book(String email,int day,int hour) { var r=c.create(c.guest(email),"k-"+email+day,c.request(day,hour));assertTrue(r.success());return r.entityId(); }
    void event(UUID booking,String type,long version) {
        c.jdbc.update("INSERT INTO outbox_event(id,booking_id,type,version,created_at) VALUES(?,?,?,?,clock_timestamp())",UUID.randomUUID(),booking,type,version);
    }
    int count(String sql) { return c.count(sql); }
    /** Delivery attempts of booking notifications only: the verified-guest fixture also delivers an authentication email. */
    int attempts(String where) { return count("delivery_attempt a JOIN notification_job j ON j.id=a.job_id WHERE j.template<>'AUTH' AND "+where); }
    String job(String column) { return c.f.value("SELECT "+column+" FROM notification_job WHERE template<>'AUTH'"); }
    List<BookingNotificationWorker.Outcome> drainWorker() {
        var outcomes=new ArrayList<BookingNotificationWorker.Outcome>();
        for(int i=0;i<40;i++){var o=worker().tick();if(o==BookingNotificationWorker.Outcome.IDLE)break;outcomes.add(o);}
        return outcomes;
    }

    @Test void requestEventsExpandToDistinctAudienceJobsOnceAndDeliverWithoutAnyIntakeData() {
        UUID id=book("customer@example.test",3,9);
        assertEquals(1,expander.drain(10));
        assertEquals(1,count("notification_job WHERE booking_id='"+id+"' AND audience='CUSTOMER' AND template='BOOKING_REQUESTED' AND version=1 AND state='QUEUED'"));
        assertEquals(1,count("notification_job WHERE booking_id='"+id+"' AND audience='OWNER' AND template='OWNER_REQUEST_RECEIVED' AND version=1 AND state='QUEUED'"));
        assertEquals(1,count("outbox_event WHERE expanded_at IS NOT NULL"));assertEquals(0,expander.drain(10));assertEquals(2,count("notification_job WHERE booking_id IS NOT NULL AND template<>'AUTH'"));
        assertEquals(List.of(BookingNotificationWorker.Outcome.SENT,BookingNotificationWorker.Outcome.SENT),drainWorker());
        assertEquals(2,mail.sent.size());
        assertEquals(Set.of("customer@example.test","owner@example.test"),new HashSet<>(mail.sent.stream().map(MailDelivery.Message::recipient).toList()));
        for(var m:mail.sent) {
            for(String forbidden:List.of("Synthetic","Customer","+32400123456","1990","Synthetic city","Synthetic intake","token"))
                assertFalse((m.subject()+m.text()).contains(forbidden),forbidden);
            assertTrue(m.text().contains(c.load(id).reference()));
        }
        assertEquals(2,count("notification_job WHERE template<>'AUTH' AND state='SENT' AND encrypted_envelope IS NULL"));assertEquals(2,attempts("a.success"));
        assertEquals(0,count("notification_job WHERE template<>'AUTH' AND state='QUEUED'"));
    }

    @Test void unknownTypesFailVisiblyWithoutBlockingAndConfigurationConflictsNotifyNobody() {
        UUID id=book("customer@example.test",3,9);
        event(id,"SURPRISE",1);event(id,"CONFIGURATION_CONFLICT",1);
        assertEquals(3,expander.drain(10));
        assertEquals(1,count("notification_job WHERE template='UNKNOWN_OUTBOX_TYPE' AND state='FAILED' AND safe_error='UNKNOWN_OUTBOX_TYPE' AND audience='OWNER'"));
        assertEquals(2,count("notification_job WHERE template<>'UNKNOWN_OUTBOX_TYPE' AND template<>'AUTH'"));   // only the REQUESTED pair
        assertEquals(3,count("outbox_event WHERE expanded_at IS NOT NULL"));
        // The failed unknown job is never leased.
        assertEquals(2,drainWorker().size());assertEquals(1,count("notification_job WHERE template<>'AUTH' AND state='FAILED'"));
    }

    @Test void authenticationJobsAreNeverClaimedByTheBookingWorker() {
        c.f.delivery.issue(new com.purpuragenda.application.port.out.AuthDeliveryStore.Issue("customer@example.test",com.purpuragenda.application.port.out.AuthDeliveryStore.Purpose.CREATE,null,"a".repeat(64)));
        assertEquals(1,count("notification_job WHERE template='AUTH' AND state='QUEUED'"));
        assertEquals(BookingNotificationWorker.Outcome.IDLE,worker().tick());assertEquals(1,count("notification_job WHERE template='AUTH' AND state='QUEUED'"));
        assertEquals(0,mail.sent.size());assertTrue(queue.claimAuth().isPresent());
    }

    @Test void staleAndSupersededJobsAreSuppressedAndOnlyCurrentFactsAreSent() {
        var session=c.guest("customer@example.test");
        UUID id=c.create(session,"create",c.request(3,9)).entityId();
        assertEquals(1,expander.drain(10));
        // The booking is cancelled before the request notices are delivered.
        assertTrue(new com.purpuragenda.application.scheduling.CustomerBookingChanges(new PostgresBookingChanges(c.f.source))
                .cancel(c.binding(session),id,"cancel",new com.purpuragenda.application.scheduling.CustomerBookingChanges.Cancellation(1)).success());
        expander.drain(10);
        var outcomes=drainWorker();
        assertEquals(2,outcomes.stream().filter(o->o==BookingNotificationWorker.Outcome.DISCARDED).count());
        assertEquals(2,outcomes.stream().filter(o->o==BookingNotificationWorker.Outcome.SENT).count());
        assertEquals(2,count("notification_job WHERE state='SUPPRESSED' AND safe_error='BOOKING_NOT_PENDING' AND template IN ('BOOKING_REQUESTED','OWNER_REQUEST_RECEIVED')"));
        assertEquals(Set.of("Booking cancelled"),new HashSet<>(mail.sent.stream().map(MailDelivery.Message::subject).toList()));
        assertNotNull(session);
    }

    @Test void currentContactMeetingAndOwnerAreReadAtSendTimeAndAMissingOwnerSuppressesOwnerJobs() {
        UUID id=book("customer@example.test",3,9);event(id,"APPROVED",2);
        c.jdbc.update("DELETE FROM outbox_event WHERE type='REQUESTED'");
        c.jdbc.update("UPDATE booking SET state='CONFIRMED',version=2,expires_at=NULL,previously_confirmed=true,meeting_id='123456789',meeting_url='https://zoom.us/j/123456789',confirmation_basis='MANUAL_APPROVAL' WHERE id=?",id);
        c.jdbc.update("UPDATE booking SET delivery_email='changed@example.test' WHERE id=?",id);
        c.jdbc.update("UPDATE admin_user SET enabled=false");
        assertEquals(1,expander.drain(10));drainWorker();
        assertEquals(1,mail.sent.size());assertEquals("changed@example.test",mail.sent.getFirst().recipient());
        assertTrue(mail.sent.getFirst().text().contains("Meeting link: https://zoom.us/j/123456789"));
        assertEquals(1,count("notification_job WHERE audience='OWNER' AND state='SUPPRESSED' AND safe_error='RECIPIENT_UNAVAILABLE'"));
    }

    @Test void failedDeliveryFollowsTheDurableRetryScheduleThenTheFailedQueue() {
        UUID id=book("customer@example.test",3,9);event(id,"REFUND_RECORDED",1);
        c.jdbc.update("DELETE FROM outbox_event WHERE type='REQUESTED'");
        assertEquals(1,expander.drain(10));mail.result=Result.RETRY_LATER;
        long[] expectedSeconds={60,300,1800,7200,21600,43200};
        for(int attempt=0;attempt<7;attempt++) {
            assertEquals(BookingNotificationWorker.Outcome.RETRY_LATER,worker().tick(),"attempt "+attempt);
            if(attempt<6) {
                assertEquals(expectedSeconds[attempt],Long.valueOf(c.f.value("SELECT round(extract(epoch FROM j.due_at-(SELECT max(a.attempted_at) FROM delivery_attempt a WHERE a.job_id=j.id)))::bigint FROM notification_job j WHERE j.template<>'AUTH'")),"retry "+attempt);
                assertEquals("QUEUED",job("state"));
                assertEquals(BookingNotificationWorker.Outcome.IDLE,worker().tick());   // not due yet
                c.jdbc.update("UPDATE notification_job SET due_at=clock_timestamp() WHERE template<>'AUTH'");
            }
        }
        assertEquals("FAILED",job("state"));assertEquals("SMTP_DELIVERY_FAILED",job("safe_error"));
        assertEquals(7,attempts("NOT a.success"));assertEquals(BookingNotificationWorker.Outcome.IDLE,worker().tick());
        assertEquals(7,mail.sent.size());assertEquals(1,new HashSet<>(mail.sent.stream().map(MailDelivery.Message::jobId).toList()).size());   // stable job identity for Message-ID
    }

    @Test void staleLeasesCannotAcknowledgeSuppressOrDuplicateAndACrashAfterSmtpResendsWithTheSameJobIdentity() {
        UUID id=book("customer@example.test",3,9);event(id,"REFUND_RECORDED",1);c.jdbc.update("DELETE FROM outbox_event WHERE type='REQUESTED'");expander.drain(10);
        var first=queue.claimBooking().orElseThrow();
        // A worker sent the message (capture) but crashed before acknowledging: the lease expires and another worker resends.
        mail.send(new MailDelivery.Message(first.id(),"customer@example.test","Refund recorded","Synthetic"));
        c.jdbc.update("UPDATE notification_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",first.id());
        assertEquals(BookingNotificationWorker.Outcome.SENT,worker().tick());
        assertEquals(2,mail.sent.size());assertEquals(first.id(),mail.sent.get(0).jobId());assertEquals(first.id(),mail.sent.get(1).jobId());
        assertFalse(queue.sent(first));assertFalse(queue.failed(first));assertFalse(queue.suppressed(first,"BOOKING_NOT_PENDING"));assertFalse(queue.renew(first));
        assertEquals(1,attempts("a.success"));assertEquals("SENT",job("state"));
        assertThrows(IllegalArgumentException.class,()->queue.suppressed(first,"lowercase"));
    }

    @Test void operatorSuppressionBlocksClaimsAndALostLeaseNeverReportsDelivery() {
        UUID id=book("customer@example.test",3,9);event(id,"REFUND_RECORDED",1);c.jdbc.update("DELETE FROM outbox_event WHERE type='REQUESTED'");expander.drain(10);
        assertEquals(BookingNotificationWorker.Outcome.SUPPRESSED_BY_OPERATOR,new BookingNotificationWorker(queue,store,mail,true).tick());
        c.jdbc.update("UPDATE operation_control SET delivery_suppressed=true");
        assertEquals(BookingNotificationWorker.Outcome.IDLE,worker().tick());assertEquals(0,mail.sent.size());
        c.jdbc.update("UPDATE operation_control SET delivery_suppressed=false");
        // A lease expiring while the message is in flight is reported stale and the job stays retryable.
        MailDelivery slow=m->{c.jdbc.update("UPDATE notification_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",m.jobId());return Result.SENT;};
        assertEquals(BookingNotificationWorker.Outcome.STALE,worker(slow).tick());assertEquals(0,attempts("TRUE"));
    }

    @Test void expansionRollsBackWhenTheStoreFailsAndTwentyIndependentExpandersExpandEachEventExactlyOnce() throws Exception {
        UUID id=book("customer@example.test",3,9);
        for(int i=0;i<19;i++)event(id,"CANCELLED",2+i);
        assertEquals(20,count("outbox_event WHERE expanded_at IS NULL"));
        var failure=assertThrows(IllegalStateException.class,()->outbox.expandNext(type->{throw new IllegalArgumentException("SYNTHETIC_PRIVATE");}));
        assertEquals("NOTIFICATION_STORAGE_FAILED",failure.getMessage());assertNull(failure.getCause());
        assertEquals(20,count("outbox_event WHERE expanded_at IS NULL"));assertEquals(0,count("notification_job WHERE template<>'AUTH'"));
        var ready=new CountDownLatch(20);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<Future<Integer>>();
            for(int i=0;i<20;i++)futures.add(pool.submit(()->{var own=new OutboxExpander(new PostgresOutboxStore(c.f.source));ready.countDown();release.await();return own.drain(5);}));
            assertTrue(ready.await(5,TimeUnit.SECONDS));release.countDown();int total=0;for(var f:futures)total+=f.get(60,TimeUnit.SECONDS);assertEquals(20,total);
        }
        assertEquals(0,count("outbox_event WHERE expanded_at IS NULL"));assertEquals(40,count("notification_job WHERE template<>'AUTH'"));
        assertEquals(19,count("notification_job WHERE audience='CUSTOMER' AND template='BOOKING_CANCELLED'"));
        assertEquals(40,count("(SELECT DISTINCT event_id,audience,template,version FROM notification_job WHERE template<>'AUTH') s"));
    }

    @Test void twentyIndependentWorkersDeliverEveryJobExactlyOnce() throws Exception {
        for(int i=0;i<10;i++){UUID id=book("c"+i+"@example.test",3+i,9);event(id,"REFUND_RECORDED",1);}
        c.jdbc.update("DELETE FROM outbox_event WHERE type='REQUESTED'");assertEquals(10,expander.drain(50));
        var ready=new CountDownLatch(20);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<20;i++)futures.add(pool.submit(()->{
                var own=new BookingNotificationWorker(new PostgresNotificationQueue(c.f.source,false),new PostgresBookingNotificationStore(c.f.source),mail,false);
                ready.countDown();release.await();for(int n=0;n<3;n++)own.tick();return null;}));
            assertTrue(ready.await(5,TimeUnit.SECONDS));release.countDown();for(var f:futures)f.get(60,TimeUnit.SECONDS);
        }
        assertEquals(10,mail.sent.size());assertEquals(10,new HashSet<>(mail.sent.stream().map(MailDelivery.Message::jobId).toList()).size());
        assertEquals(10,count("notification_job WHERE template<>'AUTH' AND state='SENT'"));assertEquals(10,attempts("a.success"));
    }

    @Test void leasesThatAreNotLiveForTheJobCannotReadSuppressOrAcknowledgeAnything() {
        UUID id=book("customer@example.test",3,9);event(id,"REFUND_RECORDED",1);c.jdbc.update("DELETE FROM outbox_event WHERE type='REQUESTED'");expander.drain(10);
        var live=queue.claimBooking().orElseThrow();
        var other=new NotificationQueue.Lease(live.id(),UUID.randomUUID(),live.expiresAt());
        assertTrue(store.load(other).isEmpty());assertFalse(queue.suppressed(other,"SUPERSEDED"));assertFalse(queue.sent(other));
        assertTrue(store.load(new NotificationQueue.Lease(UUID.randomUUID(),live.token(),live.expiresAt())).isEmpty());
        // An authentication job is not a booking notification, even with a live lease.
        c.f.delivery.issue(new AuthDeliveryStore.Issue("customer@example.test",AuthDeliveryStore.Purpose.CREATE,null,"a".repeat(64)));
        var auth=queue.claimAuth().orElseThrow();assertTrue(store.load(auth).isEmpty());
        assertEquals("REFUND_RECORDED",store.load(live).orElseThrow().template());
        assertTrue(queue.suppressed(live,"SUPERSEDED"));assertEquals("SUPPRESSED",job("state"));assertEquals("SUPERSEDED",job("safe_error"));
        assertFalse(queue.suppressed(live,"SUPERSEDED"));assertTrue(store.load(live).isEmpty());   // no longer leased
        // An expired lease can neither read nor suppress.
        event(id,"REFUND_RECORDED",1);expander.drain(10);var expired=queue.claimBooking().orElseThrow();
        c.jdbc.update("UPDATE notification_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",expired.id());
        assertTrue(store.load(expired).isEmpty());assertFalse(queue.suppressed(expired,"SUPERSEDED"));
        assertThrows(NullPointerException.class,()->new PostgresBookingNotificationStore(null));assertThrows(NullPointerException.class,()->new PostgresOutboxStore(null));
        assertThrows(NullPointerException.class,()->outbox.expandNext(null));assertThrows(NullPointerException.class,()->store.load(null));
    }
}
