package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.email.*;
import com.purpuragenda.adapters.out.security.Secrets;
import com.purpuragenda.application.AuthDeliveryWorker;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.AuthDeliveryStore.*;
import com.purpuragenda.application.port.out.NotificationQueue.Lease;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.*;
import java.net.URI;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.postgresql.ds.PGSimpleDataSource;
import static com.purpuragenda.application.AuthDeliveryWorker.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(180)
@ResourceLock("postgres-migration-fixture")
class AuthWorkerDatabaseTest {
    final MigrationTest database=new MigrationTest();
    final ThreadLocal<AtomicInteger> active=ThreadLocal.withInitial(AtomicInteger::new);
    @TempDir Path directory;
    PostgresAuthDeliveryStore store; PostgresNotificationQueue queue; DataSource source; Secrets key;
    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void setup() throws Exception {
        database.fresh();var pg=new PGSimpleDataSource();pg.setURL(MigrationTest.jdbcUrl);
        pg.setUser(MigrationTest.APP);pg.setPassword(MigrationTest.appPassword);pg.setCurrentSchema(database.schema+",public");
        source=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(proxy,method,args)->{
            if(!method.getName().equals("getConnection")) return invoke(method,pg,args);
            var connection=(Connection)invoke(method,pg,args);var counter=active.get();counter.incrementAndGet();var closed=new AtomicBoolean();
            return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                try { return invoke(m,connection,a); }
                finally { if(m.getName().equals("close") && closed.compareAndSet(false,true)) counter.decrementAndGet(); }
            });
        });
        key=key("delivery",(byte)1);store=store(key,false);queue=new PostgresNotificationQueue(source,false);
    }
    static Object invoke(Method method,Object target,Object[] args) throws Throwable {
        try { return method.invoke(target,args); } catch(InvocationTargetException failure) { throw failure.getCause(); }
    }
    @AfterEach void cleanup() { database.cleanup(); }
    Secrets key(String name,byte value) throws Exception {
        byte[] bytes=new byte[32];Arrays.fill(bytes,value);Path file=directory.resolve(name);Files.write(file,bytes);Arrays.fill(bytes,(byte)0);
        return new Secrets(file,name);
    }
    PostgresAuthDeliveryStore store(Secrets secret,boolean suppressed) { return new PostgresAuthDeliveryStore(source,secret,URI.create("https://example.test"),suppressed); }
    Receipt issue() { return store.issue(new Issue("synthetic@example.test",Purpose.CREATE,null,"a".repeat(64))); }
    AuthDeliveryWorker worker(MailDelivery mail) { return new AuthDeliveryWorker(queue,store,mail,false); }
    void sql(String text,Object... values) {
        try(var c=database.connection(MigrationTest.APP)) { MigrationTest.execute(c,text,values); }
        catch(SQLException failure) { throw MigrationTest.safe(failure); }
    }
    long count(String text) { return database.scalar(MigrationTest.APP,text); }
    String text(String sql,Object... values) {
        try(var c=database.connection(MigrationTest.APP);var query=c.prepareStatement(sql)) {
            for(int i=0;i<values.length;i++) query.setObject(i+1,values[i]);
            try(var r=query.executeQuery()) { r.next();return r.getString(1); }
        } catch(SQLException failure) { throw MigrationTest.safe(failure); }
    }
    MimeMessage received(TestSmtpServer smtp) throws Exception {
        byte[] bytes=smtp.receive();assertNotNull(bytes);
        try { return new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(bytes)); }
        finally { Arrays.fill(bytes,(byte)0); }
    }
    MailDelivery capture(TestSmtpServer smtp) {
        var sender=SmtpDelivery.capture("127.0.0.1",smtp.port(),"sender@example.test");
        return message->{assertEquals(0,active.get().get(),"Network caller must hold no database connection");return sender.send(message);};
    }
    @Test void actualSmtpSucceedsOnlyAfterTransactionsCloseAndClearsCiphertext() throws Exception {
        var receipt=issue();
        try(var smtp=new TestSmtpServer(null,false,250,false)) {
            assertEquals(SENT,worker(capture(smtp)).tick());var message=received(smtp);
            assertTrue(message.getHeader("Message-ID",null).equals("<"+receipt.jobId()+"@purpuragenda>"));
            assertTrue(message.getContent().toString().contains("/verify#token="));
            assertEquals(1,count("SELECT count(*) FROM notification_job WHERE state='SENT' AND encrypted_envelope IS NULL"));
            assertEquals(1,count("SELECT count(*) FROM delivery_attempt WHERE success"));
        }
    }
    @Test void temporaryAndPermanentSmtpFailuresUseDurableBoundedRetries() throws Exception {
        for(int code:new int[]{451,550}) try(var smtp=new TestSmtpServer(null,false,code,false)) {
            var receipt=issue();assertEquals(RETRY_LATER,worker(capture(smtp)).tick());
            assertTrue(text("SELECT state='QUEUED' AND attempts=1 AND encrypted_envelope IS NOT NULL AND due_at>clock_timestamp()+interval '50 seconds' FROM notification_job WHERE id=?",receipt.jobId()).equals("t"));
            sql("UPDATE notification_job SET attempts=6,due_at=to_timestamp(0) WHERE id=?",receipt.jobId());
            assertEquals(RETRY_LATER,worker(capture(smtp)).tick());
            assertTrue(text("SELECT state='FAILED' AND attempts=7 AND encrypted_envelope IS NULL FROM notification_job WHERE id=?",receipt.jobId()).equals("t"));
        }
        assertEquals(4,count("SELECT count(*) FROM delivery_attempt WHERE NOT success AND safe_code='SMTP_DELIVERY_FAILED'"));
    }
    @Test void sweepErasesFutureConsumedMissingAndTerminalCiphertextWithoutDecrypting() throws Exception {
        for(String state:List.of("QUEUED","FAILED","SENT","SUPPRESSED")) {
            var receipt=issue();sql("UPDATE notification_job SET state=?,due_at=clock_timestamp()+interval '1 day' WHERE id=?",state,receipt.jobId());
            if(state.equals("QUEUED")) sql("UPDATE email_challenge SET expires_at=to_timestamp(0) WHERE id=?",receipt.challengeId());
        }
        var consumed=issue();sql("UPDATE email_challenge SET consumed_at=clock_timestamp() WHERE id=?",consumed.challengeId());
        var missing=issue();sql("UPDATE notification_job SET challenge_id=NULL WHERE id=?",missing.jobId());
        assertEquals(6,store(key("wrong",(byte)2),false).eraseExpired());
        assertEquals(0,count("SELECT count(*) FROM notification_job WHERE encrypted_envelope IS NOT NULL"));
        assertEquals(1,count("SELECT count(*) FROM notification_job WHERE state='SENT'"));
        assertEquals(1,count("SELECT count(*) FROM notification_job WHERE state='FAILED'"));
        assertEquals(0,store.eraseExpired());
    }
    @Test void sweepSkipsLockedRowsAndLiveLeasesUntilTheirOwnerExpires() throws Exception {
        var locked=issue();var live=issue();var lease=queue.claimAuth().orElseThrow();
        UUID lockedJob=lease.id().equals(locked.jobId())?live.jobId():locked.jobId();
        sql("UPDATE email_challenge SET consumed_at=clock_timestamp()");
        try(var connection=database.connection(MigrationTest.APP)) {
            connection.setAutoCommit(false);MigrationTest.execute(connection,"SELECT id FROM notification_job WHERE id=? FOR UPDATE",lockedJob);
            assertEquals(0,store.eraseExpired());connection.rollback();
        }
        assertEquals(1,store.eraseExpired());assertEquals(1,count("SELECT count(*) FROM notification_job WHERE encrypted_envelope IS NOT NULL"));
        sql("UPDATE notification_job SET lease_until=to_timestamp(0) WHERE id=?",lease.id());
        assertEquals(1,store.eraseExpired());assertFalse(queue.sent(lease));
    }
    @Test void sweepIsBoundedAndNeverRequiresDueTime() {
        for(int i=0;i<105;i++) issue();
        sql("UPDATE email_challenge SET expires_at=to_timestamp(0)");sql("UPDATE notification_job SET due_at=clock_timestamp()+interval '1 day'");
        assertEquals(100,store.eraseExpired());assertEquals(5,store.eraseExpired());assertEquals(0,store.eraseExpired());
    }
    @Test void authClaimNeverConsumesOrReclaimsBookingTemplateJobs() {
        UUID queued=UUID.randomUUID(),leased=UUID.randomUUID();
        sql("INSERT INTO notification_job(id,audience,template,due_at) VALUES(?,'CUSTOMER','BOOKING',to_timestamp(0))",queued);
        sql("INSERT INTO notification_job(id,audience,template,due_at,state,lease_until,lease_token) VALUES(?,'OWNER','BOOKING',to_timestamp(0),'LEASED',to_timestamp(0),?)",leased,UUID.randomUUID());
        UUID unsupported=UUID.randomUUID();
        sql("INSERT INTO notification_job(id,audience,template,due_at,encrypted_envelope) VALUES(?,'OWNER','AUTH',to_timestamp(0),'synthetic-invalid-ciphertext')",unsupported);
        assertEquals(IDLE,worker(message->{throw new AssertionError("No SMTP");}).tick());
        assertTrue(text("SELECT state='SUPPRESSED' AND attempts=0 AND encrypted_envelope IS NULL FROM notification_job WHERE id=?",unsupported).equals("t"));
        assertEquals(1,count("SELECT count(*) FROM notification_job WHERE template='BOOKING' AND state='LEASED'"));
        var receipt=issue();assertTrue(queue.claimAuth().orElseThrow().id().equals(receipt.jobId()));
        assertTrue(queue.claimAuth().isEmpty());assertTrue(queue.claim().isPresent());
        assertEquals(0,count("SELECT count(*) FROM notification_job WHERE template='BOOKING' AND encrypted_envelope IS NOT NULL"));
    }
    @Test void heartbeatKeepsBlockedSmtpLeaseOwnedAndCompetingWorkerCannotClaim() throws Exception {
        issue();var renewed=new CountDownLatch(1);var calls=new AtomicInteger();
        NotificationQueue heartbeatQueue=new NotificationQueue() {
            public Optional<Lease> claim() { throw new AssertionError("AUTH-only"); }
            public Optional<Lease> claimAuth() { return queue.claimAuth(); }
            public boolean renew(Lease lease) { boolean valid=queue.renew(lease);if(calls.incrementAndGet()>1) renewed.countDown();return valid; }
            public boolean sent(Lease lease) { return queue.sent(lease); }
            public boolean failed(Lease lease) { return queue.failed(lease); }
        };
        try(var smtp=new TestSmtpServer(null,false,250,false)) {
            MailDelivery blocked=message->{
                assertEquals(0,active.get().get());await(renewed);assertTrue(queue.claimAuth().isEmpty());
                return capture(smtp).send(message);
            };
            assertEquals(SENT,new AuthDeliveryWorker(heartbeatQueue,store,blocked,false,Duration.ofMillis(20)).tick());
            assertTrue(calls.get()>1);assertNotNull(received(smtp));assertEquals(1,count("SELECT count(*) FROM delivery_attempt WHERE success"));
        }
    }
    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5,TimeUnit.SECONDS)); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new AssertionError("SYNTHETIC_TIMEOUT"); }
    }
    @Test void lostLeaseAfterAcceptanceCannotOverwriteReplacementOrRecordAnAttempt() throws Exception {
        var receipt=issue();var replacement=new AtomicReference<Lease>();
        try(var smtp=new TestSmtpServer(null,false,250,false)) {
            MailDelivery lose=message->{
                var result=capture(smtp).send(message);sql("UPDATE notification_job SET lease_until=to_timestamp(0) WHERE id=?",receipt.jobId());
                replacement.set(queue.claimAuth().orElseThrow());return result;
            };
            assertEquals(STALE,worker(lose).tick());var first=received(smtp);
            assertEquals(0,count("SELECT count(*) FROM delivery_attempt"));
            assertTrue(text("SELECT lease_token::text FROM notification_job WHERE id=?",receipt.jobId()).equals(replacement.get().token().toString()));
            sql("UPDATE notification_job SET lease_until=to_timestamp(0) WHERE id=?",receipt.jobId());
            assertEquals(SENT,worker(capture(smtp)).tick());var second=received(smtp);
            assertTrue(first.getHeader("Message-ID",null).equals(second.getHeader("Message-ID",null)));
            assertEquals(1,count("SELECT count(*) FROM delivery_attempt WHERE success"));
        }
    }
    @Test void crashAfterAcceptanceBeforeAckRetainsCiphertextForStableIdReplay() throws Exception {
        var receipt=issue();
        try(var smtp=new TestSmtpServer(null,false,250,false)) {
            assertThrows(SyntheticCrash.class,()->worker(message->{capture(smtp).send(message);throw new SyntheticCrash();}).tick());
            var first=received(smtp);assertEquals(0,count("SELECT count(*) FROM delivery_attempt"));
            assertEquals(1,count("SELECT count(*) FROM notification_job WHERE state='LEASED' AND encrypted_envelope IS NOT NULL"));
            sql("UPDATE notification_job SET lease_until=to_timestamp(0) WHERE id=?",receipt.jobId());
            assertEquals(SENT,worker(capture(smtp)).tick());var second=received(smtp);
            assertTrue(first.getHeader("Message-ID",null).equals(second.getHeader("Message-ID",null)));
        }
    }
    static final class SyntheticCrash extends Error { }
    @Test void processAndDatabaseRestoreSuppressionNeverDecryptOrContactSmtp() throws Exception {
        try(var smtp=new TestSmtpServer(null,false,250,false)) {
            issue();var restore=store(key("wrong",(byte)2),true);
            assertEquals(SUPPRESSED,new AuthDeliveryWorker(queue,restore,capture(smtp),true).tick());
            assertEquals(1,count("SELECT count(*) FROM notification_job WHERE state='SUPPRESSED' AND encrypted_envelope IS NULL"));
            issue();sql("UPDATE operation_control SET delivery_suppressed=true");
            assertEquals(IDLE,new AuthDeliveryWorker(queue,store,capture(smtp),false).tick());
            assertEquals(0,smtp.acceptedCount());assertEquals(0,count("SELECT count(*) FROM notification_job WHERE encrypted_envelope IS NOT NULL"));
        }
    }
}
