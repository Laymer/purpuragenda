package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.adapters.out.security.*;
import com.purpuragenda.application.*;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.SessionStore.*;
import java.nio.file.*;
import java.security.SecureRandom;
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

/** Real ACCOUNT email enrollment, password verification, persisted proofs and epoch-checked sessions. */
@Timeout(240)
@ResourceLock("postgres-migration-fixture")
class AccountAuthenticationDatabaseTest {
    final AccountEnrollmentDatabaseTest e=new AccountEnrollmentDatabaseTest();
    @TempDir Path directory;
    AccountEnrollmentStore.Enrolled account;PostgresAccountAuthentication store;AccountAuthentication command;
    Issued browser;PostgresSessions sessions;
    static final String EMAIL="account@example.test",REQUEST="a".repeat(64),IP="203.0.113.8";
    @BeforeAll static void start() { AccountEnrollmentDatabaseTest.start(); }
    @AfterAll static void stop() { AccountEnrollmentDatabaseTest.stop(); }
    @BeforeEach void setup() throws Exception {
        e.directory=directory;e.setup();account=e.enroll(e.proof(EMAIL)).orElseThrow();
        sessions=e.c.sessions;browser=sessions.anonymous();store=new PostgresAccountAuthentication(e.c.f.source);
        var key=new RateKey(Files.write(directory.resolve("account-rate.key"),SecureRandom.getSeed(32)),"account-test-v1");
        command=new AccountAuthentication(store,new Argon2PasswordHash(),new AdminCrypto(),new RequestThrottle(new PostgresRateLimits(e.c.f.source,key)));
    }
    @AfterEach void cleanup() { browser.close();e.cleanup(); }
    ProofBinding binding() { return new ProofBinding(browser.session().reference().browserHash(),REQUEST); }
    AccountAuthentication.Attempt proof() { return command.authenticate(EMAIL,AccountEnrollmentDatabaseTest.password(),IP,binding()); }
    Optional<Issued> exchange(PostgresSessions who,AccountAuthentication.Attempt proof) { return who.exchangeAccount(browser.session().reference(),REQUEST,proof.proof().orElseThrow().token().value()); }
    Issued login() { try(var proof=proof()) { return exchange(sessions,proof).orElseThrow(); } }
    int count(String table) { return e.count(table); }
    String value(String sql,Object... args) { return e.value(sql,args); }
    void epoch() { e.c.jdbc.update("UPDATE customer_account SET credential_version=credential_version+1 WHERE id=?",account.accountId()); }
    void clear(char[] input) { assertTrue(Arrays.equals(new char[input.length],input),"SENSITIVE_BUFFER_NOT_CLEARED"); }
    void unavailable(org.junit.jupiter.api.function.Executable action,String code) {
        var failure=assertThrows(IllegalStateException.class,action);assertEquals(code,failure.getMessage());assertNull(failure.getCause());assertEquals(0,failure.getSuppressed().length);
    }

