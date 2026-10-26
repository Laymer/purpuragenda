package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.security.*;
import com.purpuragenda.adapters.out.postgres.DatabaseReadiness;
import com.purpuragenda.adapters.out.postgres.PostgresScheduling;
import com.purpuragenda.application.AccountEnrollment;
import com.purpuragenda.application.port.out.AccountEnrollmentStore.*;
import com.purpuragenda.application.port.out.AuthDeliveryStore.Purpose;
import com.purpuragenda.domain.DomainFailure;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL optional-account enrollment: explicit proof, immutable identity, atomicity and races. */
@Timeout(240)
@ResourceLock("postgres-migration-fixture")
class AccountEnrollmentDatabaseTest {
    final CustomerCreationPostgresTest c=new CustomerCreationPostgresTest();
    @TempDir Path directory;
    PostgresAccountEnrollment store;AccountEnrollment command;
    static final String BROWSER=GuestAccessDatabaseTest.BROWSER;
    static String encoded;
    @BeforeAll static void start() { MigrationTest.start();encoded=new Argon2PasswordHash().hash(password()); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void setup() throws Exception {
        c.directory=directory;c.setup();store=new PostgresAccountEnrollment(c.f.source);command=new AccountEnrollment(store,new Argon2PasswordHash());
    }
    @AfterEach void cleanup() { c.cleanup(); }
    static char[] password() { return ("synthetic-account-"+"password").toCharArray(); }
    GuestAccessDatabaseTest.Proof proof(String email) { return c.f.issue(Purpose.ACCOUNT,email,null); }
    Optional<Enrolled> enroll(GuestAccessDatabaseTest.Proof proof) { return command.enroll(proof.token(),BROWSER,password()); }
    Optional<Enrolled> enrollHashed(PostgresAccountEnrollment who,GuestAccessDatabaseTest.Proof proof) { return who.enroll(new Enrollment(proof.token(),BROWSER,encoded)); }
    int count(String sql) { return c.count(sql); }
    String value(String sql,Object... values) { return c.f.value(sql,values); }

    @Test void explicitProofAndCredentialCreateOneAccountWithProfileAuditAndNoAccessAtomically() {
        var proof=proof("synthetic@example.test");var enrolled=enroll(proof).orElseThrow();
        assertEquals(1,count("customer_account"));assertEquals(1,count("customer_identity"));assertEquals(1,count("customer_profile"));
        assertEquals("synthetic@example.test",value("SELECT login_email FROM customer_account WHERE id=?",enrolled.accountId()));
        assertEquals(enrolled.customerId().toString(),value("SELECT customer_id::text FROM customer_account WHERE id=?",enrolled.accountId()));
        assertEquals("t",value("SELECT a.enrolled_at=c.consumed_at AND a.password_changed_at=c.consumed_at AND a.credential_version=1 FROM customer_account a, email_challenge c WHERE c.id=?",proof.id()));
        assertEquals("{}",value("SELECT document::text FROM customer_profile WHERE customer_id=?",enrolled.customerId()));
        assertEquals(1,count("audit_event WHERE type='ACCOUNT_ENROLLED' AND actor_kind='ACCOUNT' AND actor_id='"+enrolled.accountId()+"'"));
        String hash=value("SELECT password_hash FROM customer_account");
        assertTrue(Argon2PasswordHash.validEncoding(hash));assertTrue(new Argon2PasswordHash().verify(password(),hash));assertFalse(new Argon2PasswordHash().verify("synthetic-wrong-password".toCharArray(),hash));
        assertFalse(hash.contains("synthetic"));
        assertEquals(0,count("access_grant"));assertEquals(0,count("app_session"));assertEquals(0,count("booking"));
    }

    @Test void guestProofNeverCreatesAnAccountAndEnrollmentLinksTheExistingIdentityWithoutTransferringAnything() {
        var guest=c.guest("synthetic@example.test");var created=c.create(guest,"create",c.request(3,9));assertTrue(created.success());
        assertEquals(0,count("customer_account"));
        var booking=c.load(created.entityId());int grants=count("access_grant");
        var enrolled=enroll(proof("synthetic@example.test")).orElseThrow();
        assertEquals(booking.customerId(),enrolled.customerId());assertEquals(1,count("customer_identity"));assertEquals(booking,c.load(created.entityId()));
        assertEquals(grants,count("access_grant"));assertEquals(1,count("request_charge"));
        assertEquals(guest.customerId(),enrolled.customerId());
    }

    @Test void replayWrongBrowserPurposeUnknownAndConsumedProofCreateNothing() {
        var proof=proof("synthetic@example.test");
        assertTrue(store.enroll(new Enrollment(proof.token(),"c".repeat(64),encoded)).isEmpty());
        assertTrue(store.enroll(new Enrollment(Secrets.token(),BROWSER,encoded)).isEmpty());
        var create=c.f.issue(Purpose.CREATE,"synthetic@example.test",null);assertTrue(enrollHashed(store,create).isEmpty());
        assertEquals(0,count("customer_account"));assertEquals(0,count("customer_identity"));assertNull(value("SELECT consumed_at FROM email_challenge WHERE id=?",proof.id()));
        char[] secret=password();assertTrue(command.enroll(proof.token(),BROWSER,secret).isPresent());assertArrayEquals(new char[secret.length],secret);
        assertTrue(enroll(proof).isEmpty());assertEquals(1,count("customer_account"));
        c.jdbc.update("UPDATE email_challenge SET consumed_at=NULL WHERE purpose='CREATE'");
    }

    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void proofExpiryIsStrictAtMicrosecondEquality(long micros) {
        var proof=proof("synthetic@example.test");Instant expires=proof.expiresAt();
        var reads=new AtomicInteger();var fixed=new PostgresAccountEnrollment(c.clock(expires.plusNanos(micros*1000),reads));
        assertEquals(micros<0,enrollHashed(fixed,proof).isPresent());assertEquals(1,reads.get());
        assertEquals(micros<0?1:0,count("customer_account"));
    }

    @Test void anIdentityThatAlreadyHasAnAccountIsNeverReplacedAndTheNewProofIsNotConsumed() {
        assertTrue(enroll(proof("synthetic@example.test")).isPresent());String hash=value("SELECT password_hash FROM customer_account");
        var second=proof("synthetic@example.test");assertTrue(enrollHashed(store,second).isEmpty());
        assertEquals(1,count("customer_account"));assertEquals(hash,value("SELECT password_hash FROM customer_account"));assertNull(value("SELECT consumed_at FROM email_challenge WHERE id=?",second.id()));
    }

    @Test void invalidPasswordIsRejectedBeforeAnyProofIsConsumed() {
        var proof=proof("synthetic@example.test");
        assertEquals("INVALID_ACCOUNT_PASSWORD",assertThrows(DomainFailure.class,()->command.enroll(proof.token(),BROWSER,"too-short".toCharArray())).code());
        assertNull(value("SELECT consumed_at FROM email_challenge WHERE id=?",proof.id()));assertEquals(0,count("customer_account"));
        assertThrows(IllegalArgumentException.class,()->store.enroll(new Enrollment(proof.token(),BROWSER,"not-an-argon2-encoding")));
    }

    @Test void accountIdentityAndCredentialHistoryAreImmutableAndConsistentInTheDatabase() {
        var enrolled=enroll(proof("synthetic@example.test")).orElseThrow();UUID id=enrolled.accountId();
        UUID other=UUID.randomUUID();c.jdbc.update("INSERT INTO customer_identity VALUES(?,?,clock_timestamp())",other,"other@example.test");
        for(String change:List.of("login_email='other@example.test'","customer_id='"+other+"'","enrolled_at=enrolled_at+interval '1 second'","id='"+UUID.randomUUID()+"'",
                "password_hash=NULL","login_email=NULL,password_hash=NULL,password_changed_at=NULL","credential_version=credential_version-1",
                "password_hash='"+encoded.substring(0,encoded.length()-1)+"A'"))
            c.f.database.denied("23514","UPDATE customer_account SET "+change+" WHERE id=?",id);
        c.f.database.denied("23514","UPDATE customer_account SET password_hash='not-an-encoding',credential_version=2 WHERE id=?",id);
        // Only an explicit version advance may replace the credential.
        c.jdbc.update("UPDATE customer_account SET password_hash=?,credential_version=2,password_changed_at=clock_timestamp() WHERE id=?",encoded.replace("$argon2id","$argon2id"),id);
        c.f.database.denied("23514","INSERT INTO customer_account(id,customer_id,enrolled_at,login_email,password_hash,password_changed_at) VALUES(gen_random_uuid(),?,clock_timestamp(),'unrelated@example.test',?,clock_timestamp())",other,encoded);
        c.f.database.denied("23505","INSERT INTO customer_account(id,customer_id,enrolled_at,login_email,password_hash,password_changed_at) VALUES(gen_random_uuid(),?,clock_timestamp(),'synthetic@example.test',?,clock_timestamp())",enrolled.customerId(),encoded);
        c.f.database.denied("23514","INSERT INTO customer_account(id,customer_id,enrolled_at,login_email) VALUES(gen_random_uuid(),?,clock_timestamp(),'other@example.test')",other);
    }

    @Test void accountAttemptThrottleKindIsBoundedByThePolicyConstraint() {
        String key="a".repeat(64);
        c.jdbc.update("INSERT INTO rate_limit_bucket(key,count,reset_at,kind,key_id,window_started_at) SELECT ?,5,t+interval '15 minutes','ACCOUNT_ATTEMPT','k',t FROM (SELECT clock_timestamp() t) x",key);
        c.f.database.denied("23514","INSERT INTO rate_limit_bucket(key,count,reset_at,kind,key_id,window_started_at) SELECT ?,6,t+interval '15 minutes','ACCOUNT_ATTEMPT','k',t FROM (SELECT clock_timestamp() t) x","b".repeat(64));
        c.f.database.denied("23514","INSERT INTO rate_limit_bucket(key,count,reset_at,kind,key_id,window_started_at) SELECT ?,1,t+interval '1 hour','ACCOUNT_ATTEMPT','k',t FROM (SELECT clock_timestamp() t) x","c".repeat(64));
    }

    @Test void databaseRejectsAnEnrolledIdentityWithoutAPassword() {
        UUID customer=UUID.randomUUID();
        c.jdbc.update("INSERT INTO customer_identity VALUES(?,?,clock_timestamp())",customer,"incomplete@example.test");
        c.f.database.denied("23514","""
            INSERT INTO customer_account(id,customer_id,enrolled_at,login_email,password_changed_at)
            VALUES(gen_random_uuid(),?,clock_timestamp(),'incomplete@example.test',clock_timestamp())
            """,customer);
        assertEquals(0,count("customer_account"));
    }

    @Test void aProofThatExpiresWhileWaitingForItsChallengeLockCannotEnroll() throws Exception {
        var proof=proof("synthetic@example.test");var reads=new AtomicInteger();
        var fixed=new PostgresAccountEnrollment(c.clock(proof.expiresAt(),reads));
        String name="account-proof-lock-"+UUID.randomUUID();c.f.source.setApplicationName(name);
        try(var blocker=c.f.database.connection(MigrationTest.APP);var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            MigrationTest.execute(blocker,"SELECT id FROM email_challenge WHERE id=? FOR UPDATE",proof.id());
            var future=pool.submit(()->enrollHashed(fixed,proof));boolean waiting=false;
            long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(System.nanoTime()<limit) {
                if(c.jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock'",Integer.class,name)>0) {
                    waiting=true;break;
                }
                Thread.sleep(10);
            }
            assertTrue(waiting);assertEquals(0,reads.get());blocker.commit();
            assertTrue(future.get(10,TimeUnit.SECONDS).isEmpty());assertEquals(1,reads.get());
        }
        assertEquals(0,count("customer_account"));assertEquals(0,count("customer_identity"));
        assertNull(value("SELECT consumed_at FROM email_challenge WHERE id=?",proof.id()));
    }

    @Test void additiveCredentialShapeUpgradePreservesEnrolledAndLegacyAccountsAndReruns() throws Exception {
        c.f.database.newSchema();c.f.database.migrate(14); // 001-013 plus unchanged 015; 014 is reserved for P36.
        c.f.source.setCurrentSchema(c.f.database.schema+",public");
        var readiness=new DatabaseReadiness(c.f.source,new PostgresScheduling(c.f.source));
        assertEquals(org.springframework.boot.health.contributor.Status.DOWN,readiness.health().getStatus());
        UUID enrolled=UUID.randomUUID(),legacy=UUID.randomUUID(),incomplete=UUID.randomUUID();
        Instant enrolledAt=Instant.parse("2026-10-01T10:00:00Z");
        try(var connection=c.f.database.connection(MigrationTest.APP)) {
            MigrationTest.execute(connection,"INSERT INTO customer_identity VALUES(?,?,?),(?,?,?),(?,?,?)",
                    enrolled,"enrolled@example.test",enrolledAt,legacy,"legacy@example.test",enrolledAt,incomplete,"incomplete@example.test",enrolledAt);
            MigrationTest.execute(connection,"INSERT INTO customer_account(id,customer_id,enrolled_at,login_email,password_hash,password_changed_at) VALUES(?,?,?,'enrolled@example.test',?,?)",
                    UUID.randomUUID(),enrolled,enrolledAt,encoded,enrolledAt);
            MigrationTest.execute(connection,"INSERT INTO customer_account(id,customer_id,enrolled_at) VALUES(?,?,?)",UUID.randomUUID(),legacy,enrolledAt);
        }
        c.f.database.migrate(0);c.f.database.migrate(0);
        assertEquals(org.springframework.boot.health.contributor.Status.UP,readiness.health().getStatus());
        assertEquals(15,c.f.database.scalar(MigrationTest.MIGRATOR,"SELECT count(*) FROM databasechangelog"));
        assertEquals(2,c.f.database.scalar(MigrationTest.APP,"SELECT count(*) FROM customer_account"));
        assertEquals(1,c.f.database.scalar(MigrationTest.APP,"SELECT count(*) FROM customer_account WHERE login_email IS NULL AND password_hash IS NULL AND password_changed_at IS NULL"));
        try(var connection=c.f.database.connection(MigrationTest.APP);
            var query=connection.prepareStatement("SELECT password_hash=? AND enrolled_at=? AND password_changed_at=? AND credential_version=1 FROM customer_account WHERE customer_id=?")) {
            query.setString(1,encoded);query.setTimestamp(2,Timestamp.from(enrolledAt));query.setTimestamp(3,Timestamp.from(enrolledAt));query.setObject(4,enrolled);
            try(var rows=query.executeQuery()) { assertTrue(rows.next());assertTrue(rows.getBoolean(1)); }
        }
        c.f.database.denied("23514","INSERT INTO customer_account(id,customer_id,enrolled_at,login_email,password_changed_at) VALUES(gen_random_uuid(),?,clock_timestamp(),'incomplete@example.test',clock_timestamp())",incomplete);
    }

    @ParameterizedTest @ValueSource(strings={"customer_profile","customer_account","audit_event"})
    void unexpectedStorageFailureRollsBackIdentityAccountProfileAuditAndConsumption(String table) throws Exception {
        var proof=proof("fresh@example.test");
        try(var connection=c.f.database.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(connection,"CREATE FUNCTION reject_enrollment() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF TG_TABLE_NAME<>'audit_event' OR NEW.type='ACCOUNT_ENROLLED' THEN RAISE EXCEPTION 'SYNTHETIC_FAILURE'; END IF; RETURN NEW; END $$");
            MigrationTest.execute(connection,"CREATE TRIGGER reject_enrollment BEFORE INSERT ON "+table+" FOR EACH ROW EXECUTE FUNCTION reject_enrollment()");
        }
        var failure=assertThrows(IllegalStateException.class,()->enrollHashed(store,proof));
        assertEquals("ACCOUNT_ENROLLMENT_UNAVAILABLE",failure.getMessage());assertNull(failure.getCause());assertEquals(0,failure.getSuppressed().length);
        assertEquals(0,count("customer_account"));assertEquals(0,count("customer_identity"));assertEquals(0,count("customer_profile"));
        assertNull(value("SELECT consumed_at FROM email_challenge WHERE id=?",proof.id()));assertEquals(0,count("audit_event WHERE type='ACCOUNT_ENROLLED'"));
    }

    @Test void twentySameProofEnrollmentsAcrossTwoInstancesCreateExactlyOneAccount() throws Exception {
        var proof=proof("synthetic@example.test");var other=new PostgresAccountEnrollment(c.f.source);
        var results=race(i->enrollHashed(i%2==0?store:other,proof));
        assertEquals(1,results.stream().filter(Optional::isPresent).count());assertEquals(1,count("customer_account"));assertEquals(1,count("customer_profile"));
        assertEquals(1,count("audit_event WHERE type='ACCOUNT_ENROLLED'"));
    }

    @Test void twentyDifferentProofsForOneMailboxCreateExactlyOneAccountAndConsumeOnlyTheWinner() throws Exception {
        var proofs=new ArrayList<GuestAccessDatabaseTest.Proof>();for(int i=0;i<20;i++)proofs.add(proof("synthetic@example.test"));var other=new PostgresAccountEnrollment(c.f.source);
        var results=race(i->enrollHashed(i%2==0?store:other,proofs.get(i)));
        assertEquals(1,results.stream().filter(Optional::isPresent).count());assertEquals(1,count("customer_account"));assertEquals(1,count("customer_identity"));
        assertEquals(1,count("email_challenge WHERE purpose='ACCOUNT' AND consumed_at IS NOT NULL"));
    }

    @Test void twentyDifferentMailboxesEnrollIndependentlyWithDistinctIdentities() throws Exception {
        var proofs=new ArrayList<GuestAccessDatabaseTest.Proof>();for(int i=0;i<20;i++)proofs.add(proof("person"+i+"@example.test"));var other=new PostgresAccountEnrollment(c.f.source);
        var results=race(i->enrollHashed(i%2==0?store:other,proofs.get(i)));
        assertTrue(results.stream().allMatch(Optional::isPresent));assertEquals(20,count("customer_account"));assertEquals(20,count("customer_identity"));
    }

    List<Optional<Enrolled>> race(Work work) throws Exception {
        var ready=new CountDownLatch(20);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<Future<Optional<Enrolled>>>();
            for(int i=0;i<20;i++){int index=i;futures.add(pool.submit(()->{ready.countDown();release.await();return work.apply(index);}));}
            assertTrue(ready.await(5,TimeUnit.SECONDS));release.countDown();var results=new ArrayList<Optional<Enrolled>>();for(var future:futures)results.add(future.get(90,TimeUnit.SECONDS));return results;
        }
    }
    interface Work { Optional<Enrolled> apply(int index) throws Exception; }
}
