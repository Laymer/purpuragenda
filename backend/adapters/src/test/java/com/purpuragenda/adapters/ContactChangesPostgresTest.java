package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.adapters.out.email.*;
import com.purpuragenda.adapters.out.security.PostgresRateLimits;
import com.purpuragenda.adapters.out.security.RateKey;
import com.purpuragenda.application.notification.*;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.ContactChangesUnitOfWork.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.*;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.Booking;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(240)
@ResourceLock("postgres-migration-fixture")
class ContactChangesPostgresTest {
    final CustomerCreationPostgresTest c=new CustomerCreationPostgresTest();
    @TempDir Path directory;
    RateKey rateKey;PostgresContactChanges adapter;CustomerContactChanges changes;SessionBinding owner;UUID booking;
    @BeforeAll static void start(){MigrationTest.start();}
    @AfterAll static void stop(){MigrationTest.stop();}
    @BeforeEach void setup() throws Exception {
        c.directory=directory;c.setup();byte[] rateBytes=new byte[32];Arrays.fill(rateBytes,(byte)7);rateKey=new RateKey(java.nio.file.Files.write(directory.resolve("contact-rate.key"),rateBytes),"contact-test-v1");Arrays.fill(rateBytes,(byte)0);adapter=new PostgresContactChanges(c.f.source,c.f.delivery);changes=app(adapter);
        var guest=c.guest("original@example.test");owner=c.binding(guest);booking=c.create(guest,"booking",c.request(3,9)).entityId();assertNotNull(booking);
    }
    @AfterEach void cleanup(){c.cleanup();}
    CustomerContactChanges app(PostgresContactChanges unit){return new CustomerContactChanges(unit,new com.purpuragenda.application.RequestThrottle(new PostgresRateLimits(c.f.source,rateKey)));}
    String proof(SessionBinding who,UUID id,String email) {
        adapter.request(new ProofRequest(who,id,email));var lease=c.f.queue.claimAuth().orElseThrow();var mail=c.f.delivery.prepare(lease).orElseThrow();
        assertTrue(mail.text().contains("&purpose=CONTACT&bookingId="+id));assertTrue(c.f.queue.sent(lease));return mail.text().split("#token=",2)[1].split("&",2)[0];
    }
    Receipt confirm(SessionBinding who,UUID id,String key,long version,String token){return changes.confirm(who,id,key,new CustomerContactChanges.Confirmation(version,token));}
    Receipt confirm(String key,long version,String token){return confirm(owner,booking,key,version,token);}
    int count(String sql){return c.count(sql);}
    void invalid(org.junit.jupiter.api.function.Executable work){assertEquals("INVALID_CONTACT_PROOF",assertThrows(DomainFailure.class,work).code());}

