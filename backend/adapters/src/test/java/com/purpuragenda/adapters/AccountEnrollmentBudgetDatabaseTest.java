package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.security.*;
import com.purpuragenda.application.*;
import com.purpuragenda.application.port.out.PasswordHash;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(240)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class AccountEnrollmentBudgetDatabaseTest {
    final AccountEnrollmentDatabaseTest enrollment=new AccountEnrollmentDatabaseTest();
    @TempDir Path directory;
    RateKey key;
    AtomicInteger hashes;
    @BeforeAll static void start() { AccountEnrollmentDatabaseTest.start(); }
    @AfterAll static void stop() { AccountEnrollmentDatabaseTest.stop(); }
    @BeforeEach void setup() throws Exception {
        enrollment.directory=directory;enrollment.setup();hashes=new AtomicInteger();
        byte[] bytes=new byte[32];Arrays.fill(bytes,(byte)7);
        key=new RateKey(Files.write(directory.resolve("enrollment-rate.key"),bytes),"synthetic-enrollment-v1");
    }
    @AfterEach void cleanup() { enrollment.cleanup(); }
    AccountEnrollmentRequest request(PostgresAccountEnrollment store,Runnable beforeHash) {
        PasswordHash passwords=new PasswordHash() {
            public String hash(char[] secret) { hashes.incrementAndGet();beforeHash.run();return AccountEnrollmentDatabaseTest.encoded; }
            public boolean verify(char[] secret,String encoded) { throw new AssertionError("Enrollment never verifies a prior password"); }
        };
        return new AccountEnrollmentRequest(new AccountEnrollment(store,passwords),new RequestThrottle(new PostgresRateLimits(enrollment.c.f.source,key)));
    }
    AccountEnrollmentRequest.Result invoke(AccountEnrollmentRequest command,GuestAccessDatabaseTest.Proof proof) {
        char[] secret=AccountEnrollmentDatabaseTest.password();
        try { return command.enroll(proof.token(),AccountEnrollmentDatabaseTest.BROWSER,secret,"203.0.113.19"); }
        finally { assertTrue(Arrays.equals(new char[secret.length],secret)); }
    }
    int charged() { return enrollment.c.jdbc.queryForObject("SELECT count FROM rate_limit_bucket WHERE kind='ACCOUNT_ENROLLMENT_IP'",Integer.class); }

    @Test void committedEnrollmentReturnsItsSlotButReplayExhaustsTheBudgetBeforeHashing() {
        var proof=enrollment.proof("synthetic@example.test");var command=request(enrollment.store,()->{});
        assertTrue(invoke(command,proof).enrolled());assertEquals(0,charged());
        for(int i=0;i<5;i++) {var rejected=invoke(command,proof);assertFalse(rejected.enrolled());assertEquals(0,rejected.retryAfterSeconds());}
        assertTrue(invoke(command,proof).retryAfterSeconds()>0);assertEquals(6,hashes.get());assertEquals(5,charged());
        assertEquals(1,enrollment.count("customer_account"));assertEquals(1,enrollment.c.jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE type='ACCOUNT_ENROLLED'",Integer.class));
    }
    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void expiryEqualityRetainsItsFailureChargeWhileOnlyStrictlyEarlierProofCommits(long micros) {
        var proof=enrollment.proof("synthetic@example.test");
        var command=request(new PostgresAccountEnrollment(enrollment.c.clock(proof.expiresAt().plusNanos(micros*1000),new AtomicInteger())),()->{});
        assertEquals(micros<0,invoke(command,proof).enrolled());assertEquals(micros<0?0:1,charged());
        assertEquals(micros<0?1:0,enrollment.count("customer_account"));
    }
    @Test void failedAuditRollsBackEnrollmentButKeepsItsIndependentNetworkChargeAndRedeemableProof() throws Exception {
        var proof=enrollment.proof("synthetic@example.test");var command=request(enrollment.store,()->{});
        try(var connection=enrollment.c.f.database.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(connection,"CREATE FUNCTION reject_budget_enrollment() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.type='ACCOUNT_ENROLLED' THEN RAISE EXCEPTION 'SYNTHETIC_FAILURE'; END IF; RETURN NEW; END $$");
            MigrationTest.execute(connection,"CREATE TRIGGER reject_budget_enrollment BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_budget_enrollment()");
        }
        var failure=assertThrows(IllegalStateException.class,()->invoke(command,proof));
        assertEquals("ACCOUNT_ENROLLMENT_UNAVAILABLE",failure.getMessage());assertNull(failure.getCause());assertEquals(0,failure.getSuppressed().length);
        assertEquals(1,charged());assertEquals(0,enrollment.count("customer_account"));assertEquals(0,enrollment.count("customer_identity"));assertEquals(0,enrollment.count("customer_profile"));
        assertEquals(0,enrollment.count("audit_event WHERE type='ACCOUNT_ENROLLED'"));
        assertNull(enrollment.value("SELECT consumed_at FROM email_challenge WHERE id=?",proof.id()));
        try(var connection=enrollment.c.f.database.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(connection,"DROP TRIGGER reject_budget_enrollment ON audit_event");
        }
        assertTrue(invoke(command,proof).enrolled());assertEquals(1,charged());
        assertEquals(1,enrollment.count("customer_account"));assertEquals(1,enrollment.count("audit_event WHERE type='ACCOUNT_ENROLLED'"));
    }

    @Test void twoCompositionsAndTwentyCallersAllowOnlyFiveDerivationsAndOneEnrollment() throws Exception {
        var proof=enrollment.proof("synthetic@example.test");var entered=new CountDownLatch(5);var release=new CountDownLatch(1);
        Runnable hold=()->{entered.countDown();try {if(!release.await(30,TimeUnit.SECONDS))throw new AssertionError("Hash gate timed out");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError("Interrupted synthetic hash");}};
        var first=request(enrollment.store,hold);var second=request(new PostgresAccountEnrollment(enrollment.c.f.source),hold);
        var barrier=new CyclicBarrier(20);var denied=new AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(20)) {
            var futures=new ArrayList<Future<AccountEnrollmentRequest.Result>>();
            for(int i=0;i<20;i++) {var command=i%2==0?first:second;futures.add(pool.submit(()->{barrier.await(30,TimeUnit.SECONDS);var result=invoke(command,proof);if(result.retryAfterSeconds()>0)denied.incrementAndGet();return result;}));}
            try {
                assertTrue(entered.await(30,TimeUnit.SECONDS));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                while(denied.get()<15 && System.nanoTime()<deadline)Thread.sleep(10);
                assertEquals(15,denied.get());assertEquals(5,hashes.get());
            } finally {release.countDown();}
            int enrolled=0,rejected=0;for(var future:futures){var result=future.get(30,TimeUnit.SECONDS);if(result.enrolled())enrolled++;else if(result.retryAfterSeconds()==0)rejected++;}
            assertEquals(1,enrolled);assertEquals(4,rejected);
        } finally {release.countDown();}
        assertEquals(4,charged());assertEquals(1,enrollment.count("customer_account"));assertEquals(0,enrollment.count("app_session"));
    }
}
