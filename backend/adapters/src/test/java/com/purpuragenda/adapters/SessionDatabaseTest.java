package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.security.*;
import com.purpuragenda.adapters.out.email.*;
import com.purpuragenda.application.PrivateAdminRecovery;
import com.purpuragenda.application.ReenrollmentGrantPolicy.Binding;
import com.purpuragenda.application.port.out.SessionStore.*;
import com.purpuragenda.application.port.out.AdminMfaUnitOfWork.*;
import com.purpuragenda.application.port.out.GuestAccessStore;
import com.purpuragenda.application.port.out.AuthDeliveryStore;
import java.net.URI;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class SessionDatabaseTest {
    @TempDir Path directory;AdminMfaDatabaseTest f;PostgresSessions sessions;Issued browser;char[] recoveryCode;
    static final String REQUEST="a".repeat(64);
    @BeforeAll static void start() { AdminMfaDatabaseTest.start(); }
    @AfterAll static void stop() { AdminMfaDatabaseTest.stop(); }
    @BeforeEach void prepare() throws Exception {
        f=new AdminMfaDatabaseTest();f.temporary=directory;f.prepare();
        try(var codes=f.mfa.enroll("owner@example.test",password(),f.code(f.now().getEpochSecond()/30)).orElseThrow()) { recoveryCode=codes.codes().getFirst().value(); }
        sessions=new PostgresSessions(f.source);browser=sessions.anonymous();
    }
    @AfterEach void cleanup() { browser.close();Arrays.fill(recoveryCode,'\0');f.cleanup(); }
    static char[] password() { return AdminMfaDatabaseTest.password(); }
    SessionReference ref() { return browser.session().reference(); }
    AuthenticationProof proof() throws Exception { return f.mfa.authenticateForSession("owner@example.test",password(),f.freshCode(),new ProofBinding(ref().browserHash(),REQUEST)).orElseThrow(); }
    Optional<Issued> exchange(PostgresSessions store,AuthenticationProof proof) { return store.exchangeAdmin(ref(),REQUEST,proof.token().value()); }
    void safe(org.junit.jupiter.api.function.Executable action) { var failure=assertThrows(IllegalStateException.class,action);assertEquals("SESSION_UNAVAILABLE",failure.getMessage());assertNull(failure.getCause());assertEquals(0,failure.getSuppressed().length); }
    PostgresSessions at(Instant captured,AtomicInteger reads) {
        var source=new DelegatingDataSource(f.source) {
            @Override public Connection getConnection() throws SQLException {
                var connection=super.getConnection();return GuestAccessDatabaseTest.proxy(Connection.class,(p,m,a)->{
                    Object result=GuestAccessDatabaseTest.invoke(connection,m,a);if(!m.getName().equals("createStatement"))return result;
                    return GuestAccessDatabaseTest.proxy(Statement.class,(sp,sm,sa)->{
                        Object rows=GuestAccessDatabaseTest.invoke(result,sm,sa);
                        if(!sm.getName().equals("executeQuery") || !"SELECT clock_timestamp()".equals(sa[0]))return rows;reads.incrementAndGet();
                        return GuestAccessDatabaseTest.proxy(ResultSet.class,(rp,rm,ra)->rm.getName().equals("getTimestamp") && Integer.valueOf(1).equals(ra[0])?Timestamp.from(captured):GuestAccessDatabaseTest.invoke(rows,rm,ra));
                    });
                });
            }
        };return new PostgresSessions(source);
    }
    GuestAccessStore.Redemption guest(String email) throws Exception {
        return emailProof(email,GuestAccessStore.Kind.CREATE,null,ref().browserHash());
    }
    GuestAccessStore.Redemption emailProof(String email,GuestAccessStore.Kind kind,UUID booking,String browserHash) throws Exception {
        Path key=directory.resolve("delivery.key");Files.write(key,new byte[32]);
        var delivery=new PostgresAuthDeliveryStore(f.source,new Secrets(key,"delivery"),URI.create("https://example.test"),false);
        delivery.issue(new AuthDeliveryStore.Issue(email,AuthDeliveryStore.Purpose.valueOf(kind.name()),booking,kind==GuestAccessStore.Kind.CREATE?browserHash:null));
        var queue=new PostgresNotificationQueue(f.source,false);var lease=queue.claimAuth().orElseThrow();
        String token=delivery.prepare(lease).orElseThrow().text().split("#token=",2)[1].split("&",2)[0];assertTrue(queue.sent(lease));
        return new GuestAccessStore.Redemption(token,kind,browserHash);
    }
    void rejectSessionInsert() {
        try(var c=f.fixture.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(c,"CREATE FUNCTION reject_session() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic-private-marker'; END $$");
            MigrationTest.execute(c,"CREATE TRIGGER reject_session BEFORE INSERT ON app_session FOR EACH ROW EXECUTE FUNCTION reject_session()");
        } catch(SQLException failure) { throw MigrationTest.safe(failure); }
    }
    @Test void anonymousLookupStoresOnlyHashesAndConsumesCookieCopies() {
        assertEquals(Kind.ANONYMOUS,browser.session().kind());assertNull(browser.session().customerId());assertNull(browser.session().authenticatedAt());
        char[] token=browser.token();var snapshot=sessions.resolve(token).orElseThrow();assertTrue(Arrays.equals(new char[43],token));assertEquals(ref(),snapshot.reference());
        assertTrue(f.jdbc.queryForObject("SELECT token_hash=? AND csrf_hash=? FROM app_session",Boolean.class,new AdminCrypto().digest(browser.token()).orElseThrow(),new AdminCrypto().digest(browser.csrf()).orElseThrow()));
        assertEquals(Duration.ofHours(2),Duration.between(snapshot.issuedAt(),snapshot.absoluteExpiresAt()));
        assertTrue(sessions.resolve(null).isEmpty());assertTrue(sessions.resolve(new char[42]).isEmpty());
        try(var unknown=new AdminCrypto().recoveryCode()) { assertTrue(sessions.resolve(unknown.value()).isEmpty()); }
        assertEquals("SessionStore[REDACTED]",sessions.toString());
    }
    @Test void upgradeAndRerunPreserveExistingIdentityAndFenceLaterCredentialChanges() throws Exception {
        f.fixture.newSchema();f.fixture.migrate(11);UUID admin=UUID.randomUUID();
        try(var c=f.fixture.connection(MigrationTest.APP)) {
            MigrationTest.execute(c,"INSERT INTO admin_user(id,email_key,password_hash,totp_secret,enrolled,enabled,security_version) VALUES (?,'upgrade@example.test',?,'synthetic-encrypted-secret',true,true,7)",admin,AdminMfaDatabaseTest.hash);
        }
        f.fixture.migrate(0);f.fixture.migrate(0);
        assertEquals(12,f.fixture.scalar(MigrationTest.MIGRATOR,"SELECT count(*) FROM databasechangelog"));
        assertEquals(7,f.fixture.scalar(MigrationTest.APP,"SELECT security_version FROM admin_user"));
        try(var c=f.fixture.connection(MigrationTest.APP)) { MigrationTest.execute(c,"UPDATE admin_user SET enabled=false"); }
        assertEquals(8,f.fixture.scalar(MigrationTest.APP,"SELECT security_version FROM admin_user"));
        assertEquals(0,f.fixture.scalar(MigrationTest.APP,"SELECT count(*) FROM app_session"));assertEquals(0,f.fixture.scalar(MigrationTest.APP,"SELECT count(*) FROM admin_authentication_proof"));
    }
    @Test void csrfRequiresCurrentSessionBrowserAndDigestWithoutTouchingFailures() {
        Instant before=f.jdbc.queryForObject("SELECT last_seen_at FROM app_session",Timestamp.class).toInstant();
        assertFalse(sessions.csrf(ref(),null));assertFalse(sessions.csrf(ref(),"x".repeat(43).toCharArray()));
        assertFalse(sessions.csrf(new SessionReference(ref().id(),"b".repeat(64)),browser.csrf()));
        assertEquals(before,f.jdbc.queryForObject("SELECT last_seen_at FROM app_session",Timestamp.class).toInstant());
        char[] token=browser.csrf();assertTrue(sessions.csrf(ref(),token));assertTrue(Arrays.equals(new char[43],token));
        assertTrue(sessions.revoke(ref()));assertTrue(sessions.revoke(ref()));assertFalse(sessions.csrf(ref(),browser.csrf()));assertTrue(sessions.resolve(browser.token()).isEmpty());
        assertFalse(sessions.revoke(new SessionReference(UUID.randomUUID(),ref().browserHash())));
    }
    @Test void idleAndAbsoluteExpiryRejectEqualityWithoutExtendingAbsoluteLifetime() {
        Instant issued=browser.session().issuedAt();
        for(long micros:new long[]{0,1,-1}) { var reads=new AtomicInteger();assertEquals(micros<0,at(issued.plusSeconds(1800).plusNanos(micros*1000),reads).resolve(browser.token()).isPresent());assertEquals(1,reads.get()); }
        Instant expiry=browser.session().absoluteExpiresAt();f.jdbc.update("UPDATE app_session SET last_seen_at=?",Timestamp.from(expiry.minusSeconds(1)));
        for(long micros:new long[]{0,1,-1}) { var reads=new AtomicInteger();assertEquals(micros<0,at(expiry.plusNanos(micros*1000),reads).resolve(browser.token()).isPresent());assertEquals(1,reads.get()); }
        assertEquals(expiry,f.jdbc.queryForObject("SELECT absolute_expires_at FROM app_session",Timestamp.class).toInstant());
        assertTrue(at(issued.minusNanos(1000),new AtomicInteger()).resolve(browser.token()).isEmpty());
    }
    @Test void csrfRotationReplacesOnlyTheProofKeepingCookieBindingAndAbsoluteLifetime() {
        char[] oldCsrf=browser.csrf();String tokenHash=f.jdbc.queryForObject("SELECT token_hash FROM app_session",String.class);
        Instant absolute=browser.session().absoluteExpiresAt();
        try(var rotated=sessions.rotateCsrf(browser.token()).orElseThrow()) {
            assertEquals(ref(),rotated.session().reference());assertEquals(absolute,rotated.session().absoluteExpiresAt());
            assertEquals("CsrfRotation[REDACTED]",rotated.toString());
            assertEquals(tokenHash,f.jdbc.queryForObject("SELECT token_hash FROM app_session",String.class));
            assertEquals(1,f.count("app_session"));assertFalse(sessions.csrf(ref(),oldCsrf));
            assertTrue(sessions.csrf(ref(),rotated.csrf()));
            assertTrue(f.jdbc.queryForObject("SELECT csrf_hash=? FROM app_session",Boolean.class,new AdminCrypto().digest(rotated.csrf()).orElseThrow()));
            assertTrue(sessions.resolve(browser.token()).isPresent());
        }
        char[] token=browser.token();sessions.rotateCsrf(token);assertTrue(Arrays.equals(new char[43],token));
    }
    @Test void csrfRotationRefusesUnknownMalformedRevokedAndExpiredSessionsAtExactBoundaries() {
        assertTrue(sessions.rotateCsrf(null).isEmpty());assertTrue(sessions.rotateCsrf(new char[42]).isEmpty());
        try(var unknown=new AdminCrypto().recoveryCode()) { assertTrue(sessions.rotateCsrf(unknown.value()).isEmpty()); }
        Instant issued=browser.session().issuedAt();String before=f.jdbc.queryForObject("SELECT csrf_hash FROM app_session",String.class);
        for(long micros:new long[]{0,1,-1}) {
            var reads=new AtomicInteger();var result=at(issued.plusSeconds(1800).plusNanos(micros*1000),reads).rotateCsrf(browser.token());
            assertEquals(micros<0,result.isPresent());assertEquals(1,reads.get());result.ifPresent(r->r.close());
            f.jdbc.update("UPDATE app_session SET last_seen_at=?",Timestamp.from(issued));
        }
        f.jdbc.update("UPDATE app_session SET csrf_hash=?",before);
        assertTrue(at(issued.minusNanos(1000),new AtomicInteger()).rotateCsrf(browser.token()).isEmpty());
        assertEquals(before,f.jdbc.queryForObject("SELECT csrf_hash FROM app_session",String.class));
        assertTrue(sessions.revoke(ref()));assertTrue(sessions.rotateCsrf(browser.token()).isEmpty());
        assertEquals(before,f.jdbc.queryForObject("SELECT csrf_hash FROM app_session",String.class));
    }
    @Test void csrfRotationAndLogoutAcquireNoAdministratorLockAndAbsoluteDeadlineStillWins() throws Exception {
        try(var proof=proof();var admin=exchange(sessions,proof).orElseThrow();var holder=f.source.getConnection()) {
            holder.setAutoCommit(false);
            try(var lock=holder.createStatement()) { lock.execute("SELECT 1 FROM admin_user FOR UPDATE"); }
            long started=System.nanoTime();
            try(var rotated=sessions.rotateCsrf(admin.token()).orElseThrow()) { assertTrue(sessions.csrf(admin.session().reference(),rotated.csrf())); }
            assertTrue(sessions.revoke(admin.session().reference()));
            assertTrue(Duration.ofNanos(System.nanoTime()-started).compareTo(Duration.ofSeconds(1))<0);
            holder.rollback();
            assertTrue(sessions.rotateCsrf(admin.token()).isEmpty());
        }
    }
    @Test void anonymousLifetimeIsElapsedAcrossTheBrusselsAutumnTransition() {
        Instant now=Instant.parse("2026-10-25T00:30:00Z");var reads=new AtomicInteger();
        try(var value=at(now,reads).anonymous()) { assertEquals(now.plusSeconds(7200),value.session().absoluteExpiresAt());assertEquals(1,reads.get()); }
    }
    @Test void mfaProofAndAcceptedStepCommitTogetherWithoutGrantingASession() throws Exception {
        long version=f.jdbc.queryForObject("SELECT security_version FROM admin_user",Long.class);
        try(var proof=proof()) {
            assertEquals(1,f.count("admin_authentication_proof"));assertEquals(1,f.count("app_session"));
            assertTrue(f.jdbc.queryForObject("SELECT p.token_hash=? AND p.security_version=? AND p.expires_at=p.issued_at+interval '60 seconds' AND a.occurred_at=p.issued_at FROM admin_authentication_proof p JOIN audit_event a ON a.actor_id=p.admin_id AND a.type='ADMIN_MFA_VERIFIED'",Boolean.class,proof.token().hash(),version));
            assertEquals(version,f.jdbc.queryForObject("SELECT security_version FROM admin_user",Long.class));
        }
        assertThrows(IllegalStateException.class,()->f.store.locked("owner@example.test",admin->{admin.issueAuthenticationProof("a".repeat(64),new ProofBinding(ref().browserHash(),REQUEST));return null;}));
    }
    @Test void adminExchangeRotatesCookieSessionAndCsrfWhilePreservingBrowserAndRejectingReplay() throws Exception {
        try(var proof=proof();var authenticated=exchange(sessions,proof).orElseThrow()) {
            var value=authenticated.session();assertEquals(Kind.ADMIN,value.kind());assertEquals(ref().browserHash(),value.reference().browserHash());assertNotEquals(ref().id(),value.reference().id());
            assertFalse(Arrays.equals(browser.token(),authenticated.token()));assertFalse(Arrays.equals(browser.csrf(),authenticated.csrf()));
            assertEquals(value.issuedAt(),value.authenticatedAt());assertEquals(Duration.ofHours(8),Duration.between(value.issuedAt(),value.absoluteExpiresAt()));
            assertTrue(sessions.resolve(browser.token()).isEmpty());assertFalse(sessions.csrf(ref(),browser.csrf()));assertTrue(exchange(sessions,proof).isEmpty());
            assertTrue(sessions.resolve(authenticated.token()).isPresent());assertTrue(sessions.csrf(value.reference(),authenticated.csrf()));
            assertTrue(sessions.exchangeAdmin(value.reference(),REQUEST,proof.token().value()).isEmpty());assertEquals(2,f.count("app_session"));
            Instant last=f.jdbc.queryForObject("SELECT last_seen_at FROM app_session WHERE id=?",Timestamp.class,value.reference().id()).toInstant();
            assertTrue(at(last.plusSeconds(900),new AtomicInteger()).resolve(authenticated.token()).isEmpty());
        }
    }
    @Test void wrongBrowserRequestUnknownProofAndReenrollmentPurposeCannotCreateAdminSession() throws Exception {
        try(var proof=proof();var other=sessions.anonymous()) {
            assertTrue(sessions.exchangeAdmin(other.session().reference(),REQUEST,proof.token().value()).isEmpty());
            assertTrue(sessions.exchangeAdmin(new SessionReference(UUID.randomUUID(),ref().browserHash()),REQUEST,proof.token().value()).isEmpty());
            assertTrue(sessions.exchangeAdmin(ref(),"b".repeat(64),proof.token().value()).isEmpty());assertTrue(sessions.exchangeAdmin(ref(),REQUEST,null).isEmpty());
            assertTrue(sessions.exchangeAdmin(ref(),REQUEST,"x".repeat(43).toCharArray()).isEmpty());
            f.fixture.denied("23514","UPDATE admin_authentication_proof SET purpose='ADMIN_REENROLLMENT'");
            f.fixture.denied("23514","UPDATE admin_authentication_proof SET expires_at=expires_at+interval '1 microsecond'");
            f.jdbc.update("UPDATE admin_authentication_proof SET revoked_at=clock_timestamp()");assertTrue(exchange(sessions,proof).isEmpty());
            assertTrue(sessions.resolve(browser.token()).isPresent());assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM admin_authentication_proof WHERE consumed_at IS NOT NULL",Integer.class));
            assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM app_session WHERE kind='ADMIN'",Integer.class));
        }
        var recovery=new PrivateAdminRecovery(new PostgresAdminRecovery(f.source,f.secrets),new Argon2PasswordHash(),new AdminCrypto());
        try(var grant=recovery.begin("owner@example.test",password(),recoveryCode.clone(),new Binding(new byte[32],new byte[32])).orElseThrow()) { assertTrue(sessions.exchangeAdmin(ref(),REQUEST,grant.token().value()).isEmpty()); }
    }
    @Test void proofExpiryIsExclusiveAtExactCapturedMicrosecond() throws Exception {
        try(var proof=proof()) {
            var early=new AtomicInteger();assertTrue(exchange(at(proof.expiresAt().minusSeconds(60).minusNanos(1000),early),proof).isEmpty());assertEquals(1,early.get());
            for(long micros:new long[]{0,1,-1}) {
                var reads=new AtomicInteger();var result=exchange(at(proof.expiresAt().plusNanos(micros*1000),reads),proof);
                assertEquals(micros<0,result.isPresent());result.ifPresent(Issued::close);assertEquals(1,reads.get());
            }
        }
    }
    void changeCredential(String field,Runnable whileChanged) {
        Object original=f.jdbc.queryForObject("SELECT "+field+" FROM admin_user",Object.class);
        Object replacement=field.equals("enabled") || field.equals("enrolled")?false:"synthetic-changed-cipher-or-hash";
        f.jdbc.update("UPDATE admin_user SET "+field+"=?",replacement);whileChanged.run();f.jdbc.update("UPDATE admin_user SET "+field+"=?",original);
    }
    @ParameterizedTest @ValueSource(strings={"enabled","enrolled","totp_secret","password_hash"})
    void credentialChangesCannotResurrectAnEarlierProof(String field) throws Exception {
        try(var proof=proof()) { long before=f.jdbc.queryForObject("SELECT security_version FROM admin_user",Long.class);changeCredential(field,()->assertTrue(exchange(sessions,proof).isEmpty()));assertTrue(exchange(sessions,proof).isEmpty());assertEquals(before+2,f.jdbc.queryForObject("SELECT security_version FROM admin_user",Long.class)); }
    }
    @ParameterizedTest @ValueSource(strings={"enabled","enrolled","totp_secret","password_hash"})
    void credentialChangesRevokeAlreadyIssuedAdminSessions(String field) throws Exception {
        try(var proof=proof();var value=exchange(sessions,proof).orElseThrow()) { changeCredential(field,()->assertTrue(sessions.resolve(value.token()).isEmpty()));assertTrue(sessions.resolve(value.token()).isEmpty());assertFalse(sessions.csrf(value.session().reference(),value.csrf())); }
    }
    @Test void twentyConcurrentProofConsumptionsIssueExactlyOneSession() throws Exception {
        try(var proof=proof();var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var ready=new CountDownLatch(20);var start=new CountDownLatch(1);List<Future<Optional<Issued>>> results=new ArrayList<>();
            for(int i=0;i<20;i++)results.add(pool.submit(()->{ready.countDown();start.await();return exchange(new PostgresSessions(f.source()),proof);}));
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();int winners=0;
            for(var future:results) { var result=future.get(15,TimeUnit.SECONDS);if(result.isPresent()) { winners++;result.get().close(); } }
            assertEquals(1,winners);assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM app_session WHERE kind='ADMIN'",Integer.class));
        }
    }
    @Test void proofInsertFailureRollsBackAcceptedStepAndAllowsExactFactorRetry() throws Exception {
        long step=f.previous();String code=f.freshCode();
        try(var c=f.fixture.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(c,"CREATE FUNCTION reject_proof() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic-private-marker'; END $$");
            MigrationTest.execute(c,"CREATE TRIGGER reject_proof BEFORE INSERT ON admin_authentication_proof FOR EACH ROW EXECUTE FUNCTION reject_proof()");
        }
        var binding=new ProofBinding(ref().browserHash(),REQUEST);var failure=assertThrows(IllegalStateException.class,()->f.mfa.authenticateForSession("owner@example.test",password(),code,binding));
        assertEquals("ADMIN_MFA_UNAVAILABLE",failure.getMessage());assertNull(failure.getCause());assertEquals(step,f.previous());assertEquals(0,f.count("admin_authentication_proof"));assertEquals(2,f.count("audit_event"));
        try(var c=f.fixture.connection(MigrationTest.MIGRATOR)) { MigrationTest.execute(c,"DROP TRIGGER reject_proof ON admin_authentication_proof"); }
        try(var proof=f.mfa.authenticateForSession("owner@example.test",password(),code,binding).orElseThrow()) { assertEquals(1,f.count("admin_authentication_proof")); }
    }
    @Test void sessionInsertFailureRollsBackProofConsumptionAndOldSessionRevocation() throws Exception {
        try(var proof=proof()) {
            rejectSessionInsert();safe(()->exchange(sessions,proof));assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM admin_authentication_proof WHERE consumed_at IS NOT NULL",Integer.class));
            assertTrue(sessions.resolve(browser.token()).isPresent());assertTrue(sessions.csrf(ref(),browser.csrf()));
            try(var c=f.fixture.connection(MigrationTest.MIGRATOR)) { MigrationTest.execute(c,"DROP TRIGGER reject_session ON app_session"); }
            try(var value=exchange(sessions,proof).orElseThrow()) { assertEquals(Kind.ADMIN,value.session().kind()); }
        }
    }
    @Test void recoveryRacingSessionHandoffLeavesNoAdministratorAccess() throws Exception {
        try(var proof=proof()) {
            var recovery=new PrivateAdminRecovery(new PostgresAdminRecovery(f.source(),f.secrets),new Argon2PasswordHash(),new AdminCrypto());
            var result=f.contended(()->{var value=exchange(new PostgresSessions(f.source()),proof);value.ifPresent(Issued::close);return value.isPresent();},
                    ()->{try(var value=recovery.begin("owner@example.test",password(),recoveryCode.clone(),new Binding(new byte[32],new byte[32])).orElseThrow()) { return true; }});
            assertTrue(result.get(1));assertFalse(f.enrolled());assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM app_session WHERE kind='ADMIN'",Integer.class));
        }
    }
    @Test void guestExchangeConsumesProofGrantAndSessionRotationInOneTransaction() throws Exception {
        var request=guest("synthetic@example.test");
        try(var value=sessions.exchangeGuest(ref(),request).orElseThrow()) {
            assertEquals(Kind.GUEST,value.session().kind());assertEquals(ref().browserHash(),value.session().reference().browserHash());assertTrue(sessions.resolve(browser.token()).isEmpty());
            assertTrue(sessions.resolve(value.token()).isPresent());assertEquals(1,f.count("access_grant"));assertEquals(0,f.count("customer_account"));
            assertTrue(f.jdbc.queryForObject("SELECT s.issued_at=g.issued_at AND c.consumed_at=g.issued_at FROM app_session s JOIN access_grant g ON g.id=s.grant_id JOIN email_challenge c ON c.id=g.challenge_id WHERE s.id=?",Boolean.class,value.session().reference().id()));
        }
    }
    @Test void guestProofCannotDowngradeAnAdministratorSessionOrConsumeAuthority() throws Exception {
        var request=guest("synthetic@example.test");
        try(var proof=proof();var admin=exchange(sessions,proof).orElseThrow()) {
            assertTrue(sessions.exchangeGuest(admin.session().reference(),request).isEmpty());assertTrue(sessions.resolve(admin.token()).isPresent());
            assertEquals(0,f.count("access_grant"));assertEquals(0,f.count("customer_identity"));assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM email_challenge WHERE consumed_at IS NOT NULL",Integer.class));
        }
    }
    @Test void managementExchangeUsesFreshBrowserAndCurrentContactWithoutTransferringOwnership() throws Exception {
        UUID booking=f.fixture.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        UUID owner=f.jdbc.queryForObject("SELECT customer_id FROM booking WHERE id=?",UUID.class,booking);
        f.jdbc.update("UPDATE booking SET delivery_email='old-delivery@example.test' WHERE id=?",booking);
        try(var other=sessions.anonymous()) {
            var previous=other.session().reference();var stale=emailProof("old-delivery@example.test",GuestAccessStore.Kind.MANAGE,booking,previous.browserHash());
            f.jdbc.update("UPDATE booking SET delivery_email='current-delivery@example.test' WHERE id=?",booking);
            assertTrue(sessions.exchangeGuest(previous,stale).isEmpty());assertEquals(0,f.count("access_grant"));assertEquals(2,f.count("app_session"));
            assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM email_challenge WHERE consumed_at IS NOT NULL",Integer.class));
            var current=emailProof("current-delivery@example.test",GuestAccessStore.Kind.MANAGE,booking,previous.browserHash());
            try(var value=sessions.exchangeGuest(previous,current).orElseThrow()) {
                assertEquals(owner,value.session().customerId());assertEquals(previous.browserHash(),value.session().reference().browserHash());assertNotEquals(ref().browserHash(),value.session().reference().browserHash());
                assertTrue(f.jdbc.queryForObject("SELECT kind='MANAGE' AND booking_id=? AND customer_id=? FROM access_grant WHERE id=?",Boolean.class,booking,owner,value.session().grantId()));
                assertTrue(sessions.resolve(other.token()).isEmpty());assertTrue(sessions.resolve(value.token()).isPresent());assertEquals(1,f.count("customer_identity"));assertEquals(0,f.count("customer_account"));
                assertTrue(sessions.exchangeGuest(value.session().reference(),stale).isEmpty());assertEquals(1,f.count("access_grant"));
                var renewed=emailProof("current-delivery@example.test",GuestAccessStore.Kind.MANAGE,booking,previous.browserHash());
                try(var next=sessions.exchangeGuest(value.session().reference(),renewed).orElseThrow()) {
                    assertEquals(owner,next.session().customerId());assertTrue(sessions.resolve(value.token()).isEmpty());assertTrue(sessions.resolve(next.token()).isPresent());
                }
            }
        }
    }
    @Test void sessionLockWaitPrecedesGuestProofTimeAndExpiredProofRemainsUnconsumed() throws Exception {
        var request=guest("synthetic@example.test");Instant expiry=f.jdbc.queryForObject("SELECT expires_at FROM email_challenge",Timestamp.class).toInstant();var reads=new AtomicInteger();
        try(var blocker=f.fixture.connection(MigrationTest.APP);var executor=Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);MigrationTest.execute(blocker,"SELECT id FROM app_session WHERE id=? FOR UPDATE",ref().id());
            var result=executor.submit(()->at(expiry,reads).exchangeGuest(ref(),request));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);long waiting=0;
            while(System.nanoTime()<deadline) {
                MigrationTest.execute(blocker,"SELECT pg_stat_clear_snapshot()");
                try(var q=blocker.createStatement();var r=q.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name='p16_mfa' AND wait_event_type='Lock'")) { r.next();waiting=r.getLong(1);if(waiting==1)break; }
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
            assertEquals(1,waiting,"SESSION_CONNECTION_DID_NOT_REACH_LOCK");assertEquals(0,reads.get());blocker.commit();assertTrue(result.get(10,TimeUnit.SECONDS).isEmpty());assertEquals(1,reads.get());
        }
        assertEquals(0,f.count("access_grant"));assertEquals(0,f.count("customer_identity"));assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM email_challenge WHERE consumed_at IS NOT NULL",Integer.class));
    }
    @Test void staleOrWrongBrowserSessionRollsBackGuestProofAndIdentityCreation() throws Exception {
        var request=guest("synthetic@example.test");
        assertTrue(sessions.exchangeGuest(new SessionReference(UUID.randomUUID(),ref().browserHash()),request).isEmpty());
        assertTrue(sessions.exchangeGuest(new SessionReference(ref().id(),"b".repeat(64)),request).isEmpty());sessions.revoke(ref());assertTrue(sessions.exchangeGuest(ref(),request).isEmpty());
        assertEquals(0,f.count("access_grant"));assertEquals(0,f.count("customer_identity"));assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM email_challenge WHERE consumed_at IS NOT NULL",Integer.class));
    }
    @Test void failedGuestSessionInsertRollsBackGrantAndChallengeConsumption() throws Exception {
        var request=guest("synthetic@example.test");rejectSessionInsert();safe(()->sessions.exchangeGuest(ref(),request));
        assertEquals(0,f.count("access_grant"));assertEquals(0,f.count("customer_identity"));assertTrue(sessions.resolve(browser.token()).isPresent());
        assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM email_challenge WHERE consumed_at IS NOT NULL",Integer.class));
    }
    @ParameterizedTest @ValueSource(strings={"CHILD","PARENT"})
    void consumedCreationKeepsOnlyItsDerivedManagementScopeAfterThirtyMinutes(String revoked) throws Exception {
        UUID booking=f.fixture.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        String email=f.jdbc.queryForObject("SELECT c.email_key FROM customer_identity c JOIN booking b ON b.customer_id=c.id WHERE b.id=?",String.class,booking);
        try(var value=sessions.exchangeGuest(ref(),guest(email)).orElseThrow()) {
            f.jdbc.update("UPDATE access_grant SET booking_id=?,consumed_at=clock_timestamp() WHERE id=?",booking,value.session().grantId());
            UUID child=UUID.randomUUID();f.jdbc.update("INSERT INTO access_grant(id,customer_id,kind,booking_id,issued_at,expires_at,browser_hash,parent_grant_id) SELECT ?,customer_id,'MANAGE',booking_id,consumed_at,issued_at+interval '2 hours',browser_hash,id FROM access_grant WHERE id=?",child,value.session().grantId());
            Instant later=value.session().issuedAt().plusSeconds(1860);f.jdbc.update("UPDATE app_session SET last_seen_at=? WHERE id=?",Timestamp.from(later.minusSeconds(1)),value.session().reference().id());
            var store=at(later,new AtomicInteger());assertTrue(store.resolve(value.token()).isPresent());
            if(revoked.equals("PARENT")) { f.jdbc.update("UPDATE app_session SET grant_id=? WHERE id=?",child,value.session().reference().id());assertTrue(store.resolve(value.token()).isPresent()); }
            f.jdbc.update("UPDATE access_grant SET revoked_at=clock_timestamp() WHERE id=?",revoked.equals("PARENT")?value.session().grantId():child);assertTrue(store.resolve(value.token()).isEmpty());
        }
    }
    @Test void accountStorageKeepsItsIdentityAndTwelveHourAbsoluteLimit() {
        UUID owner=UUID.randomUUID(),account=UUID.randomUUID();f.jdbc.update("INSERT INTO customer_identity VALUES (?,'account@example.test',clock_timestamp())",owner);
        f.jdbc.update("INSERT INTO customer_account VALUES (?,?,clock_timestamp())",account,owner);
        f.jdbc.update("UPDATE app_session SET kind='ACCOUNT',customer_id=?,account_id=?,authenticated_at=issued_at,absolute_expires_at=issued_at+interval '12 hours' WHERE id=?",owner,account,ref().id());
        var value=sessions.resolve(browser.token()).orElseThrow();assertEquals(Kind.ACCOUNT,value.kind());assertEquals(owner,value.customerId());assertEquals(account,value.accountId());assertEquals(Duration.ofHours(12),Duration.between(value.issuedAt(),value.absoluteExpiresAt()));
        f.fixture.denied("23514","UPDATE app_session SET csrf_hash='invalid'");f.fixture.denied("23514","UPDATE app_session SET absolute_expires_at=issued_at+interval '13 hours'");
    }
}