    @Test void proofAndChangeUseOneCapturedClockAndCommitOnlyDeliveryFactsWithReplay() {
        Booking before=c.load(booking);var clocks=new AtomicInteger();Instant now=c.now();
        var fixed=new PostgresContactChanges(c.clock(now,clocks),c.f.delivery);fixed.request(new ProofRequest(owner,booking,"next@example.test"));assertEquals(1,clocks.get());
        var lease=c.f.queue.claimAuth().orElseThrow();var mail=c.f.delivery.prepare(lease).orElseThrow();assertTrue(c.f.queue.sent(lease));String token=mail.text().split("#token=",2)[1].split("&",2)[0];
        clocks.set(0);var result=app(fixed).confirm(owner,booking,"change",new CustomerContactChanges.Confirmation(before.version(),token));assertEquals(1,clocks.get());
        assertEquals(before.deliveryContact("next@example.test"),c.load(booking));assertEquals(new Receipt(Code.OK,booking,2),result);
        assertEquals(result,confirm("change",1,token));assertEquals(Code.IDEMPOTENCY_KEY_REUSED,confirm("change",2,token).code());
        assertEquals(1,count("booking_contact_change"));assertEquals(1,count("outbox_event WHERE type='CONTACT_CHANGED'"));assertEquals(1,count("audit_event WHERE type='CONTACT_CHANGED'"));assertEquals(1,count("request_charge"));
        assertEquals("t",c.f.value("SELECT h.changed_at=e.created_at AND h.changed_at=a.occurred_at AND a.metadata='{}'::jsonb FROM booking_contact_change h JOIN outbox_event e ON e.id=h.id JOIN audit_event a ON a.id=h.id"));
        assertEquals("original@example.test",c.f.value("SELECT email_key FROM customer_identity WHERE id=?",before.customerId()));
        assertEquals("GUEST",c.f.value("SELECT actor_kind FROM audit_event WHERE type='CONTACT_CHANGED'"));
        invalid(()->confirm("consumed",2,token));
    }
    @Test void accountAuthorityNeverMovesOwnershipOrLoginToTheProvedMailbox() {
        var account=c.account();UUID id=c.command.create(account.binding(),"account-booking",c.request(4,9)).entityId();Booking before=c.load(id);
        String token=proof(account.binding(),id,"new-account-contact@example.test");assertTrue(confirm(account.binding(),id,"change",1,token).success());
        assertEquals(before.deliveryContact("new-account-contact@example.test"),c.load(id));assertEquals(account.customer(),c.load(id).customerId());
        assertEquals("ACCOUNT",c.f.value("SELECT actor_kind FROM audit_event WHERE type='CONTACT_CHANGED'"));
        c.jdbc.update("UPDATE customer_account SET credential_version=credential_version+1 WHERE id=?",account.id());
        assertEquals(Failure.Kind.FORBIDDEN,c.failure(()->confirm(account.binding(),id,"change",1,token)).kind());
    }
    @Test void unownedUnknownAndWrongScopeNeverIssueOrConsumeProofs() {
        var other=c.guest("other@example.test");UUID theirs=c.create(other,"other",c.request(4,9)).entityId();String token=proof(owner,booking,"next@example.test");
        int challenges=count("email_challenge");
        for(UUID id:List.of(UUID.randomUUID(),theirs)) {
            assertEquals(Failure.Kind.FORBIDDEN,c.failure(()->adapter.request(new ProofRequest(owner,id,"next@example.test"))).kind());
            assertEquals(Failure.Kind.FORBIDDEN,c.failure(()->confirm(owner,id,"wrong",1,token)).kind());
        }
        assertEquals(challenges,count("email_challenge"));assertEquals(0,count("booking_contact_change"));assertTrue(confirm("right",1,token).success());
    }
    @Test void revokedSessionsCannotReplayEarlierSuccessOrIssueAnotherProof() {
        String token=proof(owner,booking,"next@example.test");assertTrue(confirm("change",1,token).success());
        c.jdbc.update("UPDATE app_session SET revoked_at=clock_timestamp() WHERE id=?",owner.sessionId());
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,c.failure(()->confirm("change",1,token)).kind());
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,c.failure(()->adapter.request(new ProofRequest(owner,booking,"third@example.test"))).kind());
        assertEquals(1,count("booking_contact_change"));
    }
    @Test void proofPurposeBookingExpiryAndSingleUseAreMandatory() {
        String token=proof(owner,booking,"next@example.test");
        c.jdbc.update("UPDATE email_challenge SET purpose='MANAGE' WHERE purpose='CONTACT'");invalid(()->confirm("purpose",1,token));
        c.jdbc.update("UPDATE email_challenge SET purpose='CONTACT' WHERE email_key='next@example.test'");
        var other=c.guest("other@example.test");UUID theirs=c.create(other,"other",c.request(4,9)).entityId();
        c.jdbc.update("UPDATE email_challenge SET booking_id=? WHERE purpose='CONTACT'",theirs);invalid(()->confirm("booking",1,token));
        c.jdbc.update("UPDATE email_challenge SET booking_id=? WHERE purpose='CONTACT'",booking);
        Instant expires=c.jdbc.queryForObject("SELECT expires_at FROM email_challenge WHERE purpose='CONTACT'",(r,n)->r.getTimestamp(1).toInstant());
        var fixed=app(new PostgresContactChanges(c.clock(expires,new AtomicInteger()),c.f.delivery));
        invalid(()->fixed.confirm(owner,booking,"expired",new CustomerContactChanges.Confirmation(1,token)));
        assertEquals(0,count("booking_contact_change"));assertTrue(confirm("live",1,token).success());
    }
    @Test void staleVersionKeepsProofAvailableAndSuccessfulChangeInvalidatesOtherPendingProofs() {
        String first=proof(owner,booking,"next@example.test"),second=proof(owner,booking,"later@example.test");
        assertEquals(Code.STALE_VERSION,confirm("stale",9,first).code());assertEquals(0,count("email_challenge WHERE purpose='CONTACT' AND consumed_at IS NOT NULL"));
        assertTrue(confirm("current",1,first).success());invalid(()->confirm("obsolete",2,second));
        assertEquals(2,count("email_challenge WHERE purpose='CONTACT' AND consumed_at IS NOT NULL"));assertEquals(1,count("booking_contact_change"));
    }
    @Test void expiredHoldsAreReclaimedBeforeStaleVersionReceiptsWithoutConsumingTheProof() {
        String token=proof(owner,booking,"next@example.test");
        c.jdbc.update("UPDATE booking SET expires_at=clock_timestamp()-interval '1 microsecond' WHERE id=?",booking);
        assertEquals(Code.STALE_VERSION,confirm("stale-after-expiry",1,token).code());assertEquals(Booking.State.EXPIRED,c.load(booking).state());
        assertEquals(0,count("email_challenge WHERE purpose='CONTACT' AND consumed_at IS NOT NULL"));assertEquals(1,count("outbox_event WHERE type='EXPIRED'"));
        assertTrue(confirm("current-after-expiry",2,token).success());assertEquals(Booking.State.EXPIRED,c.load(booking).state());assertEquals(3,c.load(booking).version());
    }
    @Test void concurrentIdenticalRequestsConsumeExactlyOnceAcrossIndependentConnections() throws Exception {
        String token=proof(owner,booking,"next@example.test");var barrier=new CyclicBarrier(20);
        try(var pool=Executors.newFixedThreadPool(20)) {
            var futures=new ArrayList<Future<Receipt>>();for(int i=0;i<20;i++)futures.add(pool.submit(()->{barrier.await();return confirm("race",1,token);}));
            for(var future:futures)assertEquals(new Receipt(Code.OK,booking,2),future.get(45,TimeUnit.SECONDS));
        }
        assertEquals(1,count("booking_contact_change"));assertEquals(1,count("idempotency_record WHERE scope LIKE '%CHANGE_CONTACT%'"));assertEquals(1,count("outbox_event WHERE type='CONTACT_CHANGED'"));
    }
    @Test void invalidCallbacksCannotCommitUnverifiedOrUnnotifiedChanges() {
        String token=proof(owner,booking,"next@example.test");Booking before=c.load(booking);
        List<Function<Change,Receipt>> bad=List.of(
            tx->new Receipt(Code.OK,booking,2),
            tx->{tx.authorizedBooking();return new Receipt(Code.OK,booking,2);},
            tx->{tx.verifiedEmail();return new Receipt(Code.OK,booking,2);},
            tx->{tx.save(before.deliveryContact("unproved@example.test"));return new Receipt(Code.OK,booking,2);},
            tx->{tx.attempt(()->Receipt.failure(Code.STALE_VERSION));return tx.attempt(()->Receipt.failure(Code.STALE_VERSION));},
            tx->SchedulingCommands.within(tx,()->new Receipt(Code.OK,booking,2)),
            tx->SchedulingCommands.within(tx,()->Receipt.failure(Code.INVALID_TRANSITION)),
            tx->SchedulingCommands.within(tx,()->{tx.verifiedEmail();return new Receipt(Code.OK,booking,2);}),
            tx->SchedulingCommands.within(tx,()->{tx.authorizedBooking();tx.authorizedBooking();return new Receipt(Code.OK,booking,2);}),
            tx->SchedulingCommands.within(tx,()->{Booking b=tx.authorizedBooking();tx.save(b.deliveryContact("unproved@example.test"));return new Receipt(Code.OK,booking,2);}),
            tx->SchedulingCommands.within(tx,()->{Booking b=tx.authorizedBooking(),next=b.deliveryContact(tx.verifiedEmail());tx.save(next);return new Receipt(Code.OK,booking,2);}),
            tx->SchedulingCommands.within(tx,()->{Booking b=tx.authorizedBooking(),next=b.deliveryContact(tx.verifiedEmail());tx.contactChangedEffects(b,next);return new Receipt(Code.OK,booking,2);}),
            tx->SchedulingCommands.within(tx,()->{Booking b=tx.authorizedBooking(),next=b.deliveryContact(tx.verifiedEmail());tx.save(next);tx.save(next);return new Receipt(Code.OK,booking,2);}),
            tx->SchedulingCommands.within(tx,()->{Booking b=tx.authorizedBooking(),next=b.deliveryContact(tx.verifiedEmail());tx.save(next);tx.contactChangedEffects(b,next);return new Receipt(Code.OK,booking,3);}),
            tx->SchedulingCommands.within(tx,()->{Booking b=tx.authorizedBooking();tx.chargeCreated(b);return new Receipt(Code.OK,booking,2);}),
            tx->SchedulingCommands.within(tx,()->{Booking b=tx.authorizedBooking(),next=b.deliveryContact(tx.verifiedEmail());tx.save(next);tx.contactChangedEffects(next,next);return new Receipt(Code.OK,booking,2);}),
            tx->SchedulingCommands.within(tx,()->{Booking b=tx.authorizedBooking(),next=b.deliveryContact(tx.verifiedEmail());tx.save(next);tx.contactChangedEffects(b,b);return new Receipt(Code.OK,booking,2);}),
            tx->SchedulingCommands.within(tx,()->{Booking b=tx.authorizedBooking(),next=b.deliveryContact(tx.verifiedEmail());tx.save(next);tx.contactChangedEffects(b,next);tx.contactChangedEffects(b,next);return new Receipt(Code.OK,booking,2);}),
            tx->SchedulingCommands.within(tx,()->{Booking b=tx.authorizedBooking(),next=b.deliveryContact(tx.verifiedEmail());tx.save(next);return Receipt.failure(Code.STALE_VERSION);}),
            tx->SchedulingCommands.within(tx,()->{Booking b=tx.authorizedBooking(),next=b.deliveryContact(tx.verifiedEmail());tx.save(next);c.jdbc.update("UPDATE email_challenge SET consumed_at=clock_timestamp() WHERE purpose='CONTACT'");tx.contactChangedEffects(b,next);return new Receipt(Code.OK,booking,2);}),
            tx->SchedulingCommands.within(tx,()->{Booking b=tx.authorizedBooking(),next=b.deliveryContact(tx.verifiedEmail());tx.save(next);tx.contactChangedEffects(b,next);return new Receipt(Code.OK,UUID.randomUUID(),2);})
        );
        int index=0;for(var callback:bad) {
            var command=new Command(owner,booking,token,"wrong-"+index++,CommandKey.fingerprint(List.of("synthetic")));
            assertEquals(Failure.Kind.STORAGE_FAILURE,c.failure(()->adapter.change(command,callback)).kind());assertEquals(before,c.load(booking));
            assertEquals(0,count("booking_contact_change"));assertEquals(0,count("email_challenge WHERE purpose='CONTACT' AND consumed_at IS NOT NULL"));
        }
        assertTrue(confirm("valid",1,token).success());
    }
    @Test void forgedReceiptsNeverEscapeTheAuthorizedBookingAndVersion() {
        String token=proof(owner,booking,"next@example.test");assertTrue(confirm("change",1,token).success());
        for(String receipt:List.of("{\"code\":\"OK\",\"entityId\":\""+UUID.randomUUID()+"\",\"version\":2}","{\"code\":\"OK\",\"entityId\":\""+booking+"\",\"version\":1}","{\"code\":\"OK\",\"entityId\":\""+booking+"\",\"version\":999}","{\"code\":\"INVALID_TRANSITION\",\"entityId\":null,\"version\":0}")) {
            c.jdbc.update("UPDATE idempotency_record SET receipt=?::jsonb WHERE scope LIKE '%CHANGE_CONTACT%'",receipt);
            assertEquals(Failure.Kind.STORAGE_FAILURE,c.failure(()->confirm("change",1,token)).kind());
        }
    }
    @Test void historicalNoticesUseTheirOriginalRecipientsAndCarryNoCurrentBookingDetails() {
        String first=proof(owner,booking,"second@example.test");assertTrue(confirm("first",1,first).success());String second=proof(owner,booking,"third@example.test");assertTrue(confirm("second",2,second).success());
        new OutboxExpander(new PostgresOutboxStore(c.f.source)).drain(20);
        c.jdbc.update("UPDATE notification_job SET state='SUPPRESSED' WHERE template NOT IN ('AUTH','CONTACT_CHANGED','CONTACT_CHANGED_OLD')");
        var messages=new ArrayList<MailDelivery.Message>();var worker=new BookingNotificationWorker(c.f.queue,new PostgresBookingNotificationStore(c.f.source),m->{messages.add(m);return MailDelivery.Result.SENT;},false);
        for(int i=0;i<4;i++)assertEquals(BookingNotificationWorker.Outcome.SENT,worker.tick());
        assertEquals(BookingNotificationWorker.Outcome.IDLE,worker.tick());
        assertEquals(List.of("original@example.test","second@example.test","second@example.test","third@example.test"),messages.stream().map(MailDelivery.Message::recipient).sorted().toList());
        for(var message:messages){assertTrue(message.text().contains(c.load(booking).reference()));for(String forbidden:List.of("Synthetic","zoom.us","second@example.test","third@example.test","2026-"))assertFalse(message.text().contains(forbidden));}
    }
    @Test void failedContactEffectsRollBackProofConsumptionBookingHistoryAndReceipt() throws Exception {
        String token=proof(owner,booking,"next@example.test");Booking before=c.load(booking);
        try(var connection=c.f.database.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(connection,"CREATE FUNCTION reject_contact_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.type='CONTACT_CHANGED' THEN RAISE EXCEPTION 'SYNTHETIC_FAILURE'; END IF; RETURN NEW; END $$");
            MigrationTest.execute(connection,"CREATE TRIGGER reject_contact_event BEFORE INSERT ON outbox_event FOR EACH ROW EXECUTE FUNCTION reject_contact_event()");
        }
        assertEquals(Failure.Kind.STORAGE_FAILURE,c.failure(()->confirm("failed",1,token)).kind());assertEquals(before,c.load(booking));assertEquals(0,count("booking_contact_change"));assertEquals(0,count("email_challenge WHERE purpose='CONTACT' AND consumed_at IS NOT NULL"));assertEquals(0,count("idempotency_record WHERE scope LIKE '%CHANGE_CONTACT%'"));
    }
    @Test void suppressedProofDeliveryAndMaintenanceNeverCreatePartialChallenges() {
        int before=count("email_challenge");c.jdbc.update("UPDATE operation_control SET delivery_suppressed=true WHERE id=1");
        assertEquals(Failure.Kind.STORAGE_FAILURE,c.failure(()->adapter.request(new ProofRequest(owner,booking,"next@example.test"))).kind());assertEquals(before,count("email_challenge"));
        c.jdbc.update("UPDATE operation_control SET maintenance=true WHERE id=1");assertEquals(Failure.Kind.MAINTENANCE,c.failure(()->adapter.request(new ProofRequest(owner,booking,"next@example.test"))).kind());
        assertEquals(before,count("email_challenge"));
    }
}
