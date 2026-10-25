package com.purpuragenda.adapters;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import com.purpuragenda.adapters.out.security.*;
import com.purpuragenda.application.PrivateAdminMfa;
import com.purpuragenda.application.port.out.AdminMfaUnitOfWork.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class AdminMfaDatabaseTest {
    @TempDir Path temporary;
    MigrationTest fixture;DriverManagerDataSource source;JdbcTemplate jdbc;Secrets secrets;
    PostgresAdminMfa store;PrivateAdminMfa mfa;static String hash;
    @BeforeAll static void start() { MigrationTest.start();hash=new Argon2PasswordHash().hash(password()); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    static char[] password() { return "synthetic private admin phrase".toCharArray(); }
    DriverManagerDataSource source() {
        var value=new DriverManagerDataSource(MigrationTest.jdbcUrl,MigrationTest.APP,MigrationTest.appPassword);
        Properties properties=new Properties();properties.setProperty("currentSchema",fixture.schema+",public");properties.setProperty("ApplicationName","p16_mfa");value.setConnectionProperties(properties);return value;
    }
    PrivateAdminMfa workflow(PostgresAdminMfa value) { return new PrivateAdminMfa(value,new Argon2PasswordHash(),new AdminCrypto()); }
    @BeforeEach void prepare() throws Exception {
        fixture=new MigrationTest();fixture.fresh();source=source();jdbc=new JdbcTemplate(source);
        Path key=temporary.resolve("totp.key");byte[] bytes=SecureRandom.getSeed(32);Files.write(key,bytes);Arrays.fill(bytes,(byte)0);
        secrets=new Secrets(key,"totp-v1");new PostgresPrivateAdmin(source,secrets).createUnenrolled("owner@example.test",hash);
        store=new PostgresAdminMfa(source,secrets);mfa=workflow(store);
    }
    @AfterEach void cleanup() { fixture.cleanup(); }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
    long previous() { return jdbc.queryForObject("SELECT last_totp_step FROM admin_user",Long.class); }
    boolean enrolled() { return jdbc.queryForObject("SELECT enrolled FROM admin_user",Boolean.class); }
    Instant now() { return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant(); }
    byte[] secret() { return Base64.getDecoder().decode(secrets.decrypt(jdbc.queryForObject("SELECT totp_secret FROM admin_user",String.class))); }
    String code(long step) throws Exception {
        byte[] bytes=secret();try {
            var generator=new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30),6,"HmacSHA1");
            return String.format(Locale.ROOT,"%06d",generator.generateOneTimePassword(new SecretKeySpec(bytes,"HmacSHA1"),Instant.ofEpochSecond(step*30)));
        } finally { Arrays.fill(bytes,(byte)0); }
    }
    String freshCode() throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
        do {
            long current=now().getEpochSecond()/30,step=current+1;
            if(step>previous()) {
                String value=code(step);
                // Avoid adjacent six-digit collisions when a real clock crosses a boundary.
                if(!value.equals(code(step-1)) && !value.equals(code(step+1)))return value;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
        } while(System.nanoTime()<deadline);
        throw new AssertionError("NO_FRESH_SYNTHETIC_TOTP_WINDOW");
    }
    void enroll() throws Exception { try(var codes=mfa.enroll("owner@example.test",password(),code(now().getEpochSecond()/30)).orElseThrow()) { assertEquals(10,codes.codes().size()); } }
    @Test void provisioningRequiresPrivatePasswordUnenrolledAndEnabledIdentity() throws Exception {
        assertTrue(mfa.provision("missing@example.test",password()).isEmpty());
        assertTrue(mfa.provision("owner@example.test","synthetic incorrect phrase".toCharArray()).isEmpty());
        try(var value=mfa.provision("owner@example.test",password()).orElseThrow()) { byte[] raw=secret();try { assertTrue(Arrays.equals(raw,value.bytes())); } finally { Arrays.fill(raw,(byte)0); } }
        enroll();assertTrue(mfa.provision("owner@example.test",password()).isEmpty());
        jdbc.update("UPDATE admin_user SET enrolled=false,enabled=false");assertTrue(mfa.provision("owner@example.test",password()).isEmpty());
    }
    @Test void enrollmentAtomicallyRecordsOneStepTenHashesAndRedactedPrivateCodes() throws Exception {
        String value=code(now().getEpochSecond()/30);
        try(var result=mfa.enroll("owner@example.test",password(),value).orElseThrow()) {
            assertTrue(enrolled());assertTrue(previous()>=0);assertEquals(10,count("admin_recovery_code"));assertEquals(2,count("audit_event"));
            Set<String> stored=new HashSet<>(jdbc.queryForList("SELECT code_hash FROM admin_recovery_code",String.class));
            assertTrue(stored.equals(new HashSet<>(result.codes().stream().map(RecoveryCode::hash).toList())));
            assertTrue(jdbc.queryForObject("SELECT bool_and(consumed_at IS NULL) FROM admin_recovery_code",Boolean.class));
            assertEquals("AdminEnrollment[REDACTED]",result.toString());
        }
        assertTrue(mfa.enroll("owner@example.test",password(),value).isEmpty());assertEquals(10,count("admin_recovery_code"));
    }
    @Test void invalidFactorsAndUnenrolledIdentityCannotAuthenticateOrAdvanceState() {
        assertFalse(mfa.authenticate("owner@example.test",password(),"123456"));
        assertTrue(mfa.enroll("owner@example.test",password(),"invalid").isEmpty());
        assertTrue(mfa.enroll("owner@example.test","synthetic incorrect phrase".toCharArray(),"123456").isEmpty());
        assertFalse(mfa.authenticate("missing@example.test",password(),"123456"));
        assertEquals(-1,previous());assertFalse(enrolled());assertEquals(0,count("admin_recovery_code"));assertEquals(1,count("audit_event"));
    }
    @Test void enrolledAuthenticationRequiresPasswordAndRejectsReplayedAcceptedStep() throws Exception {
        enroll();String value=freshCode();long before=previous();
        assertFalse(mfa.authenticate("owner@example.test","synthetic incorrect phrase".toCharArray(),value));assertEquals(before,previous());
        assertTrue(mfa.authenticate("owner@example.test",password(),value));long accepted=previous();assertTrue(accepted>before);
        assertFalse(mfa.authenticate("owner@example.test",password(),value));assertEquals(accepted,previous());
        assertEquals(3,count("audit_event"));assertEquals(10,count("admin_recovery_code"));
    }
    <T>List<T> contended(Callable<T> first,Callable<T> second) throws Exception {
        try(Connection blocker=fixture.connection(MigrationTest.APP);var executor=Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);MigrationTest.execute(blocker,"SELECT id FROM admin_user FOR UPDATE");
            Future<T> a=executor.submit(first),b=executor.submit(second);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);int waiting=0;
            while(System.nanoTime()<deadline) {
                MigrationTest.execute(blocker,"SELECT pg_stat_clear_snapshot()");
                try(var query=blocker.createStatement();var rows=query.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name='p16_mfa' AND wait_event_type='Lock'")) { rows.next();waiting=rows.getInt(1);if(waiting==2)break; }
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
            assertEquals(2,waiting,"INDEPENDENT_MFA_CONNECTIONS_DID_NOT_REACH_LOCK");blocker.commit();return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
    }
    @Test void concurrentEnrollmentIssuesRecoveryCodesToExactlyOneConnection() throws Exception {
        String value=freshCode();var other=workflow(new PostgresAdminMfa(source(),secrets));
        var results=contended(()->mfa.enroll("owner@example.test",password(),value),()->other.enroll("owner@example.test",password(),value));
        assertEquals(1,results.stream().filter(Optional::isPresent).count());results.forEach(result->result.ifPresent(Enrollment::close));
        assertEquals(10,count("admin_recovery_code"));assertEquals(2,count("audit_event"));
    }
    @Test void sameCodeAcrossTwoIndependentConnectionsAuthenticatesOnlyOnce() throws Exception {
        enroll();String value=freshCode();var other=workflow(new PostgresAdminMfa(source(),secrets));
        var results=contended(()->mfa.authenticate("owner@example.test",password(),value),()->other.authenticate("owner@example.test",password(),value));
        assertEquals(1,results.stream().filter(Boolean::booleanValue).count());assertEquals(3,count("audit_event"));
    }
    @Test void capturedDatabaseTimeIsStableAndContextsCannotEscapeOrCrossThreads() throws Exception {
        AtomicInteger clocks=new AtomicInteger();var wrapped=new DelegatingDataSource(source) {
            @Override public Connection getConnection() throws SQLException {
                Connection connection=super.getConnection();return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,args)->{
                    Object result=TransactionCoreTest.invoke(m,connection,args);if(!m.getName().equals("createStatement"))return result;
                    return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Statement.class},(q,n,values)->{ if(n.getName().equals("executeQuery") && ((String)values[0]).contains("clock_timestamp()"))clocks.incrementAndGet();return TransactionCoreTest.invoke(n,result,values); });
                });
            }
        };
        AtomicReference<Admin> escaped=new AtomicReference<>();
        new PostgresAdminMfa(wrapped,secrets).locked("owner@example.test",admin->{
            escaped.set(admin);assertSame(admin.now(),admin.now());assertTrue(admin.credential().isPresent());
            try(var executor=Executors.newSingleThreadExecutor()) { var future=executor.submit(admin::now);var failure=assertThrows(ExecutionException.class,()->future.get(5,TimeUnit.SECONDS));assertEquals("ADMIN_MFA_CONTEXT_CLOSED",failure.getCause().getMessage()); }
            return null;
        });
        assertEquals(1,clocks.get());assertThrows(IllegalStateException.class,()->escaped.get().now());assertThrows(IllegalStateException.class,()->escaped.get().credential());
        assertThrows(IllegalStateException.class,()->escaped.get().initialEnrollment());
        assertThrows(IllegalStateException.class,()->escaped.get().acceptStep(7));assertThrows(IllegalStateException.class,()->escaped.get().enroll(7,List.of()));
        assertThrows(IllegalStateException.class,()->escaped.get().issueAuthenticationProof("a".repeat(64),new com.purpuragenda.application.port.out.SessionStore.ProofBinding("b".repeat(64),"c".repeat(64))));
    }
    @Test void auditFailureRollsBackEnrollmentAndErasesIssuedCodeBuffers() throws Exception {
        try(Connection connection=fixture.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(connection,"CREATE FUNCTION reject_mfa_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic-private-marker'; END $$");
            MigrationTest.execute(connection,"CREATE TRIGGER reject_mfa_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_mfa_audit()");
        }
        List<RecoveryCode> issued=new ArrayList<>();var delegate=new AdminCrypto();
        var crypto=new com.purpuragenda.application.port.out.AdminFactorCrypto() {
            public long acceptedStep(byte[] secret,String value,Instant time,long previous) { return delegate.acceptedStep(secret,value,time,previous); }
            public RecoveryCode recoveryCode() { var value=delegate.recoveryCode();issued.add(value);return value; }
        };
        String value=code(now().getEpochSecond()/30);var failed=new PrivateAdminMfa(store,new Argon2PasswordHash(),crypto);
        var failure=assertThrows(IllegalStateException.class,()->failed.enroll("owner@example.test",password(),value));
        assertEquals("ADMIN_MFA_UNAVAILABLE",failure.getMessage());assertNull(failure.getCause());assertEquals(10,issued.size());
        issued.forEach(token->assertTrue(Arrays.equals(new char[43],token.value())));assertFalse(enrolled());assertEquals(-1,previous());assertEquals(0,count("admin_recovery_code"));
    }
    @Test void malformedStoredCredentialsFailClosedWithoutProviderDetails() {
        String encrypted=jdbc.queryForObject("SELECT totp_secret FROM admin_user",String.class);
        for(int mode=0;mode<4;mode++) {
            jdbc.update("UPDATE admin_user SET totp_secret=?,password_hash=?,last_totp_step=?",
                    mode==0?"invalid":mode==1?secrets.encrypt(Base64.getEncoder().encodeToString(new byte[19])):encrypted,
                    mode==3?"invalid":hash,mode==2?-2:-1);
            var failure=assertThrows(IllegalStateException.class,()->mfa.provision("owner@example.test",password()));
            assertEquals("ADMIN_MFA_UNAVAILABLE",failure.getMessage());assertNull(failure.getCause());assertFalse(enrolled());
        }
    }
    @Test void invalidDirectContextMutationsCannotPersistCredentialChanges() throws Exception {
        List<String> hashes=new ArrayList<>();for(int i=0;i<10;i++)hashes.add(String.format("%064x",i));
        assertThrows(IllegalStateException.class,()->store.locked("owner@example.test",admin->{admin.acceptStep(7);return null;}));
        for(List<String> invalid:List.of(List.<String>of(),Collections.nCopies(10,"a".repeat(64)),Collections.nCopies(10,"invalid")))
            assertThrows(IllegalStateException.class,()->store.locked("owner@example.test",admin->{admin.enroll(7,invalid);return null;}));
        assertThrows(IllegalStateException.class,()->store.locked("owner@example.test",admin->{admin.enroll(-1,hashes);return null;}));
        assertThrows(IllegalStateException.class,()->store.locked("missing@example.test",admin->{admin.enroll(7,hashes);return null;}));
        assertThrows(IllegalStateException.class,()->store.locked("missing@example.test",admin->{admin.acceptStep(7);return null;}));
        List<String> malformed=new ArrayList<>(hashes);malformed.set(0,"invalid");
        assertThrows(IllegalStateException.class,()->store.locked("owner@example.test",admin->{admin.enroll(7,malformed);return null;}));
        assertFalse(enrolled());assertEquals(0,count("admin_recovery_code"));enroll();
        assertThrows(IllegalStateException.class,()->store.locked("owner@example.test",admin->{admin.enroll(7,hashes);return null;}));
        assertThrows(IllegalStateException.class,()->store.locked("owner@example.test",admin->{admin.acceptStep(previous());return null;}));
    }
    @Test void unavailableHistoryFailsBeforeDecryptingCredentialsOrInvokingWorkflow() {
        jdbc.update("UPDATE admin_user SET totp_secret='invalid'");
        AtomicBoolean historyRequested=new AtomicBoolean();
        var wrapped=new DelegatingDataSource(source) {
            @Override public Connection getConnection() throws SQLException {
                Connection connection=super.getConnection();
                return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    if(method.getName().equals("prepareStatement") && ((String)args[0]).contains("SELECT NOT EXISTS")) {
                        historyRequested.set(true);throw new SQLException("synthetic-private-history-failure");
                    }
                    return TransactionCoreTest.invoke(method,connection,args);
                });
            }
        };
        var failure=assertThrows(IllegalStateException.class,()->new PostgresAdminMfa(wrapped,secrets).locked("owner@example.test",admin->{throw new AssertionError("WORKFLOW_MUST_NOT_RUN");}));
        assertTrue(historyRequested.get(),"HISTORY_MUST_BE_READ_BEFORE_SECRET_DECRYPTION");
        assertEquals("ADMIN_MFA_UNAVAILABLE",failure.getMessage());assertNull(failure.getCause());
        assertFalse(enrolled());assertEquals(-1,previous());assertEquals(0,count("admin_recovery_code"));
    }
    @Test void recoveryAndImmutableAuditHistoryPreventPasswordOnlyReenrollmentForever() throws Exception {
        enroll();jdbc.update("UPDATE admin_user SET enrolled=false,last_totp_step=-1");
        String value=freshCode();assertTrue(mfa.provision("owner@example.test",password()).isEmpty());
        assertTrue(mfa.enroll("owner@example.test",password(),value).isEmpty());assertEquals(10,count("admin_recovery_code"));
        jdbc.update("UPDATE admin_recovery_code SET consumed_at=clock_timestamp()");
        assertTrue(mfa.provision("owner@example.test",password()).isEmpty());
        jdbc.update("DELETE FROM admin_recovery_code");
        assertTrue(mfa.provision("owner@example.test",password()).isEmpty());assertTrue(mfa.enroll("owner@example.test",password(),value).isEmpty());
        assertThrows(IllegalStateException.class,()->store.locked("owner@example.test",admin->{
            assertFalse(admin.initialEnrollment());List<String> hashes=new ArrayList<>();for(int i=0;i<10;i++)hashes.add(String.format("%064x",i));admin.enroll(7,hashes);return null;
        }));
        assertFalse(enrolled());assertEquals(-1,previous());assertEquals(0,count("admin_recovery_code"));assertEquals(2,count("audit_event"));
        jdbc.update("UPDATE admin_user SET enrolled=true");assertTrue(mfa.authenticate("owner@example.test",password(),freshCode()));
    }

}
