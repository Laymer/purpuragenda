package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.postgres.PostgresScheduling;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.domain.DomainFailure;
import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reuses production-migration setup, never bypasses PostgreSQL or authentication. */
@Timeout(90)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class TransactionCoreTest {
    MigrationTest fixture;
    JdbcTemplate jdbc;
    DataSource source;
    PostgresScheduling unit;
    AtomicInteger clocks;
    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void prepare() {
        fixture=new MigrationTest(); fixture.fresh(); clocks=new AtomicInteger();
        var physical=new DriverManagerDataSource(MigrationTest.jdbcUrl,MigrationTest.APP,MigrationTest.appPassword);
        Properties properties=new Properties(); properties.setProperty("currentSchema",fixture.schema+",public");
        properties.setProperty("ApplicationName","p08_core"); physical.setConnectionProperties(properties);
        source=new DelegatingDataSource(physical) {
            @Override public Connection getConnection() throws SQLException {
                Connection connection=super.getConnection();
                return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    Object result=invoke(method,connection,args);
                    if(method.getName().equals("prepareStatement") && ((String)args[0]).contains("clock_timestamp()")) clocks.incrementAndGet();
                    if(!method.getName().equals("createStatement")) return result;
                    return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Statement.class},(p,m,a)->{
                        if(m.getName().equals("executeQuery") && ((String)a[0]).contains("clock_timestamp()")) clocks.incrementAndGet();
                        return invoke(m,result,a);
                    });
                });
            }
        };
        jdbc=new JdbcTemplate(source); unit=new PostgresScheduling(source);
        try(Connection c=fixture.connection(MigrationTest.MIGRATOR)) { MigrationTest.execute(c,"CREATE TABLE progress(id integer PRIMARY KEY)"); }
        catch(SQLException failure) { throw MigrationTest.safe(failure); }
    }
    static Object invoke(Method method,Object target,Object[] args) throws Throwable {
        try { return method.invoke(target,args); } catch(InvocationTargetException failure) { throw failure.getCause(); }
    }
    @AfterEach void cleanup() { fixture.cleanup(); }
    CommandKey key() { return new CommandKey(new Scope(UUID.randomUUID(),"SYNTHETIC",null),UUID.randomUUID().toString(),CommandKey.fingerprint(List.of("normalized"))); }
    Receipt ok() { return new Receipt(Code.OK,null,0); }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
    void function(String state) {
        try(Connection c=fixture.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(c,"CREATE OR REPLACE FUNCTION synthetic_failure() RETURNS integer LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic' USING ERRCODE='"+state+"'; END $$");
        } catch(SQLException failure) { throw MigrationTest.safe(failure); }
    }
    @Test void readCommittedUsesOneStableMicrosecondDatabaseTime() {
        unit.execute(key(),tx->{
            assertEquals("read committed",jdbc.queryForObject("SHOW transaction_isolation",String.class));
            Instant captured=tx.now(); jdbc.execute("SELECT pg_sleep(0.01)");
            assertSame(captured,tx.now()); assertEquals(0,captured.getNano()%1000);
            return tx.attempt(this::ok);
        });
        assertEquals(1,clocks.get()); assertEquals(1,count("idempotency_record"));
    }
    @Test void resourceLockPrecedesCapturedTimeAcrossIndependentConnections() throws Exception {
        AtomicReference<Instant> captured=new AtomicReference<>();
        try(Connection blocker=fixture.connection(MigrationTest.APP); var executor=Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false); MigrationTest.execute(blocker,"SELECT id FROM scheduling_resource WHERE id=1 FOR UPDATE");
            var result=executor.submit(()->unit.execute(key(),tx->{captured.set(tx.now());return ok();}));
            long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos(); boolean waiting=false;
            while(System.nanoTime()<deadline) {
                if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE application_name='p08_core' AND wait_event_type='Lock'",Integer.class)>0) {waiting=true;break;}
                Thread.sleep(10);
            }
            assertTrue(waiting,"Worker must actually wait on resource lock");
            Instant released;
            try(var statement=blocker.createStatement();var row=statement.executeQuery("SELECT clock_timestamp()")) {assertTrue(row.next());released=row.getTimestamp(1).toInstant();}
            blocker.commit(); assertTrue(result.get(10,TimeUnit.SECONDS).success());
            assertFalse(captured.get().isBefore(released));
        }
    }
    @Test void identicalConcurrentKeysRunOnceAndChangedFingerprintsNeverReplay() throws Exception {
        CommandKey key=key(); AtomicInteger calls=new AtomicInteger(); UUID entity=UUID.randomUUID();
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->unit.execute(key,tx->{calls.incrementAndGet();jdbc.update("INSERT INTO progress VALUES(1)");return new Receipt(Code.OK,entity,7);}));
            var other=new PostgresScheduling(source);
            var second=executor.submit(()->other.execute(key,tx->{calls.incrementAndGet();return new Receipt(Code.OK,entity,7);}));
            assertEquals(first.get(),second.get());
        }
        assertEquals(1,calls.get()); assertEquals(1,count("idempotency_record"));
        Receipt conflict=unit.execute(new CommandKey(key.scope(),key.key(),"b".repeat(64)),tx->{fail("Replay must not execute");return ok();});
        assertEquals(Code.IDEMPOTENCY_KEY_REUSED,conflict.code());
    }
    @Test void knownOverlapRollsBackAttemptButCommitsEarlierExpiryAndReceipt() {
        UUID due=fixture.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        UUID occupied=fixture.booking(MigrationTest.START.plusSeconds(86400),"CONFIRMED",MigrationTest.snapshot());
        UUID moving=fixture.booking(MigrationTest.START.plusSeconds(172800),"PENDING_CONFIRMATION",MigrationTest.snapshot());
        CommandKey key=key(); Receipt result=unit.execute(key,tx->{
            jdbc.update("UPDATE booking SET state='EXPIRED' WHERE id=?",due);
            jdbc.update("UPDATE booking_occupancy SET released_at=? WHERE booking_id=?",Timestamp.from(tx.now()),due);
            return tx.attempt(()->{
                jdbc.update("UPDATE booking_occupancy SET occupied=(SELECT occupied FROM booking_occupancy WHERE booking_id=?) WHERE booking_id=?",occupied,moving);
                return ok();
            });
        });
        assertEquals(Code.SLOT_UNAVAILABLE,result.code());
        assertEquals("EXPIRED",jdbc.queryForObject("SELECT state FROM booking WHERE id=?",String.class,due));
        assertEquals(1,count("idempotency_record"));
        assertEquals(result,unit.execute(key,tx->{fail("Failed receipt must replay");return ok();}));
    }
    @Test void returnedBusinessFailureRollsBackOnlyItsSavepoint() {
        Receipt receipt=unit.execute(key(),tx->{
            jdbc.update("INSERT INTO progress VALUES(1)");
            return tx.attempt(()->{jdbc.update("INSERT INTO progress VALUES(2)");return Receipt.failure(Code.SLOT_UNAVAILABLE);});
        });
        assertFalse(receipt.success()); assertEquals(1,count("progress"));
    }
    @Test void deferredInvariantFailureAndUnexpectedCallbackAbortEverything() {
        UUID id=fixture.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        Failure failure=assertThrows(Failure.class,()->unit.execute(key(),tx->{
            jdbc.update("INSERT INTO progress VALUES(1)");
            return tx.attempt(()->{jdbc.update("UPDATE booking SET state='EXPIRED' WHERE id=?",id);return ok();});
        }));
        assertEquals(Failure.Kind.STORAGE_FAILURE,failure.kind()); assertNull(failure.getCause());
        assertEquals(0,count("progress")); assertEquals(0,count("idempotency_record"));
        assertThrows(DomainFailure.class,()->unit.execute(key(),tx->tx.attempt(()->{jdbc.update("INSERT INTO progress VALUES(1)");throw new DomainFailure("SYNTHETIC_FAILURE");})));
        assertEquals(0,count("progress"));
    }
    @Test void deadlockRetriesAreBoundedAndEveryFailedTransactionRollsBack() {
        function("40P01"); AtomicInteger attempts=new AtomicInteger();
        unit.execute(key(),tx->{jdbc.update("INSERT INTO progress VALUES(1)");if(attempts.incrementAndGet()<3) jdbc.queryForObject("SELECT synthetic_failure()",Integer.class);return ok();});
        assertEquals(3,attempts.get()); assertEquals(1,count("progress"));
        jdbc.update("DELETE FROM progress"); attempts.set(0);
        Failure failure=assertThrows(Failure.class,()->unit.execute(key(),tx->{attempts.incrementAndGet();jdbc.update("INSERT INTO progress VALUES(1)");jdbc.queryForObject("SELECT synthetic_failure()",Integer.class);return ok();}));
        assertEquals(Failure.Kind.RETRY_LATER,failure.kind()); assertEquals(3,attempts.get()); assertEquals(0,count("progress"));
    }
    @Test void lockTimeoutAndStatementCancellationAreSafeRetryableFailures() throws Exception {
        try(Connection blocker=fixture.connection(MigrationTest.APP)) {
            blocker.setAutoCommit(false); MigrationTest.execute(blocker,"SELECT id FROM scheduling_resource WHERE id=1 FOR UPDATE");
            Failure failure=assertThrows(Failure.class,()->unit.execute(key(),tx->ok()));
            assertEquals(Failure.Kind.RETRY_LATER,failure.kind()); assertNull(failure.getCause());
        }
        function("57014");
        assertEquals(Failure.Kind.RETRY_LATER,assertThrows(Failure.class,()->unit.execute(key(),tx->{jdbc.queryForObject("SELECT synthetic_failure()",Integer.class);return ok();})).kind());
    }
    @Test void corruptStoredReceiptsAndMissingResourceCannotBeCertified() {
        CommandKey key=key(); unit.execute(key,tx->ok());
        for(String document:List.of("{}","{\"code\":\"OTHER\",\"entityId\":null,\"version\":0}","{\"code\":\"OK\",\"entityId\":\"invalid\",\"version\":1}","{\"code\":\"OK\",\"entityId\":null,\"version\":-1}")) {
            jdbc.update("UPDATE idempotency_record SET receipt=?::jsonb",document);
            assertEquals(Failure.Kind.STORAGE_FAILURE,assertThrows(Failure.class,()->unit.execute(key,tx->ok())).kind());
        }
        jdbc.update("UPDATE idempotency_record SET fingerprint=?","q".repeat(64));
        assertThrows(Failure.class,()->unit.execute(key,tx->ok()));
        jdbc.update("DELETE FROM scheduling_resource");
        assertEquals(Failure.Kind.STORAGE_FAILURE,assertThrows(Failure.class,()->unit.execute(key(),tx->ok())).kind());
    }
}
