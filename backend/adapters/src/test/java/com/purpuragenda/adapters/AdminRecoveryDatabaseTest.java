package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.security.*;
import com.purpuragenda.application.PrivateAdminRecovery;
import com.purpuragenda.application.PrivateAdminRecovery.Reenrollment;
import com.purpuragenda.application.ReenrollmentGrantPolicy.Binding;
import com.purpuragenda.application.port.out.AdminRecoveryUnitOfWork.Admin;
import com.purpuragenda.application.port.out.AdminMfaUnitOfWork.*;
import com.purpuragenda.application.port.out.AdminRecoveryCrypto;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class AdminRecoveryDatabaseTest {
    @TempDir Path temporary;AdminMfaDatabaseTest f;PostgresAdminRecovery store;PrivateAdminRecovery recovery;
    final List<char[]> codes=new ArrayList<>();final Binding binding=binding(1,2);long initialVersion;
    @BeforeAll static void start() { AdminMfaDatabaseTest.start(); }
    @AfterAll static void stop() { AdminMfaDatabaseTest.stop(); }
    static Binding binding(int browser,int request) { byte[] b=new byte[32],r=new byte[32];Arrays.fill(b,(byte)browser);Arrays.fill(r,(byte)request);return new Binding(b,r); }
    static char[] password() { return AdminMfaDatabaseTest.password(); }
    @BeforeEach void prepare() throws Exception {
        f=new AdminMfaDatabaseTest();f.temporary=temporary;f.prepare();
        try(var result=f.mfa.enroll("owner@example.test",password(),f.code(f.now().getEpochSecond()/30)).orElseThrow()) { result.codes().forEach(code->codes.add(code.value())); }
        initialVersion=f.jdbc.queryForObject("SELECT security_version FROM admin_user",Long.class);
        store=new PostgresAdminRecovery(f.source,f.secrets);recovery=workflow(store);
    }
    @AfterEach void cleanup() { codes.forEach(value->Arrays.fill(value,'\0'));f.cleanup(); }
    PrivateAdminRecovery workflow(PostgresAdminRecovery target) { return new PrivateAdminRecovery(target,new Argon2PasswordHash(),new AdminCrypto()); }
    Reenrollment begin() { return recovery.begin("owner@example.test",password(),codes.getFirst().clone(),binding).orElseThrow(); }
    long version() { return f.jdbc.queryForObject("SELECT security_version FROM admin_user",Long.class)-initialVersion; }
    long consumed() { return f.jdbc.queryForObject("SELECT count(*) FROM admin_recovery_code WHERE consumed_at IS NOT NULL",Long.class); }
    void sessions() {
        f.jdbc.update("INSERT INTO spring_session VALUES (?,?,0,0,900,9999999999999,(SELECT id::text FROM admin_user))",UUID.randomUUID().toString(),UUID.randomUUID().toString());
        f.jdbc.update("INSERT INTO spring_session VALUES (?,?,0,0,900,9999999999999,'synthetic-other')",UUID.randomUUID().toString(),UUID.randomUUID().toString());
    }
    Optional<Enrollment> finish(Reenrollment value,Binding expected,String code) { return recovery.reenroll("owner@example.test",password(),value.token().value(),expected,code); }
    @Test void recoveryAtomicallyConsumesOneCodeRotatesSecretAndRevokesOrdinaryAccess() throws Exception {
        sessions();byte[] previous=f.secret();String oldCode=f.freshCode();
        try(var value=begin()) {
            assertFalse(Arrays.equals(previous,value.secret().bytes()));assertFalse(f.enrolled());assertEquals(-1,f.previous());assertEquals(1,version());
            assertEquals(1,consumed());assertEquals(10,f.count("admin_recovery_code"));assertEquals(1,f.count("admin_reenrollment_grant"));assertEquals(1,f.count("spring_session"));
            assertEquals(600,f.jdbc.queryForObject("SELECT extract(epoch FROM expires_at-issued_at)::integer FROM admin_reenrollment_grant",Integer.class));
            assertTrue(f.jdbc.queryForObject("SELECT g.issued_at=c.consumed_at AND g.issued_at=a.occurred_at FROM admin_reenrollment_grant g JOIN admin_recovery_code c ON c.id=g.recovery_code_id JOIN audit_event a ON a.actor_id=g.admin_id AND a.type='ADMIN_RECOVERY_STARTED'",Boolean.class));
            assertFalse(f.mfa.authenticate("owner@example.test",password(),oldCode));assertTrue(f.mfa.provision("owner@example.test",password()).isEmpty());
            assertTrue(f.mfa.enroll("owner@example.test",password(),f.freshCode()).isEmpty());assertEquals(3,f.count("audit_event"));
        } finally { Arrays.fill(previous,(byte)0); }
    }
    @Test void exactBoundRetryRotatesOnlyProofWithoutExtendingExpiryOrReusingOldToken() throws Exception {
        try(var first=begin()) {
            assertEquals(f.jdbc.queryForObject("SELECT expires_at FROM admin_reenrollment_grant",Timestamp.class).toInstant(),first.expiresAt());
            Instant expires=f.jdbc.queryForObject("SELECT expires_at FROM admin_reenrollment_grant",Timestamp.class).toInstant();
            assertTrue(recovery.begin("owner@example.test",password(),codes.getFirst().clone(),binding(2,2)).isEmpty());
            assertTrue(recovery.begin("owner@example.test",password(),codes.getFirst().clone(),binding(1,3)).isEmpty());
            try(var resumed=begin()) {
                assertEquals(expires,resumed.expiresAt());assertTrue(Arrays.equals(first.secret().bytes(),resumed.secret().bytes()));assertFalse(first.token().hash().equals(resumed.token().hash()));
                assertTrue(finish(first,binding,f.freshCode()).isEmpty());assertEquals(expires,f.jdbc.queryForObject("SELECT expires_at FROM admin_reenrollment_grant",Timestamp.class).toInstant());
                assertEquals(1,version());assertEquals(1,consumed());assertEquals(1,f.count("admin_reenrollment_grant"));
            }
        }
    }
    @Test void wrongPasswordMissingCodeUnknownAndDisabledIdentityLeaveRecoveryUntouched() {
        assertTrue(recovery.begin("missing@example.test",password(),codes.getFirst().clone(),binding).isEmpty());
        assertTrue(recovery.begin("owner@example.test","synthetic wrong password phrase".toCharArray(),codes.getFirst().clone(),binding).isEmpty());
        try(var wrong=new AdminCrypto().recoveryCode()) { assertTrue(recovery.begin("owner@example.test",password(),wrong.value(),binding).isEmpty()); }
        f.jdbc.update("UPDATE admin_user SET enabled=false");assertTrue(recovery.begin("owner@example.test",password(),codes.getFirst().clone(),binding).isEmpty());
        assertEquals(0,consumed());assertEquals(1,version());assertEquals(0,f.count("admin_reenrollment_grant"));assertTrue(f.enrolled());
    }
    @Test void reenrollmentConsumesGrantRevokesOldCodesAndStillRequiresFreshMfa() throws Exception {
        try(var value=begin()) {
            String code=f.code(f.now().getEpochSecond()/30);
            assertTrue(finish(value,binding(2,2),code).isEmpty());assertTrue(finish(value,binding(1,3),code).isEmpty());assertTrue(finish(value,binding,"invalid").isEmpty());
            assertTrue(recovery.reenroll("owner@example.test","synthetic wrong password phrase".toCharArray(),value.token().value(),binding,code).isEmpty());
            try(var result=finish(value,binding,code).orElseThrow()) { assertEquals(10,result.codes().size()); }
            assertTrue(f.enrolled());assertEquals(2,version());assertEquals(20,f.count("admin_recovery_code"));
            assertEquals(10,f.jdbc.queryForObject("SELECT count(*) FROM admin_recovery_code WHERE revoked_at IS NOT NULL",Integer.class));
            assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM admin_reenrollment_grant WHERE consumed_at IS NOT NULL",Integer.class));
            assertTrue(finish(value,binding,code).isEmpty());assertTrue(recovery.begin("owner@example.test",password(),codes.get(1).clone(),binding).isEmpty());
            assertFalse(f.mfa.authenticate("owner@example.test",password(),code));assertTrue(f.mfa.authenticate("owner@example.test",password(),f.freshCode()));
        }
    }
    @Test void expiredGrantCannotResumeAndAnotherUnusedCodeCanRecoverPendingReenrollment() throws Exception {
        try(var expired=begin()) {
            f.jdbc.update("UPDATE admin_reenrollment_grant SET issued_at=issued_at-interval '1 hour',expires_at=expires_at-interval '1 hour'");
            assertTrue(finish(expired,binding,f.freshCode()).isEmpty());assertTrue(recovery.begin("owner@example.test",password(),codes.getFirst().clone(),binding).isEmpty());
            try(var next=recovery.begin("owner@example.test",password(),codes.get(1).clone(),binding(3,4)).orElseThrow()) {
                assertFalse(Arrays.equals(expired.secret().bytes(),next.secret().bytes()));assertEquals(2,version());assertEquals(2,consumed());
                assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM admin_reenrollment_grant WHERE revoked_at IS NOT NULL",Integer.class));
                assertTrue(finish(expired,binding,"invalid").isEmpty());
            }
        }
    }
    @Test void oneRecoveryCodeAcrossIndependentBrowserRacesHasOneWinner() throws Exception {
        var other=workflow(new PostgresAdminRecovery(f.source(),f.secrets));
        var results=f.contended(()->recovery.begin("owner@example.test",password(),codes.getFirst().clone(),binding),
                ()->other.begin("owner@example.test",password(),codes.getFirst().clone(),binding(9,9)));
        assertEquals(1,results.stream().filter(Optional::isPresent).count());results.forEach(value->value.ifPresent(Reenrollment::close));
        assertEquals(1,consumed());assertEquals(1,version());assertEquals(1,f.count("admin_reenrollment_grant"));
    }
    @Test void oneGrantAcrossIndependentReenrollmentRacesHasOneWinner() throws Exception {
        try(var value=begin()) {
            String code=f.freshCode();var other=workflow(new PostgresAdminRecovery(f.source(),f.secrets));
            var results=f.contended(()->finish(value,binding,code),()->other.reenroll("owner@example.test",password(),value.token().value(),binding,code));
            assertEquals(1,results.stream().filter(Optional::isPresent).count());results.forEach(result->result.ifPresent(Enrollment::close));
            assertEquals(20,f.count("admin_recovery_code"));assertEquals(2,version());
        }
    }
    void rejectAudit() {
        try(Connection connection=f.fixture.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(connection,"CREATE FUNCTION reject_recovery_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic-private-marker'; END $$");
            MigrationTest.execute(connection,"CREATE TRIGGER reject_recovery_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_recovery_audit()");
        } catch(SQLException failure) { throw MigrationTest.safe(failure); }
    }
    @Test void auditFailureRollsBackConsumptionRotationRevocationAndUnreturnedProof() {
        sessions();String encrypted=f.jdbc.queryForObject("SELECT totp_secret FROM admin_user",String.class);rejectAudit();
        List<RecoveryCode> issued=new ArrayList<>();var delegate=new AdminCrypto();
        AdminRecoveryCrypto crypto=new AdminRecoveryCrypto() {
            public Optional<String> digest(char[] value) { return delegate.digest(value); }
            public long acceptedStep(byte[] secret,String code,Instant now,long previous) { return delegate.acceptedStep(secret,code,now,previous); }
            public RecoveryCode recoveryCode() { var code=delegate.recoveryCode();issued.add(code);return code; }
        };
        var failed=new PrivateAdminRecovery(store,new Argon2PasswordHash(),crypto);
        var error=assertThrows(IllegalStateException.class,()->failed.begin("owner@example.test",password(),codes.getFirst().clone(),binding));
        assertEquals("ADMIN_RECOVERY_UNAVAILABLE",error.getMessage());assertNull(error.getCause());assertEquals(1,issued.size());
        issued.forEach(code->assertTrue(Arrays.equals(new char[43],code.value())));assertTrue(f.enrolled());assertEquals(0,consumed());assertEquals(0,version());
        assertEquals(0,f.count("admin_reenrollment_grant"));assertEquals(2,f.count("spring_session"));assertTrue(encrypted.equals(f.jdbc.queryForObject("SELECT totp_secret FROM admin_user",String.class)));
    }
    @Test void reenrollmentAuditFailurePreservesPendingGrantAndOldRecoveryFacts() throws Exception {
        try(var value=begin()) {
            String code=f.freshCode();rejectAudit();
            var failure=assertThrows(IllegalStateException.class,()->finish(value,binding,code));
            assertEquals("ADMIN_RECOVERY_UNAVAILABLE",failure.getMessage());assertNull(failure.getCause());
            assertFalse(f.enrolled());assertEquals(-1,f.previous());assertEquals(1,version());assertEquals(10,f.count("admin_recovery_code"));
            assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM admin_recovery_code WHERE revoked_at IS NOT NULL",Integer.class));
            assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM admin_reenrollment_grant WHERE consumed_at IS NULL AND revoked_at IS NULL",Integer.class));
        }
    }
    @Test void revocationDisableAndEpochChangeInvalidateOtherwiseCorrectProof() throws Exception {
        try(var value=begin()) {
            String code=f.freshCode();f.jdbc.update("UPDATE admin_user SET enabled=false");assertTrue(finish(value,binding,code).isEmpty());
            f.jdbc.update("UPDATE admin_user SET enabled=true,security_version=security_version+1");assertTrue(finish(value,binding,code).isEmpty());
            f.fixture.denied("23514","UPDATE admin_user SET security_version=security_version-1");
            try(var next=recovery.begin("owner@example.test",password(),codes.get(1).clone(),binding).orElseThrow()) {
                f.jdbc.update("UPDATE admin_reenrollment_grant SET revoked_at=clock_timestamp() WHERE revoked_at IS NULL");
                assertTrue(finish(next,binding,f.freshCode()).isEmpty());assertTrue(recovery.begin("owner@example.test",password(),codes.get(1).clone(),binding).isEmpty());
            }
        }
    }
    @Test void contextsCannotEscapeOrCrossThreadsAndDirectUnprovenWritesFailClosed() throws Exception {
        AtomicReference<Admin> escaped=new AtomicReference<>();
        store.locked("owner@example.test",admin->{
            escaped.set(admin);assertSame(admin.now(),admin.now());assertTrue(admin.identity().isPresent());
            try(var executor=Executors.newSingleThreadExecutor()) { var result=executor.submit(admin::now);var failure=assertThrows(ExecutionException.class,()->result.get(5,TimeUnit.SECONDS));assertEquals("ADMIN_RECOVERY_CONTEXT_CLOSED",failure.getCause().getMessage()); }
            return null;
        });
        assertThrows(IllegalStateException.class,()->escaped.get().now());assertThrows(IllegalStateException.class,()->escaped.get().identity());
        assertThrows(IllegalStateException.class,()->escaped.get().begin("invalid",binding,"invalid"));assertThrows(IllegalStateException.class,()->escaped.get().prove("invalid",binding));assertThrows(IllegalStateException.class,()->escaped.get().reenroll(1,List.of()));
        assertThrows(IllegalStateException.class,()->store.locked("owner@example.test",admin->{admin.reenroll(1,List.of());return null;}));
        store.locked("missing@example.test",admin->{assertTrue(admin.begin("invalid",binding,"invalid").isEmpty());assertTrue(admin.prove("invalid",binding).isEmpty());return null;});
        assertThrows(IllegalStateException.class,()->store.locked("missing@example.test",admin->{admin.reenroll(1,List.of());return null;}));
    }
    @Test void malformedSecretAndInvalidReenrollmentHashesCannotPartiallyConsumeGrant() {
        try(var value=begin()) {
            String original=f.jdbc.queryForObject("SELECT totp_secret FROM admin_user",String.class);
            for(String invalid:List.of("invalid",f.secrets.encrypt(Base64.getEncoder().encodeToString(new byte[19])))) {
                f.jdbc.update("UPDATE admin_user SET totp_secret=?",invalid);
                // Simulate matching-epoch storage corruption to exercise decryption failure separately from revocation.
                f.jdbc.update("UPDATE admin_reenrollment_grant SET security_version=(SELECT security_version FROM admin_user)");
                var failure=assertThrows(IllegalStateException.class,()->finish(value,binding,"invalid"));assertEquals("ADMIN_RECOVERY_UNAVAILABLE",failure.getMessage());assertNull(failure.getCause());
            }
            f.jdbc.update("UPDATE admin_user SET totp_secret=?",original);
            f.jdbc.update("UPDATE admin_reenrollment_grant SET security_version=(SELECT security_version FROM admin_user)");long expectedVersion=version();
            List<String> hashes=new ArrayList<>();for(int i=0;i<10;i++)hashes.add(String.format("%064x",i));
            for(List<String> invalid:List.of(List.<String>of(),Collections.nCopies(10,"a".repeat(64)),new ArrayList<>(hashes))) {
                if(invalid instanceof ArrayList<String>)invalid.set(0,"invalid");
                assertThrows(IllegalStateException.class,()->store.locked("owner@example.test",admin->{try(var secret=admin.prove(value.token().hash(),binding).orElseThrow()) { admin.reenroll(7,invalid); }return null;}));
            }
            assertThrows(IllegalStateException.class,()->store.locked("owner@example.test",admin->{try(var secret=admin.prove(value.token().hash(),binding).orElseThrow()) { admin.reenroll(-1,hashes); }return null;}));
            assertFalse(f.enrolled());assertEquals(expectedVersion,version());assertEquals(10,f.count("admin_recovery_code"));
        }
    }
    @Test void additiveMigrationPreservesExistingAdminHistoryAndRejectsWrongPurposeOrLifetime() {
        try(var value=begin()) {
            f.fixture.denied("23514","UPDATE admin_reenrollment_grant SET purpose='ADMIN_AUTHENTICATION'");
            f.fixture.denied("23514","UPDATE admin_reenrollment_grant SET expires_at=expires_at+interval '1 microsecond'");
            f.fixture.denied("23514","UPDATE admin_reenrollment_grant SET token_hash='invalid'");
            f.fixture.denied("23514","UPDATE admin_reenrollment_grant SET consumed_at=issued_at-interval '1 microsecond'");
            UUID other=UUID.randomUUID(),otherCode=UUID.randomUUID();
            f.jdbc.update("INSERT INTO admin_user(id,email_key,password_hash,totp_secret,enabled) SELECT ?,'other@example.test',password_hash,totp_secret,false FROM admin_user",other);
            f.jdbc.update("INSERT INTO admin_recovery_code(id,admin_id,code_hash) VALUES (?,?,?)",otherCode,other,"b".repeat(64));
            f.fixture.denied("23503","UPDATE admin_reenrollment_grant SET recovery_code_id=?",otherCode);
        }
        f.fixture.newSchema();f.fixture.migrate(9);
        var source=f.source();new PostgresPrivateAdmin(source,f.secrets).createUnenrolled("upgrade@example.test",AdminMfaDatabaseTest.hash);
        try(Connection connection=f.fixture.connection(MigrationTest.APP)) {
            MigrationTest.execute(connection,"INSERT INTO admin_recovery_code(id,admin_id,code_hash,consumed_at) SELECT ?,id,?,clock_timestamp() FROM admin_user",UUID.randomUUID(),"a".repeat(64));
        } catch(SQLException failure) { throw MigrationTest.safe(failure); }
        f.fixture.migrate(0);f.fixture.migrate(0);
        assertEquals(1,f.fixture.scalar(MigrationTest.APP,"SELECT count(*) FROM admin_user WHERE security_version=0"));
        assertEquals(1,f.fixture.scalar(MigrationTest.APP,"SELECT count(*) FROM admin_recovery_code WHERE consumed_at IS NOT NULL AND revoked_at IS NULL"));
    }
}