    @Test void passwordProofAndSessionRotateAtomicallyWithStableBrowserOwnershipAndNoGuestGrant() {
        char[] password=AccountEnrollmentDatabaseTest.password();
        try(var proof=command.authenticate(EMAIL.toUpperCase(Locale.ROOT),password,IP,binding())) {
            clear(password);assertEquals(1,count("account_authentication_proof"));assertEquals(1,count("app_session"));
            assertEquals("t",value("SELECT p.token_hash<>'' AND p.credential_version=1 AND p.expires_at=p.issued_at+interval '60 seconds' FROM account_authentication_proof p"));
            char[] token=proof.proof().orElseThrow().token().value();
            try(var issued=sessions.exchangeAccount(browser.session().reference(),REQUEST,token).orElseThrow()) {
                clear(token);var s=issued.session();assertEquals(Kind.ACCOUNT,s.kind());assertEquals(account.accountId(),s.accountId());assertEquals(account.customerId(),s.customerId());
                assertEquals(browser.session().reference().browserHash(),s.reference().browserHash());assertNotEquals(browser.session().reference().id(),s.reference().id());
                assertEquals(Duration.ofHours(12),Duration.between(s.issuedAt(),s.absoluteExpiresAt()));assertTrue(sessions.inspect(issued.token()).isPresent());
                assertTrue(sessions.csrf(s.reference(),issued.csrf()));assertTrue(sessions.resolve(browser.token()).isEmpty());
                assertTrue(exchange(sessions,proof).isEmpty());assertEquals(1,count("audit_event WHERE type='ACCOUNT_SESSION_CREATED'"));
                assertEquals(0,count("access_grant"));assertEquals(0,count("booking"));
            }
        }
    }
    @Test void wrongAndUnknownCredentialsShareBoundedFailureAdmissionWithoutProofs() {
        for(int i=0;i<5;i++)try(var result=command.authenticate(EMAIL,"synthetic-wrong-password".toCharArray(),IP,binding())) { assertTrue(result.proof().isEmpty());assertEquals(0,result.retryAfterSeconds()); }
        try(var denied=proof()) { assertTrue(denied.proof().isEmpty());assertTrue(denied.retryAfterSeconds()>0); }
        try(var unknown=command.authenticate("unknown@example.test",AccountEnrollmentDatabaseTest.password(),IP,binding())) { assertTrue(unknown.proof().isEmpty()); }
        assertEquals(0,count("account_authentication_proof"));assertEquals(0,count("audit_event WHERE type='ACCOUNT_PASSWORD_VERIFIED'"));
    }
    @Test void wrongBrowserRequestUnknownAndRevokedProofNeverRotateOrConsumeAuthority() {
        try(var proof=proof();var other=sessions.anonymous()) {
            var token=proof.proof().orElseThrow().token();
            assertTrue(sessions.exchangeAccount(other.session().reference(),REQUEST,token.value()).isEmpty());
            assertTrue(sessions.exchangeAccount(browser.session().reference(),"b".repeat(64),token.value()).isEmpty());
            assertTrue(sessions.exchangeAccount(new SessionReference(UUID.randomUUID(),binding().browserHash()),REQUEST,token.value()).isEmpty());
            assertTrue(sessions.exchangeAccount(browser.session().reference(),REQUEST,Secrets.token().toCharArray()).isEmpty());
            assertTrue(sessions.exchangeAccount(browser.session().reference(),REQUEST,null).isEmpty());
            e.c.jdbc.update("UPDATE account_authentication_proof SET revoked_at=clock_timestamp()");
            assertTrue(exchange(sessions,proof).isEmpty());assertNull(value("SELECT consumed_at FROM account_authentication_proof"));
            assertTrue(sessions.inspect(browser.token()).isPresent());assertEquals(0,count("app_session WHERE kind='ACCOUNT'"));
        }
    }
    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void proofExpiryRejectsEqualityUsingOneTimeAfterLocks(long micros) {
        try(var proof=proof()) {
            Instant expiry=proof.proof().orElseThrow().expiresAt();var reads=new AtomicInteger();
            try(var result=exchange(new PostgresSessions(e.c.clock(expiry.plusNanos(micros*1000),reads)),proof).orElse(null)) {
                assertEquals(micros<0,result!=null);assertEquals(1,reads.get());
            }
        }
    }
    @Test void credentialEpochChangesInvalidateProofsAndAllSessionOperationsIncludingMutationReplay() {
        String original=value("SELECT password_hash FROM customer_account WHERE id=?",account.accountId());
        String changed=original.substring(0,original.length()-1)+(original.endsWith("A")?"E":"A");
        try(var issued=login()) {
            var ref=issued.session().reference();var session=new com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding(ref.id(),ref.browserHash());
            var request=e.c.request(3,9);var created=e.c.command.create(session,"created",request);assertTrue(created.success());
            e.c.jdbc.update("UPDATE customer_account SET password_hash=?,credential_version=credential_version+1,password_changed_at=clock_timestamp() WHERE id=?",changed,account.accountId());
            assertTrue(sessions.inspect(issued.token()).isEmpty());assertTrue(sessions.resolve(issued.token()).isEmpty());assertTrue(sessions.rotateCsrf(issued.token()).isEmpty());assertFalse(sessions.csrf(ref,issued.csrf()));
            assertEquals(com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure.Kind.FORBIDDEN,e.c.failure(()->e.c.command.create(session,"created",request)).kind());
            assertEquals(com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure.Kind.FORBIDDEN,e.c.failure(()->new com.purpuragenda.application.scheduling.CustomerBookingChanges(new PostgresBookingChanges(e.c.f.source))
                    .cancel(session,created.entityId(),"cancel",new com.purpuragenda.application.scheduling.CustomerBookingChanges.Cancellation(1))).kind());
            assertEquals(1,count("booking"));assertEquals(1,count("request_charge"));
            e.c.jdbc.update("UPDATE customer_account SET password_hash=?,credential_version=credential_version+1,password_changed_at=clock_timestamp() WHERE id=?",original,account.accountId());
            assertTrue(sessions.inspect(issued.token()).isEmpty());
            e.c.f.database.denied("23514","UPDATE customer_account SET credential_version=1 WHERE id=?",account.accountId());
        }
        browser.close();browser=sessions.anonymous();
        try(var proof=proof()) { epoch();assertTrue(exchange(sessions,proof).isEmpty());assertNull(value("SELECT consumed_at FROM account_authentication_proof WHERE token_hash=?",proof.proof().get().token().hash())); }
    }
    @Test void proofAndAccountSessionInsertFailuresRollBackConsumptionPriorRevocationAndAudit() throws Exception {
        try(var proof=proof()) {
            reject("app_session","ACCOUNT");unavailable(()->exchange(sessions,proof),"SESSION_UNAVAILABLE");
            assertNull(value("SELECT consumed_at FROM account_authentication_proof"));assertTrue(sessions.inspect(browser.token()).isPresent());
            assertEquals(0,count("audit_event WHERE type='ACCOUNT_SESSION_CREATED'"));assertEquals(1,count("app_session"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"account_authentication_proof","audit_event"})
    void factorWriteFailuresLeaveNoProofOrPasswordVerificationAudit(String table) throws Exception {
        reject(table,"ACCOUNT_PASSWORD_VERIFIED");char[] password=AccountEnrollmentDatabaseTest.password();
        unavailable(()->command.authenticate(EMAIL,password,IP,binding()),"ACCOUNT_AUTHENTICATION_UNAVAILABLE");clear(password);
        assertEquals(0,count("account_authentication_proof"));assertEquals(0,count("audit_event WHERE type='ACCOUNT_PASSWORD_VERIFIED'"));
    }
    void reject(String table,String kind) throws Exception {
        try(var c=e.c.f.database.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(c,"CREATE FUNCTION reject_account() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'SYNTHETIC_FAILURE'; END $$");
            String when=table.equals("app_session")?" WHEN(NEW.kind='"+kind+"')":table.equals("audit_event")?" WHEN(NEW.type='"+kind+"')":"";
            MigrationTest.execute(c,"CREATE TRIGGER reject_account BEFORE INSERT ON "+table+" FOR EACH ROW"+when+" EXECUTE FUNCTION reject_account()");
        }
    }
    @Test void twentyExchangesAcrossTwoStoresConsumeExactlyOneProofAndIssueOneAccountSession() throws Exception {
        try(var proof=proof();var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var other=new PostgresSessions(e.c.f.source);var ready=new CountDownLatch(20);var release=new CountDownLatch(1);
            var futures=new ArrayList<Future<Optional<Issued>>>();
            for(int i=0;i<20;i++){int index=i;futures.add(pool.submit(()->{ready.countDown();release.await();return exchange(index%2==0?sessions:other,proof);}));}
            assertTrue(ready.await(5,TimeUnit.SECONDS));release.countDown();int winners=0;
            for(var future:futures)try(var value=future.get(30,TimeUnit.SECONDS).orElse(null)){if(value!=null)winners++;}
            assertEquals(1,winners);assertEquals(1,count("app_session WHERE kind='ACCOUNT'"));assertEquals(1,count("account_authentication_proof WHERE consumed_at IS NOT NULL"));
        }
    }
    @Test void cleanupRemovesExpiredAccountProofsWithinTheSameGlobalBoundAndNeverActiveProofs() {
        try(var proof=proof()) {
            assertEquals(0,new PostgresSecurityCleanup(e.c.f.source).proofs(1,Duration.ofHours(24)));
            var cleanup=new PostgresSecurityCleanup(e.c.clock(proof.proof().get().expiresAt().plus(Duration.ofHours(24)),new AtomicInteger()));
            assertEquals(1,cleanup.proofs(1,Duration.ofHours(24)));assertEquals(0,count("account_authentication_proof"));assertEquals(1,count("customer_account"));
        }
    }
    @Test void proofsCannotBeUsedBeforeTheirIssueTimeAndRevokedPriorSessionsDoNotConsumeThem() {
        try(var proof=proof()) {
            Instant issued=proof.proof().get().expiresAt().minusSeconds(60);
            assertTrue(exchange(new PostgresSessions(e.c.clock(issued.minusNanos(1000),new AtomicInteger())),proof).isEmpty());
            sessions.revoke(browser.session().reference());assertTrue(exchange(sessions,proof).isEmpty());
            assertNull(value("SELECT consumed_at FROM account_authentication_proof"));assertEquals(0,count("app_session WHERE kind='ACCOUNT'"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"enrolled","password"})
    void futureCredentialTimesNeverAuthorizePasswordProofs(String field) {
        var source=e.c.f.source;
        if(field.equals("password"))e.c.jdbc.update("UPDATE customer_account SET password_changed_at=clock_timestamp()+interval '1 day'");
        else source=null;
        var adapter=source==null?new PostgresAccountAuthentication(e.c.clock(account.enrolledAt().minusNanos(1000),new AtomicInteger())):store;
        var absent=adapter.locked(EMAIL,context->context.credential().isEmpty());assertTrue(absent);assertEquals(0,count("account_authentication_proof"));
    }
    @Test void contextCannotEscapeCrossThreadsOrIssueRepeatedMalformedOrUnknownAccountProofs() throws Exception {
        var escaped=new java.util.concurrent.atomic.AtomicReference<AccountAuthenticationUnitOfWork.Account>();
        store.locked(EMAIL,context->{
            escaped.set(context);assertNotNull(context.now());
            try(var worker=Executors.newVirtualThreadPerTaskExecutor()) {
                assertThrows(ExecutionException.class,()->worker.submit(context::credential).get());
            }
            return null;
        });
        assertThrows(IllegalStateException.class,()->escaped.get().credential());
        unavailable(()->store.locked(EMAIL,context->{context.issueProof("e".repeat(64),binding());return context.issueProof("f".repeat(64),binding());}),"ACCOUNT_AUTHENTICATION_UNAVAILABLE");
        unavailable(()->store.locked(EMAIL,context->context.issueProof(null,binding())),"ACCOUNT_AUTHENTICATION_UNAVAILABLE");
        unavailable(()->store.locked("absent@example.test",context->context.issueProof("e".repeat(64),binding())),"ACCOUNT_AUTHENTICATION_UNAVAILABLE");
        assertEquals(0,count("account_authentication_proof"));
    }
    @Test void malformedBindingsAndProviderFailuresAreSanitizedAndClearBorrowedProofs() {
        try(var proof=proof()) {
            char[] borrowed=proof.proof().get().token().value();
            assertThrows(IllegalArgumentException.class,()->sessions.exchangeAccount(browser.session().reference(),"invalid",borrowed));clear(borrowed);
            assertNull(value("SELECT consumed_at FROM account_authentication_proof"));
        }
        var broken=new org.springframework.jdbc.datasource.DelegatingDataSource(e.c.f.source) {
            @Override public Connection getConnection() throws SQLException { throw new SQLException("synthetic-private-provider-detail"); }
        };
        unavailable(()->new PostgresAccountAuthentication(broken).locked(EMAIL,context->null),"ACCOUNT_AUTHENTICATION_UNAVAILABLE");
        assertThrows(NullPointerException.class,()->new PostgresAccountAuthentication(null));
        assertThrows(NullPointerException.class,()->store.locked(EMAIL,null));
        assertEquals("AccountAuthenticationStore[REDACTED]",store.toString());
    }
    @Test void databaseProofAndSessionShapesRejectCrossPurposeMissingEpochAndInvalidLifetimes() {
        try(var proof=proof()) {
            for(String change:List.of("purpose='ADMIN_AUTHENTICATION'","credential_version=0","expires_at=expires_at+interval '1 microsecond'","consumed_at=expires_at"))
                e.c.f.database.denied("23514","UPDATE account_authentication_proof SET "+change);
        }
        e.c.f.database.denied("23514","UPDATE app_session SET account_credential_version=1 WHERE kind='ANONYMOUS'");
        try(var issued=login()) {
            e.c.f.database.denied("23514","UPDATE app_session SET account_credential_version=NULL WHERE id=?",issued.session().reference().id());
        }
    }
    @Test void exchangeWaitsForCredentialLockAndRechecksEpochBeforeConsumingProof() throws Exception {
        try(var proof=proof();var blocker=e.c.f.database.connection(MigrationTest.APP);var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            String name="account-exchange-lock-"+UUID.randomUUID();e.c.f.source.setApplicationName(name);blocker.setAutoCommit(false);
            MigrationTest.execute(blocker,"SELECT id FROM customer_account WHERE id=? FOR UPDATE",account.accountId());
            var reads=new AtomicInteger();var future=pool.submit(()->exchange(new PostgresSessions(e.c.clock(proof.proof().get().expiresAt().minusSeconds(59),reads)),proof));
            awaitLock(name);assertEquals(0,reads.get());
            MigrationTest.execute(blocker,"UPDATE customer_account SET credential_version=credential_version+1 WHERE id=?",account.accountId());blocker.commit();
            assertTrue(future.get(10,TimeUnit.SECONDS).isEmpty());assertEquals(1,reads.get());
            assertNull(value("SELECT consumed_at FROM account_authentication_proof"));assertTrue(sessions.inspect(browser.token()).isPresent());
        }
    }
    @Test void factorWaitsForCredentialLockBeforeItsOnlyCapturedTimeAndSeesCurrentVersion() throws Exception {
        try(var blocker=e.c.f.database.connection(MigrationTest.APP);var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            String name="account-factor-lock-"+UUID.randomUUID();e.c.f.source.setApplicationName(name);blocker.setAutoCommit(false);
            MigrationTest.execute(blocker,"SELECT id FROM customer_account WHERE id=? FOR UPDATE",account.accountId());
            var reads=new AtomicInteger();var fixed=new PostgresAccountAuthentication(e.c.clock(e.c.now().plusSeconds(10),reads));
            var future=pool.submit(()->fixed.locked(EMAIL,context->context.credential().orElseThrow().version()));
            awaitLock(name);assertEquals(0,reads.get());MigrationTest.execute(blocker,"UPDATE customer_account SET credential_version=credential_version+1 WHERE id=?",account.accountId());blocker.commit();
            assertEquals(2,future.get(10,TimeUnit.SECONDS));assertEquals(1,reads.get());
        }
    }
    void awaitLock(String name) throws Exception {
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(System.nanoTime()<limit) {
            if(e.c.jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock'",Integer.class,name)>0)return;
            Thread.sleep(10);
        }
        fail("SYNTHETIC_ACCOUNT_LOCK_NOT_OBSERVED");
    }
    @Test void upgradePreservesAccountsRevokesUnverifiableSessionsAndRequiresCurrentEpochSchema() throws Exception {
        e.c.f.database.newSchema();e.c.f.database.migrate(15); // Through016; immutable015/016 are not rewritten.
        e.c.f.source.setCurrentSchema(e.c.f.database.schema+",public");UUID customer=UUID.randomUUID(),id=UUID.randomUUID(),session=UUID.randomUUID();
        String token=Secrets.token();Instant now=Instant.parse("2026-10-01T10:00:00Z");
        try(var c=e.c.f.database.connection(MigrationTest.APP)) {
            MigrationTest.execute(c,"INSERT INTO customer_identity VALUES(?,'upgrade@example.test',?)",customer,now);
            MigrationTest.execute(c,"INSERT INTO customer_account(id,customer_id,enrolled_at,login_email,password_hash,password_changed_at) VALUES(?,?,?,'upgrade@example.test',?,?)",id,customer,now,AccountEnrollmentDatabaseTest.encoded,now);
            MigrationTest.execute(c,"""
                INSERT INTO app_session(id,token_hash,browser_hash,csrf_hash,kind,customer_id,account_id,issued_at,last_seen_at,absolute_expires_at,authenticated_at)
                VALUES(?,?,?,?,'ACCOUNT',?,?,?,?,?,?)
                """,session,Secrets.hash(token),binding().browserHash(),Secrets.hash("synthetic-csrf"),customer,id,now,now,now.plusSeconds(43200),now);
        }
        var ready=new DatabaseReadiness(e.c.f.source,new PostgresScheduling(e.c.f.source));
        assertEquals(org.springframework.boot.health.contributor.Status.DOWN,ready.health().getStatus());
        e.c.f.database.migrate(0);e.c.f.database.migrate(0);assertEquals(org.springframework.boot.health.contributor.Status.UP,ready.health().getStatus());
        assertEquals(16,e.c.f.database.scalar(MigrationTest.MIGRATOR,"SELECT count(*) FROM databasechangelog"));
        assertEquals(1,e.c.f.database.scalar(MigrationTest.APP,"SELECT count(*) FROM customer_account"));
        assertEquals(1,e.c.f.database.scalar(MigrationTest.APP,"SELECT count(*) FROM app_session WHERE revoked_at IS NOT NULL AND account_credential_version=1"));
        assertTrue(sessions.inspect(token.toCharArray()).isEmpty());
    }
    @Test void passwordLoginCannotDowngradeAnAdministratorSession() throws Exception {
        var admin=new AdminMfaDatabaseTest();admin.fixture=e.c.f.database;admin.source=admin.source();admin.jdbc=new org.springframework.jdbc.core.JdbcTemplate(admin.source);
        admin.secrets=new Secrets(Files.write(directory.resolve("totp.key"),SecureRandom.getSeed(32)),"account-admin-test-v1");
        new PrivateAdminBootstrap(new Argon2PasswordHash(),new PostgresPrivateAdmin(admin.source,admin.secrets)).create("owner@example.test",AdminMfaDatabaseTest.password());
        admin.store=new PostgresAdminMfa(admin.source,admin.secrets);admin.mfa=admin.workflow(admin.store);
        try(var recovery=admin.mfa.enroll("owner@example.test",AdminMfaDatabaseTest.password(),admin.code(admin.now().getEpochSecond()/30)).orElseThrow();
            var authenticated=admin.mfa.authenticateForSession("owner@example.test",AdminMfaDatabaseTest.password(),admin.freshCode(),binding()).orElseThrow();
            var session=sessions.exchangeAdmin(browser.session().reference(),REQUEST,authenticated.token().value()).orElseThrow();var proof=proof()) {
            assertTrue(sessions.exchangeAccount(session.session().reference(),REQUEST,proof.proof().get().token().value()).isEmpty());
            assertTrue(sessions.inspect(session.token()).isPresent());assertNull(value("SELECT consumed_at FROM account_authentication_proof"));
        }
    }
}
