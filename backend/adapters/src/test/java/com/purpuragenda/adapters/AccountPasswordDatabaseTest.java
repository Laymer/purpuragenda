package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.security.*;
import com.purpuragenda.application.*;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SessionStore.Issued;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual enrollment, bound password login and atomic replacement/sign-out against isolated PostgreSQL. */
@Timeout(240)
@ResourceLock("postgres-migration-fixture")
class AccountPasswordDatabaseTest {
    final AccountAuthenticationDatabaseTest a=new AccountAuthenticationDatabaseTest();
    @TempDir Path directory;
    PostgresAccountPassword store;AccountPasswordChange command;Issued logged;
    @BeforeAll static void start() { AccountAuthenticationDatabaseTest.start(); }
    @AfterAll static void stop() { AccountAuthenticationDatabaseTest.stop(); }
    @BeforeEach void setup() throws Exception {
        a.directory=directory;a.setup();logged=a.login();a.browser.close();a.browser=a.sessions.anonymous();store=new PostgresAccountPassword(a.e.c.f.source);
        command=new AccountPasswordChange(store,new Argon2PasswordHash(),throttle());
    }
    @AfterEach void cleanup() { logged.close();a.cleanup(); }
    RequestThrottle throttle() {
        return new RequestThrottle(new PostgresRateLimits(a.e.c.f.source,new RateKey(directory.resolve("account-rate.key"),"account-test-v1")));
    }
    SessionBinding binding() { var ref=logged.session().reference();return new SessionBinding(ref.id(),ref.browserHash()); }
    char[] next() { return "synthetic-new-account-password".toCharArray(); }
    AccountPasswordChange.Result change() { return command.change(a.EMAIL,binding(),AccountEnrollmentDatabaseTest.password(),next(),a.IP); }
    String value(String sql,Object...args) { return a.value(sql,args); }
    int count(String table) { return a.count(table); }
    void unavailable(org.junit.jupiter.api.function.Executable work) { a.unavailable(work,"ACCOUNT_PASSWORD_CHANGE_UNAVAILABLE"); }
    @Test void changedPasswordAdvancesEpochRevokesAllSessionsAndUnusedProofsAndRequiresNormalLogin() {
        try(var other=a.login();var proof=a.proof()) {
            char[] previous=AccountEnrollmentDatabaseTest.password(),replacement=next();
            assertTrue(command.change(a.EMAIL,binding(),previous,replacement,a.IP).changed());a.clear(previous);a.clear(replacement);
            assertEquals("2",value("SELECT credential_version FROM customer_account WHERE id=?",a.account.accountId()));
            assertEquals(0,count("app_session WHERE kind='ACCOUNT' AND revoked_at IS NULL"));
            assertEquals(0,count("account_authentication_proof WHERE consumed_at IS NULL AND revoked_at IS NULL"));
            assertTrue(a.sessions.inspect(logged.token()).isEmpty());assertTrue(a.sessions.resolve(other.token()).isEmpty());
            assertFalse(a.sessions.csrf(logged.session().reference(),logged.csrf()));assertTrue(a.exchange(a.sessions,proof).isEmpty());
            assertEquals(1,count("audit_event WHERE type='ACCOUNT_PASSWORD_CHANGED'"));
            String hash=value("SELECT password_hash FROM customer_account WHERE id=?",a.account.accountId());
            assertTrue(new Argon2PasswordHash().verify(next(),hash));assertFalse(new Argon2PasswordHash().verify(AccountEnrollmentDatabaseTest.password(),hash));
            a.browser.close();a.browser=a.sessions.anonymous();
            try(var authenticated=a.command.authenticate(a.EMAIL,next(),a.IP,a.binding());var fresh=a.exchange(a.sessions,authenticated).orElseThrow()) {
                assertEquals(a.account.accountId(),fresh.session().accountId());assertTrue(a.sessions.inspect(fresh.token()).isPresent());
            }
        }
    }
    @Test void wrongPasswordKeepsFiveAttemptBudgetWithoutChangingCredentialOrAuthority() {
        for(int i=0;i<5;i++)assertFalse(command.change(a.EMAIL,binding(),"synthetic-wrong-password".toCharArray(),next(),a.IP).changed());
        assertTrue(change().retryAfterSeconds()>0);assertEquals("1",value("SELECT credential_version FROM customer_account"));
        assertTrue(a.sessions.inspect(logged.token()).isPresent());assertEquals(0,count("audit_event WHERE type='ACCOUNT_PASSWORD_CHANGED'"));
    }
    @ParameterizedTest @ValueSource(strings={"browser","missing","revoked","epoch","anonymous","email"})
    void onlyCurrentPersistedAccountOwnershipCanReplace(String mutation) {
        SessionBinding binding=binding();String email=a.EMAIL;
        switch(mutation) {
            case "browser" -> binding=new SessionBinding(binding.sessionId(),"f".repeat(64));
            case "missing" -> binding=new SessionBinding(UUID.randomUUID(),binding.browserHash());
            case "revoked" -> a.e.c.jdbc.update("UPDATE app_session SET revoked_at=clock_timestamp() WHERE id=?",binding.sessionId());
            case "epoch" -> a.epoch();
            case "anonymous" -> binding=new SessionBinding(a.browser.session().reference().id(),a.browser.session().reference().browserHash());
            case "email" -> email="unknown@example.test";
        }
        assertFalse(command.change(email,binding,AccountEnrollmentDatabaseTest.password(),next(),a.IP).changed());
        assertEquals(0,count("audit_event WHERE type='ACCOUNT_PASSWORD_CHANGED'"));
    }
    @ParameterizedTest @ValueSource(strings={"idle","absolute","not-before"})
    void authoritativeExpiryEqualityAndFutureSessionReject(String boundary) {
        Instant time=switch(boundary) {
            case "idle" -> logged.session().lastSeenAt().plusSeconds(1800);
            case "absolute" -> logged.session().absoluteExpiresAt();
            default -> logged.session().issuedAt().minusNanos(1000);
        };
        var fixed=new PostgresAccountPassword(a.e.c.clock(time,new AtomicInteger()));
        boolean authorized=fixed.locked(a.EMAIL,binding(),account->account.credential().isPresent());assertFalse(authorized);
        assertEquals("1",value("SELECT credential_version FROM customer_account"));
    }
    @ParameterizedTest @ValueSource(strings={"customer_account","app_session","account_authentication_proof","audit_event"})
    void anyTransactionalWriteFailureRollsBackCredentialSessionsProofsAndAudit(String table) throws Exception {
        try(var proof=a.proof();var connection=a.e.c.f.database.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(connection,"CREATE FUNCTION reject_password() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'SYNTHETIC_FAILURE'; END $$");
            String operation=table.equals("audit_event")?"INSERT":"UPDATE";
            String when=table.equals("audit_event")?" WHEN (NEW.type='ACCOUNT_PASSWORD_CHANGED')":"";
            MigrationTest.execute(connection,"CREATE TRIGGER reject_password BEFORE "+operation+" ON "+table+" FOR EACH ROW"+when+" EXECUTE FUNCTION reject_password()");
            unavailable(this::change);assertEquals("1",value("SELECT credential_version FROM customer_account"));
            assertTrue(a.sessions.inspect(logged.token()).isPresent());assertEquals(0,count("audit_event WHERE type='ACCOUNT_PASSWORD_CHANGED'"));
            assertNull(value("SELECT revoked_at FROM account_authentication_proof WHERE token_hash=?",proof.proof().orElseThrow().token().hash()));
        }
    }
    @Test void twentyRequestsAcrossTwoStoresHaveOneWinnerAndNoCredentialOrSessionResurrection() throws Exception {
        var pool=Executors.newFixedThreadPool(20);var start=new CountDownLatch(1);
        try {
            var second=new AccountPasswordChange(new PostgresAccountPassword(a.e.c.f.source),new Argon2PasswordHash(),throttle());
            var results=new ArrayList<Future<Boolean>>();
            for(int i=0;i<20;i++) { var selected=i%2==0?command:second;results.add(pool.submit(()->{ start.await();return selected.change(a.EMAIL,binding(),AccountEnrollmentDatabaseTest.password(),next(),a.IP).changed(); })); }
            start.countDown();int winners=0;for(var result:results)if(result.get())winners++;
            assertEquals(1,winners);assertEquals("2",value("SELECT credential_version FROM customer_account"));assertEquals(1,count("audit_event WHERE type='ACCOUNT_PASSWORD_CHANGED'"));
            assertTrue(a.sessions.inspect(logged.token()).isEmpty());assertFalse(change().changed());
        } finally { pool.shutdownNow(); }
    }
    @Test void contextCannotEscapeThreadLifetimeOrReplaceTwiceAndRollbackRetainsAuthority() throws Exception {
        var saved=new AccountPasswordUnitOfWork.Account[1];
        store.locked(a.EMAIL,binding(),account->{saved[0]=account;return null;});assertThrows(IllegalStateException.class,()->saved[0].credential());
        try(var pool=Executors.newSingleThreadExecutor()) {
            store.locked(a.EMAIL,binding(),account->{assertThrows(ExecutionException.class,()->pool.submit(account::credential).get());return null;});
        }
        unavailable(()->store.locked(a.EMAIL,binding(),account->{account.replacePassword("invalid");return null;}));
        String hash=new Argon2PasswordHash().hash(next());
        unavailable(()->store.locked(a.EMAIL,binding(),account->{account.replacePassword(hash);account.replacePassword(hash);return null;}));
        assertEquals("1",value("SELECT credential_version FROM customer_account"));assertTrue(a.sessions.inspect(logged.token()).isPresent());
        assertEquals("AccountPasswordStore[REDACTED]",store.toString());assertThrows(NullPointerException.class,()->new PostgresAccountPassword(null));
    }
    @Test void finalMicrosecondBeforeIdleExpiryIsAcceptedWithoutAnInventedAgeCutoff() {
        var reads=new AtomicInteger();var fixed=new PostgresAccountPassword(a.e.c.clock(logged.session().lastSeenAt().plusSeconds(1800).minusNanos(1000),reads));
        boolean authorized=fixed.locked(a.EMAIL,binding(),account->account.credential().isPresent());assertTrue(authorized);assertEquals(1,reads.get());
    }
    @ParameterizedTest @ValueSource(strings={"account","session"})
    void waitsForBothRequiredLocksBeforeOnlyClockAndRechecksConcurrentRevocation(String target) throws Exception {
        try(var blocker=a.e.c.f.database.connection(MigrationTest.APP);var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            String name="account-password-lock-"+UUID.randomUUID();a.e.c.f.source.setApplicationName(name);blocker.setAutoCommit(false);
            if(target.equals("account"))MigrationTest.execute(blocker,"SELECT id FROM customer_account WHERE id=? FOR UPDATE",a.account.accountId());
            else MigrationTest.execute(blocker,"SELECT id FROM app_session WHERE id=? FOR UPDATE",binding().sessionId());
            var reads=new AtomicInteger();var fixed=new PostgresAccountPassword(a.e.c.clock(a.e.c.now().plusSeconds(10),reads));
            var future=pool.submit(()->fixed.locked(a.EMAIL,binding(),account->account.credential().isPresent()));
            a.awaitLock(name);assertEquals(0,reads.get());
            if(target.equals("account"))MigrationTest.execute(blocker,"UPDATE customer_account SET credential_version=credential_version+1 WHERE id=?",a.account.accountId());
            else MigrationTest.execute(blocker,"UPDATE app_session SET revoked_at=clock_timestamp() WHERE id=?",binding().sessionId());
            blocker.commit();assertFalse(future.get(10,TimeUnit.SECONDS));assertEquals(1,reads.get());
            assertEquals(0,count("audit_event WHERE type='ACCOUNT_PASSWORD_CHANGED'"));
        }
    }

}
