package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.security.*;
import com.purpuragenda.application.PrivateAdminBootstrap;
import com.purpuragenda.application.port.out.PrivateAdminStore.Result;
import com.purpuragenda.domain.DomainFailure;
import java.nio.file.*;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class PrivateAdminDatabaseTest {
    @TempDir Path temporary;
    MigrationTest fixture; DriverManagerDataSource source; JdbcTemplate jdbc; Secrets secrets;
    PostgresPrivateAdmin store;
    static String encoded;
    @BeforeAll static void start() {
        MigrationTest.start(); encoded=new Argon2PasswordHash().hash("synthetic bootstrap phrase".toCharArray());
    }
    @AfterAll static void stop() { MigrationTest.stop(); }
    DriverManagerDataSource source() {
        var value=new DriverManagerDataSource(MigrationTest.jdbcUrl,MigrationTest.APP,MigrationTest.appPassword);
        Properties properties=new Properties(); properties.setProperty("currentSchema",fixture.schema+",public");
        properties.setProperty("ApplicationName","p16_bootstrap");value.setConnectionProperties(properties);return value;
    }
    @BeforeEach void prepare() throws Exception {
        fixture=new MigrationTest();fixture.fresh();source=source();jdbc=new JdbcTemplate(source);
        byte[] key=SecureRandom.getSeed(32);Path file=temporary.resolve("totp.key");Files.write(file,key);Arrays.fill(key,(byte)0);
        secrets=new Secrets(file,"totp-v1");store=new PostgresPrivateAdmin(source,secrets);
    }
    @AfterEach void cleanup() { fixture.cleanup(); }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
    String hash() { return jdbc.queryForObject("SELECT password_hash FROM admin_user",String.class); }
    String envelope() { return jdbc.queryForObject("SELECT totp_secret FROM admin_user",String.class); }
    @Test void privateBootstrapPersistsOnlyHashAndEncryptedUnenrolledMfa() {
        char[] password="synthetic bootstrap phrase".toCharArray();
        assertEquals(Result.CREATED,new PrivateAdminBootstrap(new Argon2PasswordHash(),store).create("Owner@Example.test",password));
        assertTrue(Arrays.equals(password,new char[password.length]),"PASSWORD_BUFFER_NOT_CLEARED");
        assertTrue(new Argon2PasswordHash().verify("synthetic bootstrap phrase".toCharArray(),hash()));
        assertEquals(1,count("admin_user"));assertEquals(0,count("admin_recovery_code"));
        assertEquals(1,count("audit_event"));assertTrue(jdbc.queryForObject("SELECT type FROM audit_event",String.class).equals("ADMIN_BOOTSTRAPPED"));
        assertFalse(jdbc.queryForObject("SELECT enrolled FROM admin_user",Boolean.class));
        assertTrue(jdbc.queryForObject("SELECT enabled FROM admin_user",Boolean.class));
        assertEquals(-1,jdbc.queryForObject("SELECT last_totp_step FROM admin_user",Long.class));
        assertTrue(envelope().startsWith("totp-v1:"));byte[] secret=Base64.getDecoder().decode(secrets.decrypt(envelope()));
        assertEquals(20,secret.length);Arrays.fill(secret,(byte)0);
    }
    @Test void repeatedOrDifferentIdentityCannotResetExistingCredentials() throws Exception {
        assertEquals(Result.CREATED,store.createUnenrolled("owner@example.test",encoded));
        String beforeHash=hash(),beforeEnvelope=envelope();
        String replacement=new Argon2PasswordHash().hash("synthetic replacement phrase".toCharArray());
        assertEquals(Result.ALREADY_EXISTS,store.createUnenrolled("owner@example.test",replacement));
        assertEquals(Result.ALREADY_EXISTS,store.createUnenrolled("other@example.test",replacement));
        Path alternate=temporary.resolve("other.key");byte[] bytes=SecureRandom.getSeed(32);Files.write(alternate,bytes);Arrays.fill(bytes,(byte)0);
        var differentKey=new PostgresPrivateAdmin(source,new Secrets(alternate,"different-key"));
        assertEquals(Result.ALREADY_EXISTS,differentKey.createUnenrolled("other@example.test",replacement));
        assertTrue(beforeHash.equals(hash()));assertTrue(beforeEnvelope.equals(envelope()));assertEquals(1,count("admin_user"));assertEquals(1,count("audit_event"));
    }
    @Test void disabledHistoricalAdministratorStillBlocksReplacement() {
        store.createUnenrolled("owner@example.test",encoded);String before=envelope();
        jdbc.update("UPDATE admin_user SET enabled=false");
        assertEquals(Result.ALREADY_EXISTS,store.createUnenrolled("other@example.test",encoded));
        assertFalse(jdbc.queryForObject("SELECT enabled FROM admin_user",Boolean.class));assertTrue(before.equals(envelope()));
    }
    @Test void twoActuallyBlockedIndependentConnectionsCanCreateOnlyOneAdministrator() throws Exception {
        try(Connection blocker=fixture.connection(MigrationTest.APP);var executor=Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);MigrationTest.execute(blocker,"LOCK TABLE admin_user IN SHARE ROW EXCLUSIVE MODE");
            var second=new PostgresPrivateAdmin(source(),secrets);
            Future<Result> first=executor.submit(()->store.createUnenrolled("first@example.test",encoded));
            Future<Result> other=executor.submit(()->second.createUnenrolled("second@example.test",encoded));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);int blocked=0;
            while(System.nanoTime()<deadline) {
                MigrationTest.execute(blocker,"SELECT pg_stat_clear_snapshot()");
                try(var statement=blocker.createStatement();var rows=statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name='p16_bootstrap' AND wait_event_type='Lock'")) {
                    rows.next();blocked=rows.getInt(1);if(blocked==2)break;
                }
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
            assertEquals(2,blocked,"BOOTSTRAP_CONNECTIONS_DID_NOT_REACH_LOCK");blocker.commit();
            assertEquals(Set.of(Result.CREATED,Result.ALREADY_EXISTS),Set.of(first.get(20,TimeUnit.SECONDS),other.get(20,TimeUnit.SECONDS)));
            assertEquals(1,count("admin_user"));assertTrue(hash().equals(encoded));
        }
    }
    @Test void privateBootstrapCommitIsIndependentOfAnUnrelatedOuterRollback() {
        var outer=new TransactionTemplate(new DataSourceTransactionManager(source));
        assertThrows(IllegalStateException.class,()->outer.execute(status->{
            assertEquals(Result.CREATED,store.createUnenrolled("owner@example.test",encoded));
            throw new IllegalStateException("SYNTHETIC_OUTER_ROLLBACK");
        }));
        assertEquals(1,count("admin_user"));assertFalse(jdbc.queryForObject("SELECT enrolled FROM admin_user",Boolean.class));
    }
    @Test void databaseFailureRollsBackAndNeverExposesProviderData() throws Exception {
        try(Connection c=fixture.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(c,"CREATE FUNCTION reject_admin() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic-private-provider-marker'; END $$");
            MigrationTest.execute(c,"CREATE TRIGGER reject_admin BEFORE INSERT ON admin_user FOR EACH ROW EXECUTE FUNCTION reject_admin()");
        }
        var failure=assertThrows(IllegalStateException.class,()->store.createUnenrolled("owner@example.test",encoded));
        assertEquals("ADMIN_BOOTSTRAP_UNAVAILABLE",failure.getMessage());assertNull(failure.getCause());
        assertEquals(0,count("admin_user"));assertEquals(0,count("admin_recovery_code"));
    }
    @Test void requiredAuditFailureRollsBackTheAdministratorToo() throws Exception {
        try(Connection c=fixture.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(c,"CREATE FUNCTION reject_bootstrap_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic-private-audit-marker'; END $$");
            MigrationTest.execute(c,"CREATE TRIGGER reject_bootstrap_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_bootstrap_audit()");
        }
        var failure=assertThrows(IllegalStateException.class,()->store.createUnenrolled("owner@example.test",encoded));
        assertEquals("ADMIN_BOOTSTRAP_UNAVAILABLE",failure.getMessage());assertNull(failure.getCause());
        assertEquals(0,count("admin_user"));assertEquals(0,count("audit_event"));
    }
    @Test void uncanonicalIdentityAndMalformedHashCannotWrite() {
        assertThrows(DomainFailure.class,()->store.createUnenrolled("Owner@Example.test",encoded));
        assertThrows(DomainFailure.class,()->store.createUnenrolled("owner@example.test","invalid"));
        assertEquals(0,count("admin_user"));
    }
}
