package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.email.*;
import com.purpuragenda.adapters.out.security.Secrets;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.AuthDeliveryStore.*;
import com.purpuragenda.application.port.out.NotificationQueue.Lease;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.postgresql.ds.PGSimpleDataSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(180)
@ResourceLock("postgres-migration-fixture")
class AuthDeliveryDatabaseTest {
    final MigrationTest database=new MigrationTest();
    @TempDir Path directory;
    UUID bindingBooking;
    PGSimpleDataSource source; Secrets key; PostgresAuthDeliveryStore store; PostgresNotificationQueue queue;
    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void setup() throws Exception {
        database.fresh(); source=new PGSimpleDataSource(); source.setURL(MigrationTest.jdbcUrl);
        source.setUser(MigrationTest.APP); source.setPassword(MigrationTest.appPassword); source.setCurrentSchema(database.schema+",public");
        key=key("delivery",(byte)1); store=store(key,false); queue=new PostgresNotificationQueue(source,false);
    }
    @AfterEach void cleanup() { database.cleanup(); }
    Secrets key(String name,byte value) throws Exception {
        byte[] bytes=new byte[32]; Arrays.fill(bytes,value); Path file=directory.resolve(name);
        Files.write(file,bytes); Arrays.fill(bytes,(byte)0); return new Secrets(file,name);
    }
    PostgresAuthDeliveryStore store(Secrets secret,boolean suppressed) { return new PostgresAuthDeliveryStore(source,secret,URI.create("https://example.test"),suppressed); }
    Issue request(Purpose purpose) {
        boolean browser=purpose==Purpose.CREATE || purpose==Purpose.ACCOUNT;
        if(!browser && bindingBooking==null) bindingBooking=database.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        UUID booking=browser?null:bindingBooking;
        return new Issue("synthetic@example.test",purpose,booking,browser?Secrets.hash("synthetic-browser"):null);
    }
    void sql(String text,Object... values) { try(var c=database.connection(MigrationTest.APP)) { MigrationTest.execute(c,text,values); } catch(SQLException e) { throw MigrationTest.safe(e); } }
    long count(String sql) { return database.scalar(MigrationTest.APP,sql); }
    String text(String sql,Object... values) {
        try(var c=database.connection(MigrationTest.APP);var s=c.prepareStatement(sql)) {
            for(int i=0;i<values.length;i++) s.setObject(i+1,values[i]);
            try(var r=s.executeQuery()) { r.next(); return r.getString(1); }
        } catch(SQLException e) { throw MigrationTest.safe(e); }
    }
    static void safe(org.junit.jupiter.api.function.Executable action) {
        var failure=assertThrows(IllegalStateException.class,action); assertEquals("AUTH_DELIVERY_STORAGE_FAILED",failure.getMessage());
        assertNull(failure.getCause()); assertEquals(0,failure.getSuppressed().length);
    }
    void erased(String code) {
        assertEquals(1,count("SELECT count(*) FROM notification_job WHERE state='SUPPRESSED' AND encrypted_envelope IS NULL AND lease_token IS NULL AND lease_until IS NULL AND safe_error='"+code+"'"));
    }
    String envelope(UUID job) { return key.decrypt(text("SELECT encrypted_envelope FROM notification_job WHERE id=?",job)); }
    void replace(Receipt receipt,int field,String value) throws Exception {
        byte[] decoded=Base64.getDecoder().decode(envelope(receipt.jobId()));
        var fields=new ArrayList<String>();
        try(var in=new DataInputStream(new ByteArrayInputStream(decoded))) { assertEquals(1,in.readInt()); for(int i=0;i<8;i++) fields.add(in.readUTF()); }
        fields.set(field,value);
        try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)) {
            out.writeInt(1); for(String item:fields) out.writeUTF(item);
            sql("UPDATE notification_job SET encrypted_envelope=? WHERE id=?",key.encrypt(Base64.getEncoder().encodeToString(bytes.toByteArray())),receipt.jobId());
        } finally { Arrays.fill(decoded,(byte)0); }
    }

    @Test void everyPurposePersistsOneAtomicBoundChallengeAndEncryptedJobWithFixedLifetime() {
        for(var purpose:Purpose.values()) {
            var issue=request(purpose); var receipt=store.issue(issue); var lease=queue.claim().orElseThrow();
            assertTrue(receipt.jobId().equals(lease.id())); var message=store.prepare(lease).orElseThrow();
            assertTrue(message.recipient().equals(issue.email())); assertTrue(message.text().contains("https://example.test/verify#token="));
            String token=message.text().split("#token=",2)[1].split("&",2)[0]; assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
            assertTrue(text("SELECT token_hash FROM email_challenge WHERE id=?",receipt.challengeId()).equals(Secrets.hash(token)));
            assertFalse(text("SELECT encrypted_envelope FROM notification_job WHERE id=?",receipt.jobId()).contains(token));
            assertTrue(text("SELECT expires_at=due_at+interval '15 minutes' FROM email_challenge c JOIN notification_job j ON j.challenge_id=c.id WHERE c.id=?",receipt.challengeId()).equals("t"));
            assertFalse(message.toString().contains(token)); assertTrue(queue.sent(lease));
        }
        assertEquals(4,count("SELECT count(*) FROM notification_job WHERE state='SENT' AND encrypted_envelope IS NULL"));
    }
    @Test void failedJobInsertRollsBackChallengeAndRestoreCannotIssue() {
        try(var c=database.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(c,"CREATE FUNCTION reject_auth_job() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'SYNTHETIC_FAILURE'; END $$");
            MigrationTest.execute(c,"CREATE TRIGGER reject_auth_job BEFORE INSERT ON notification_job FOR EACH ROW EXECUTE FUNCTION reject_auth_job()");
        } catch(SQLException e) { throw MigrationTest.safe(e); }
        safe(()->store.issue(request(Purpose.CREATE))); assertEquals(0,count("SELECT count(*) FROM email_challenge"));
        safe(()->store(key,true).issue(request(Purpose.CREATE))); assertEquals(0,count("SELECT count(*) FROM notification_job"));
    }
    @Test void databaseSuppressionPreventsIssueAndSuppressesAlreadyClaimedDelivery() {
        var receipt=store.issue(request(Purpose.CREATE)); var lease=queue.claim().orElseThrow();
        sql("UPDATE operation_control SET delivery_suppressed=true WHERE id=1");
        safe(()->store.issue(request(Purpose.ACCOUNT))); assertTrue(store.prepare(lease).isEmpty()); erased("DELIVERY_SUPPRESSED");
        assertEquals(1,count("SELECT count(*) FROM email_challenge")); assertFalse(queue.sent(lease));
    }
    @Test void restoreSuppressionErasesWithoutDecrypting() throws Exception {
        store.issue(request(Purpose.CREATE)); var lease=queue.claim().orElseThrow();
        assertTrue(store(key("different",(byte)2),true).prepare(lease).isEmpty()); erased("DELIVERY_SUPPRESSED");
    }
    @Test void expiredConsumedAndMissingCiphertextAreErased() {
        for(String alteration:new String[]{"expires_at=clock_timestamp()","consumed_at=clock_timestamp()","expires_at=to_timestamp(0)"}) {
            var receipt=store.issue(request(Purpose.CREATE)); var lease=queue.claim().orElseThrow();
            sql("UPDATE email_challenge SET "+alteration+" WHERE id=?",receipt.challengeId()); assertTrue(store.prepare(lease).isEmpty());
        }
        store.issue(request(Purpose.CREATE)); var lease=queue.claim().orElseThrow();
        sql("UPDATE notification_job SET encrypted_envelope=NULL WHERE id=?",lease.id()); assertTrue(store.prepare(lease).isEmpty());
        assertEquals(4,count("SELECT count(*) FROM notification_job WHERE state='SUPPRESSED' AND encrypted_envelope IS NULL AND safe_error='AUTH_DELIVERY_EXPIRED'"));
    }
    @Test void staleExpiredAndUnknownLeasesCannotReadOrEraseLiveEnvelopes() {
        var receipt=store.issue(request(Purpose.CREATE)); var lease=queue.claim().orElseThrow();
        assertTrue(store.prepare(new Lease(UUID.randomUUID(),lease.token(),lease.expiresAt())).isEmpty());
        assertTrue(store.prepare(new Lease(lease.id(),UUID.randomUUID(),lease.expiresAt())).isEmpty());
        sql("UPDATE notification_job SET lease_until=clock_timestamp() WHERE id=?",lease.id()); assertTrue(store.prepare(lease).isEmpty());
        var replacement=queue.claim().orElseThrow(); assertTrue(store.prepare(lease).isEmpty()); assertTrue(store.prepare(replacement).isPresent());
        assertEquals(1,count("SELECT count(*) FROM notification_job WHERE encrypted_envelope IS NOT NULL"));
        assertFalse(queue.sent(lease)); assertTrue(queue.sent(replacement));
        for(String missing:List.of("lease_token=NULL","lease_until=NULL")) {
            store.issue(request(Purpose.CREATE)); var incomplete=queue.claim().orElseThrow();
            sql("UPDATE notification_job SET "+missing+" WHERE id=?",incomplete.id());
            assertTrue(store.prepare(incomplete).isEmpty()); assertTrue(queue.sent(queue.claim().orElseThrow()));
        }
    }
    @Test void everyEnvelopeBindingAndTokenHashMustMatchCurrentDatabase() throws Exception {
        for(int field=0;field<8;field++) {
            var receipt=store.issue(request(Purpose.CREATE)); var lease=queue.claim().orElseThrow();
            String wrong=switch(field) { case 0,1,4->UUID.randomUUID().toString(); case 2->"other@example.test"; case 3->"ACCOUNT";
                case 5->"b".repeat(64); case 6->Instant.EPOCH.toString(); default->Secrets.token(); };
            replace(receipt,field,wrong); assertTrue(store.prepare(lease).isEmpty());
        }
        assertEquals(8,count("SELECT count(*) FROM notification_job WHERE state='SUPPRESSED' AND encrypted_envelope IS NULL AND safe_error='AUTH_DELIVERY_INVALID'"));
    }
    @Test void swappingCiphertextAndDifferentDeliveryKeyFailClosed() throws Exception {
        var first=store.issue(request(Purpose.CREATE)); var second=store.issue(request(Purpose.ACCOUNT));
        sql("UPDATE notification_job SET encrypted_envelope=? WHERE id=?",text("SELECT encrypted_envelope FROM notification_job WHERE id=?",second.jobId()),first.jobId());
        var lease=queue.claim().orElseThrow(); assertTrue(lease.id().equals(first.jobId())); assertTrue(store.prepare(lease).isEmpty());
        lease=queue.claim().orElseThrow(); assertTrue(store(key("wrong",(byte)2),false).prepare(lease).isEmpty());
        assertEquals(2,count("SELECT count(*) FROM notification_job WHERE safe_error='AUTH_DELIVERY_INVALID' AND encrypted_envelope IS NULL"));
    }
    @Test void malformedVersionTruncatedTrailingOrInvalidFieldsAreErased() throws Exception {
        for(String malformed:List.of("not-base64",Base64.getEncoder().encodeToString(new byte[]{0,0,0,2}),
                Base64.getEncoder().encodeToString(new byte[]{0,0,0,1}))) {
            store.issue(request(Purpose.CREATE)); var lease=queue.claim().orElseThrow();
            sql("UPDATE notification_job SET encrypted_envelope=? WHERE id=?",key.encrypt(malformed),lease.id()); assertTrue(store.prepare(lease).isEmpty());
        }
        var receipt=store.issue(request(Purpose.CREATE)); var lease=queue.claim().orElseThrow();
        replace(receipt,7,"invalid-token"); assertTrue(store.prepare(lease).isEmpty());
        receipt=store.issue(request(Purpose.CREATE)); lease=queue.claim().orElseThrow();
        byte[] original=Base64.getDecoder().decode(envelope(receipt.jobId())); byte[] trailing=Arrays.copyOf(original,original.length+1);
        sql("UPDATE notification_job SET encrypted_envelope=? WHERE id=?",key.encrypt(Base64.getEncoder().encodeToString(trailing)),lease.id());
        Arrays.fill(original,(byte)0); Arrays.fill(trailing,(byte)0); assertTrue(store.prepare(lease).isEmpty());
        assertEquals(5,count("SELECT count(*) FROM notification_job WHERE safe_error='AUTH_DELIVERY_INVALID'"));
    }
    @Test void changedChallengeScopeOrNullChallengeNeverProducesMail() {
        for(String alteration:List.of("purpose='ACCOUNT'","browser_hash='"+"b".repeat(64)+"'","email_key='other@example.test'","token_hash='"+"c".repeat(64)+"'")) {
            var receipt=store.issue(request(Purpose.CREATE)); var lease=queue.claim().orElseThrow();
            sql("UPDATE email_challenge SET "+alteration+" WHERE id=?",receipt.challengeId()); assertTrue(store.prepare(lease).isEmpty());
        }
        store.issue(request(Purpose.CREATE)); var lease=queue.claim().orElseThrow();
        sql("UPDATE notification_job SET challenge_id=NULL WHERE id=?",lease.id()); assertTrue(store.prepare(lease).isEmpty());
        assertEquals(5,count("SELECT count(*) FROM notification_job WHERE encrypted_envelope IS NULL"));
    }
    @Test void challengeLockWaitUsesTimeCapturedAfterTheWait() throws Exception {
        var receipt=store.issue(request(Purpose.CREATE)); var lease=queue.claim().orElseThrow();
        String workerName="auth-prepare-"+UUID.randomUUID(); source.setApplicationName(workerName);
        try(var blocker=database.connection(MigrationTest.APP);var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            MigrationTest.execute(blocker,"SELECT id FROM email_challenge WHERE id=? FOR UPDATE",receipt.challengeId());
            var result=workers.submit(()->store.prepare(lease));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean blocked=false;
            while(System.nanoTime()<deadline) {
                if(text("SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock'",workerName).equals("1")) { blocked=true; break; }
                Thread.sleep(10);
            }
            assertTrue(blocked,"Preparation must actually wait on the challenge lock");
            MigrationTest.execute(blocker,"UPDATE email_challenge SET expires_at=clock_timestamp()+interval '400 milliseconds' WHERE id=?",receipt.challengeId());
            Thread.sleep(800); blocker.commit(); assertTrue(result.get(5,TimeUnit.SECONDS).isEmpty());
            erased("AUTH_DELIVERY_EXPIRED");
        }
    }

    @Test void configurationAndUnavailableDatabaseExposeOnlyFixedSafeErrors() {
        assertTrue(store.toString().contains("REDACTED")); assertFalse(store.toString().contains("example.test"));
        for(String uri:List.of("http://external.example.test","https://user@example.test","https://example.test/path","https://example.test?query","https://example.test#fragment"))
            assertThrows(IllegalArgumentException.class,()->new PostgresAuthDeliveryStore(source,key,URI.create(uri),false));
        assertNotNull(new PostgresAuthDeliveryStore(source,key,URI.create("http://localhost/"),false));
        var unavailable=new PGSimpleDataSource(); unavailable.setURL("jdbc:postgresql://127.0.0.1:1/unavailable?connectTimeout=1");
        safe(()->new PostgresAuthDeliveryStore(unavailable,key,URI.create("https://example.test"),false).issue(request(Purpose.CREATE)));
        sql("DELETE FROM operation_control"); safe(()->store.issue(request(Purpose.CREATE)));
    }
}
