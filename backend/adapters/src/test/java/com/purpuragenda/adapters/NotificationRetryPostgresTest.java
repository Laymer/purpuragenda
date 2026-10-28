package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.adapters.out.email.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.NotificationRetryUnitOfWork.*;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.port.out.MailDelivery;
import com.purpuragenda.application.notification.BookingNotificationWorker;
import com.purpuragenda.application.scheduling.AdministratorNotificationRetry;
import com.purpuragenda.application.scheduling.AdministratorNotificationRetry.Request;
import com.purpuragenda.application.scheduling.SchedulingCommands;
import com.purpuragenda.domain.booking.Booking;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(240)
@ResourceLock("postgres-migration-fixture")
class NotificationRetryPostgresTest {
    static final String REASON="Synthetic delivery retry reason";
    final AdminBookingPostgresTest b=new AdminBookingPostgresTest();@TempDir Path directory;
    CustomerCreationPostgresTest c;PostgresNotificationRetry unit;AdministratorNotificationRetry retry;UUID booking,job;
    @BeforeAll static void start(){MigrationTest.start();}
    @AfterAll static void stop(){MigrationTest.stop();}
    @BeforeEach void setup() throws Exception {b.directory=directory;b.setup();c=b.c;unit=new PostgresNotificationRetry(c.f.source,Set.of("zoom.us"));retry=new AdministratorNotificationRetry(unit);booking=b.create("create",b.request(3,10)).entityId();job=job(booking,"BOOKING_CREATED");}
    @AfterEach void cleanup(){b.cleanup();}
    UUID job(UUID target,String template){UUID id=UUID.randomUUID();c.jdbc.update("INSERT INTO notification_job(id,booking_id,audience,template,version,due_at,state,attempts,safe_error) VALUES(?,?,'CUSTOMER',?,1,clock_timestamp(),'FAILED',7,'SMTP_DELIVERY_FAILED')",id,target,template);return id;}
    Receipt retry(String key){return retry.retry(b.session,job,key,new Request(7,REASON));}
    Command command(String key){return new Command(b.session,job,key,CommandKey.fingerprint(List.of("7",REASON)));}
    int count(String table){return c.count(table);}
    Booking booking(){return c.scheduling.read(tx->tx.booking(booking).orElseThrow());}
    String state(){return c.jdbc.queryForObject("SELECT state FROM notification_job WHERE id=?",String.class,job);}
    @Test void queuesExactlyOneReasonedRetryAndPreservesOriginalJobBookingMoneyAndAttemptFacts() {
        var original=booking();int outbox=count("outbox_event");UUID attempt=UUID.randomUUID();c.jdbc.update("INSERT INTO delivery_attempt(id,job_id,attempted_at,success,safe_code) VALUES(?,?,clock_timestamp(),false,'SMTP_DELIVERY_FAILED')",attempt,job);
        Receipt result=retry("retry");assertTrue(result.success());assertEquals(1,result.version());assertEquals("QUEUED",state());assertEquals(original,booking());assertEquals(7,c.jdbc.queryForObject("SELECT attempts FROM notification_job WHERE id=?",Integer.class,job));
        assertEquals(1,count("notification_job"));assertEquals(1,count("delivery_attempt"));assertEquals(0,count("payment_entry"));assertEquals(outbox,count("outbox_event"));
        assertEquals(REASON,c.jdbc.queryForObject("SELECT reason FROM audit_event WHERE id=?",String.class,result.entityId()));assertEquals(b.adminId,c.jdbc.queryForObject("SELECT actor_id FROM audit_event WHERE id=?",UUID.class,result.entityId()));assertEquals(job.toString(),c.jdbc.queryForObject("SELECT metadata->>'jobId' FROM audit_event WHERE id=?",String.class,result.entityId()));
        assertEquals(1,count("notification_job j JOIN audit_event a ON a.metadata->>'jobId'=j.id::text WHERE j.due_at=a.occurred_at AND j.safe_error IS NULL AND j.lease_token IS NULL AND j.lease_until IS NULL"));
        assertEquals(result,retry("retry"));assertEquals(1,count("audit_event WHERE type='NOTIFICATION_RETRY_REQUESTED'"));assertEquals(Code.IDEMPOTENCY_KEY_REUSED,retry.retry(b.session,job,"retry",new Request(7,"Synthetic different retry reason")).code());
    }
    @Test void replayScopesUsePersistedAdministratorAndTargetJobInsteadOfSession() {
        Receipt first=retry("same");assertEquals(first,retry.retry(b.session(b.adminId,0),job,"same",new Request(7,REASON)));UUID other=job(booking,"BOOKING_CREATED");assertNotEquals(first,retry.retry(b.session,other,"same",new Request(7,REASON)));
        c.jdbc.update("UPDATE admin_user SET enabled=false WHERE id=?",b.adminId);var administrator=b.adminSession(UUID.randomUUID(),true);c.jdbc.update("UPDATE notification_job SET state='FAILED' WHERE id=?",job);assertNotEquals(first,retry.retry(administrator,job,"same",new Request(7,REASON)));
    }
    @ParameterizedTest @ValueSource(strings={"stale","disabled","unenrolled","epoch","revoked","browser"})
    void currentFreshAdministratorAuthorityPrecedesReceiptReplay(String change) {
        retry("authority");SessionBinding binding=b.session;switch(change){case "stale"->c.jdbc.update("UPDATE app_session SET issued_at=issued_at-interval '602 seconds',absolute_expires_at=absolute_expires_at-interval '602 seconds',authenticated_at=issued_at-interval '601 seconds' WHERE id=?",binding.sessionId());case "disabled"->c.jdbc.update("UPDATE admin_user SET enabled=false");case "unenrolled"->c.jdbc.update("UPDATE admin_user SET enrolled=false");case "epoch"->c.jdbc.update("UPDATE admin_user SET security_version=security_version+1");case "revoked"->c.jdbc.update("UPDATE app_session SET revoked_at=clock_timestamp() WHERE id=?",binding.sessionId());case "browser"->binding=new SessionBinding(binding.sessionId(),"f".repeat(64));}
        var denied=binding;assertEquals(change.equals("stale")?Failure.Kind.FRESH_AUTHENTICATION_REQUIRED:Failure.Kind.AUTHENTICATION_REQUIRED,b.fail(()->retry.retry(denied,job,"authority",new Request(7,REASON))).kind());assertEquals(1,count("audit_event WHERE type='NOTIFICATION_RETRY_REQUESTED'"));
    }
    @Test void staleCountsAndNonfailedJobsHaveStableBusinessReceiptsAndDoNotQueue() {
        assertEquals(Code.STALE_VERSION,retry.retry(b.session,job,"stale",new Request(6,REASON)).code());assertEquals(Code.STALE_VERSION,retry.retry(b.session,job,"stale",new Request(6,REASON)).code());
        for(String state:List.of("QUEUED","LEASED","SENT","SUPPRESSED")){c.jdbc.update("UPDATE notification_job SET state=? WHERE id=?",state,job);assertEquals(Code.NOTIFICATION_NOT_RETRYABLE,retry(state).code());assertEquals(Code.NOTIFICATION_NOT_RETRYABLE,retry(state).code());assertEquals(state,state());}assertEquals(0,count("audit_event WHERE type='NOTIFICATION_RETRY_REQUESTED'"));
    }
    @Test void operatorSuppressionBlocksNewRetriesButDoesNotEraseAnExistingReceipt() {
        var first=retry("old");c.jdbc.update("UPDATE notification_job SET state='FAILED' WHERE id=?",job);c.jdbc.update("UPDATE operation_control SET delivery_suppressed=true WHERE id=1");assertEquals(first,retry("old"));assertEquals(Code.DELIVERY_SUPPRESSED,retry("suppressed").code());assertEquals(Code.DELIVERY_SUPPRESSED,retry("suppressed").code());assertEquals("FAILED",state());assertEquals(1,count("audit_event WHERE type='NOTIFICATION_RETRY_REQUESTED'"));
    }
    @Test void unknownAuthenticationAndUnscopedJobsCannotBeRetriedAndAnonymousCannotProbeThem() {
        for(UUID target:List.of(UUID.randomUUID(),job(booking,"AUTH"),job(null,"BOOKING_CREATED")))assertEquals(Failure.Kind.NOT_FOUND,b.fail(()->retry.retry(b.session,target,"unknown",new Request(7,REASON))).kind());
        try(var anonymous=c.sessions.anonymous()){var session=c.binding(anonymous.session());assertEquals(Failure.Kind.FORBIDDEN,b.fail(()->retry.retry(session,UUID.randomUUID(),"unknown",new Request(7,REASON))).kind());}assertEquals(0,count("audit_event WHERE type='NOTIFICATION_RETRY_REQUESTED'"));
    }
    @Test void oneCapturedClockReclaimsExpiredHoldsBeforeRetryAndBeforeBusinessRejection() {
        Booking original=booking();Instant time=original.expiresAt();var reads=new AtomicInteger();c.jdbc.update("UPDATE app_session SET issued_at=?,last_seen_at=?,authenticated_at=?,absolute_expires_at=? WHERE id=?",Timestamp.from(time.minusSeconds(60)),Timestamp.from(time.minusSeconds(60)),Timestamp.from(time.minusSeconds(60)),Timestamp.from(time.minusSeconds(60).plus(Duration.ofHours(8))),b.session.sessionId());
        var fixed=new AdministratorNotificationRetry(new PostgresNotificationRetry(c.clock(time,reads),Set.of("zoom.us")));assertEquals(Code.STALE_VERSION,fixed.retry(b.session,job,"expiry-stale",new Request(6,REASON)).code());assertEquals(1,reads.get());assertEquals(Booking.State.EXPIRED,booking().state());assertEquals(original.version()+1,booking().version());
        assertTrue(fixed.retry(b.session,job,"expiry-retry",new Request(7,REASON)).success());assertEquals(2,reads.get());assertEquals(time,c.jdbc.queryForObject("SELECT due_at FROM notification_job WHERE id=?",Timestamp.class,job).toInstant());assertEquals(1,count("audit_event WHERE type='EXPIRED'"));
    }
    @Test void twentyIndependentConnectionsQueueOneRetryAndOneAudit() throws Exception {
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var gate=new CountDownLatch(1);var jobs=new ArrayList<Future<Receipt>>();for(int i=0;i<20;i++)jobs.add(pool.submit(()->{gate.await();return retry("race");}));gate.countDown();var first=jobs.getFirst().get(30,TimeUnit.SECONDS);for(var result:jobs)assertEquals(first,result.get(30,TimeUnit.SECONDS));}
        assertEquals(1,count("audit_event WHERE type='NOTIFICATION_RETRY_REQUESTED'"));assertEquals(1,count("idempotency_record WHERE key='race'"));assertEquals(7,c.jdbc.queryForObject("SELECT attempts FROM notification_job WHERE id=?",Integer.class,job));
    }
    @Test void ordinaryWorkerRechecksRelevanceAndKeepsTheSameMessageIdAndMonotonicAttempts() {
        var sent=new ArrayList<UUID>();MailDelivery mail=message->{sent.add(message.jobId());return MailDelivery.Result.SENT;};var worker=new BookingNotificationWorker(new PostgresNotificationQueue(c.f.source,false),new PostgresBookingNotificationStore(c.f.source),mail,false);
        retry("send");assertEquals(BookingNotificationWorker.Outcome.SENT,worker.tick());assertEquals(List.of(job),sent);assertEquals(8,c.jdbc.queryForObject("SELECT attempts FROM notification_job WHERE id=?",Integer.class,job));assertEquals("SENT",state());assertEquals(1,count("delivery_attempt"));
        UUID stale=job(booking,"BOOKING_CONFIRMED");assertTrue(retry.retry(b.session,stale,"stale-content",new Request(7,REASON)).success());assertEquals(BookingNotificationWorker.Outcome.DISCARDED,worker.tick());assertEquals(List.of(job),sent);assertEquals("SUPPRESSED",c.jdbc.queryForObject("SELECT state FROM notification_job WHERE id=?",String.class,stale));
    }
    @Test void malformedCallbacksCannotCommitPartialOrMismatchedEffects() {
        List<Function<Retry,Receipt>> invalid=List.of(
            tx->new Receipt(Code.OK,UUID.randomUUID(),1),tx->{tx.queued(UUID.randomUUID(),REASON);return Receipt.failure(Code.STALE_VERSION);},
            tx->{tx.authorizedJob();tx.authorizedJob();return Receipt.failure(Code.STALE_VERSION);},tx->{tx.authorizedJob();tx.queued(null,REASON);return Receipt.failure(Code.STALE_VERSION);},
            tx->{tx.authorizedJob();tx.queued(UUID.randomUUID(),"short");return Receipt.failure(Code.STALE_VERSION);},tx->{tx.authorizedJob();tx.queued(UUID.randomUUID()," "+REASON);return Receipt.failure(Code.STALE_VERSION);},
            tx->{tx.authorizedJob();tx.queued(UUID.randomUUID(),"Synthetic different reason");return Receipt.failure(Code.STALE_VERSION);},
            tx->{tx.authorizedJob();var id=UUID.randomUUID();tx.queued(id,REASON);tx.queued(UUID.randomUUID(),REASON);return new Receipt(Code.OK,id,1);},
            tx->{tx.authorizedJob();var id=UUID.randomUUID();tx.queued(id,REASON);return new Receipt(Code.OK,id,2);},
            tx->{tx.authorizedJob();tx.queued(UUID.randomUUID(),REASON);return new Receipt(Code.OK,UUID.randomUUID(),1);},
            tx->{tx.authorizedJob();tx.queued(UUID.randomUUID(),REASON);return Receipt.failure(Code.STALE_VERSION);},
            tx->{tx.authorizedJob();return Receipt.failure(Code.INVALID_PAYMENT);},tx->{tx.authorizedJob();return new Receipt(Code.OK,UUID.randomUUID(),1);},
            tx->{tx.authorizedJob();tx.save(tx.booking(booking).orElseThrow().reject());return Receipt.failure(Code.STALE_VERSION);},
            tx->{tx.chargeCreated(tx.booking(booking).orElseThrow());return Receipt.failure(Code.STALE_VERSION);},tx->{tx.expiryEffects(booking,1);return Receipt.failure(Code.STALE_VERSION);});
        for(int i=0;i<invalid.size();i++){var work=invalid.get(i);String key="bad-"+i;assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->unit.retry(command(key),tx->SchedulingCommands.within(tx,()->work.apply(tx)))).kind());}
        assertEquals("FAILED",state());assertEquals(0,count("audit_event WHERE type='NOTIFICATION_RETRY_REQUESTED'"));assertEquals(0,count("idempotency_record WHERE key LIKE 'bad-%'"));
    }
    @Test void callbacksCannotBypassAttemptsOrForgeExpiryAndSuppressionGuards() {
        List<Function<Retry,Receipt>> invalid=List.of(tx->{tx.authorizedJob();return Receipt.failure(Code.STALE_VERSION);},tx->{tx.queued(UUID.randomUUID(),REASON);return Receipt.failure(Code.STALE_VERSION);},tx->{tx.save(tx.booking(booking).orElseThrow());return Receipt.failure(Code.STALE_VERSION);},tx->{tx.save(tx.booking(booking).orElseThrow().reject());return Receipt.failure(Code.STALE_VERSION);},tx->{tx.expiryEffects(booking,1);return Receipt.failure(Code.STALE_VERSION);},tx->{tx.attempt(()->Receipt.failure(Code.STALE_VERSION));return tx.attempt(()->Receipt.failure(Code.STALE_VERSION));},tx->new Receipt(Code.OK,job,1));
        for(int i=0;i<invalid.size();i++){var work=invalid.get(i);String key="bypass-"+i;assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->unit.retry(command(key),work)).kind());}
        for(String state:List.of("SENT","FAILED")){c.jdbc.update("UPDATE notification_job SET state=? WHERE id=?",state,job);c.jdbc.update("UPDATE operation_control SET delivery_suppressed=? WHERE id=1",state.equals("FAILED"));assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->unit.retry(command("unsafe-"+state),tx->SchedulingCommands.within(tx,()->{tx.authorizedJob();var id=UUID.randomUUID();tx.queued(id,REASON);return new Receipt(Code.OK,id,1);}))).kind());}
        assertEquals(0,count("audit_event WHERE type='NOTIFICATION_RETRY_REQUESTED'"));
    }
    @Test void cachedReceiptsMustNameTheMatchingRetryAuditAndRejectCorruption() {
        var first=retry("cached");String scope=new Scope(b.adminId,"RETRY_NOTIFICATION",job).storageKey();
        for(String receipt:List.of("{\"code\":\"OK\",\"entityId\":\""+first.entityId()+"\",\"version\":2}","{\"code\":\"OK\",\"entityId\":\""+UUID.randomUUID()+"\",\"version\":1}","{\"code\":\"INVALID_PAYMENT\",\"entityId\":null,\"version\":0}")){c.jdbc.update("UPDATE idempotency_record SET receipt=?::jsonb WHERE scope=? AND key='cached'",receipt,scope);assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->retry("cached")).kind());}
        c.jdbc.update("UPDATE idempotency_record SET receipt=?::jsonb WHERE scope=? AND key='cached'","{\"code\":\"OK\",\"entityId\":\""+first.entityId()+"\",\"version\":1}",scope);
        c.jdbc.update("UPDATE notification_job SET state='FAILED' WHERE id=?",job);var other=retry.retry(b.session,job,"other",new Request(7,"Synthetic other retry reason"));
        c.jdbc.update("UPDATE idempotency_record SET receipt=jsonb_build_object('code','OK','entityId',?::text,'version',1) WHERE scope=? AND key='cached'",other.entityId().toString(),scope);assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->retry("cached")).kind());
        UUID malformed=UUID.randomUUID();c.jdbc.update("INSERT INTO audit_event(id,booking_id,actor_id,actor_kind,type,reason,occurred_at,version,metadata) VALUES(?,?,?,'ADMIN','NOTIFICATION_RETRY_REQUESTED',?,clock_timestamp(),1,jsonb_build_object('jobId',?::text))",malformed,booking,b.adminId,REASON,job.toString());
        c.jdbc.update("UPDATE idempotency_record SET receipt=jsonb_build_object('code','OK','entityId',?::text,'version',1) WHERE scope=? AND key='cached'",malformed.toString(),scope);assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->retry("cached")).kind());
    }
    @Test void suppressedDatabaseWritesCannotCommitPartialRetriesAndMaintenanceBlocksReplay() throws Exception {
        try(var connection=c.f.database.connection(MigrationTest.MIGRATOR)){MigrationTest.execute(connection,"CREATE FUNCTION suppress_retry() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$");
            for(String table:List.of("notification_job","audit_event")){String event=table.equals("notification_job")?"UPDATE":"INSERT";MigrationTest.execute(connection,"CREATE TRIGGER suppress_retry BEFORE "+event+" ON "+table+" FOR EACH ROW EXECUTE FUNCTION suppress_retry()");assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->retry("suppressed-"+table)).kind());assertEquals("FAILED",state());assertEquals(0,count("audit_event WHERE type='NOTIFICATION_RETRY_REQUESTED'"));assertEquals(0,count("idempotency_record WHERE key LIKE 'suppressed-%'"));MigrationTest.execute(connection,"DROP TRIGGER suppress_retry ON "+table);}}
        try(var connection=c.f.database.connection(MigrationTest.MIGRATOR)){MigrationTest.execute(connection,"CREATE FUNCTION corrupt_retry() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN NEW.state='FAILED'; RETURN NEW; END $$");MigrationTest.execute(connection,"CREATE TRIGGER corrupt_retry BEFORE UPDATE ON notification_job FOR EACH ROW EXECUTE FUNCTION corrupt_retry()");
            assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->retry("corrupt-effects")).kind());assertEquals("FAILED",state());assertEquals(0,count("audit_event WHERE type='NOTIFICATION_RETRY_REQUESTED'"));assertEquals(0,count("idempotency_record WHERE key='corrupt-effects'"));MigrationTest.execute(connection,"DROP TRIGGER corrupt_retry ON notification_job");}
        retry("existing");c.jdbc.update("UPDATE operation_control SET maintenance=true WHERE id=1");assertEquals(Failure.Kind.MAINTENANCE,b.fail(()->retry("existing")).kind());
    }
}
