package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.email.*;
import com.purpuragenda.adapters.out.security.*;
import com.purpuragenda.application.port.out.AuthDeliveryStore.*;
import com.purpuragenda.application.port.out.GuestAccessStore.*;
import java.lang.reflect.*;
import java.net.URI;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.postgresql.ds.PGSimpleDataSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(180)
@ResourceLock("postgres-migration-fixture")
class GuestAccessDatabaseTest {
    final MigrationTest database=new MigrationTest();
    @TempDir Path directory;
    PGSimpleDataSource source; PostgresGuestAccess access; PostgresAuthDeliveryStore delivery; PostgresNotificationQueue queue;
    static final String BROWSER=Secrets.hash("synthetic-requesting-browser");
    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void setup() throws Exception {
        database.fresh();
        source=new PGSimpleDataSource(); source.setURL(MigrationTest.jdbcUrl); source.setUser(MigrationTest.APP);
        source.setPassword(MigrationTest.appPassword); source.setCurrentSchema(database.schema+",public");
        access=new PostgresGuestAccess(source); queue=new PostgresNotificationQueue(source,false);
        byte[] bytes=new byte[32]; Arrays.fill(bytes,(byte)1); Path file=directory.resolve("delivery"); Files.write(file,bytes); Arrays.fill(bytes,(byte)0);
        delivery=new PostgresAuthDeliveryStore(source,new Secrets(file,"delivery"),URI.create("https://example.test"),false);
    }
    @AfterEach void cleanup() { database.cleanup(); }
    record Proof(UUID id,String token,Instant expiresAt) { @Override public String toString() { return "Proof[REDACTED]"; } }
    Proof issue(Purpose purpose,String email,UUID booking) {
        var receipt=delivery.issue(new Issue(email,purpose,booking,purpose==Purpose.CREATE || purpose==Purpose.ACCOUNT?BROWSER:null));
        var lease=queue.claimAuth().orElseThrow(); var message=delivery.prepare(lease).orElseThrow();
        String token=message.text().split("#token=",2)[1].split("&",2)[0]; assertTrue(queue.sent(lease));
        return new Proof(receipt.challengeId(),token,receipt.expiresAt());
    }
    Proof creation() { return issue(Purpose.CREATE,"synthetic@example.test",null); }
    Optional<Grant> redeem(Proof proof,Kind kind) { return access.redeem(new Redemption(proof.token(),kind,BROWSER)); }
    void sql(String sql,Object... values) { try(var c=database.connection(MigrationTest.APP)) { MigrationTest.execute(c,sql,values); } catch(SQLException e) { throw MigrationTest.safe(e); } }
    String value(String sql,Object... values) {
        try(var c=database.connection(MigrationTest.APP);var s=c.prepareStatement(sql)) {
            for(int i=0;i<values.length;i++) s.setObject(i+1,values[i]);
            try(var r=s.executeQuery()) { assertTrue(r.next()); return r.getString(1); }
        } catch(SQLException e) { throw MigrationTest.safe(e); }
    }
    long count(String table) { return database.scalar(MigrationTest.APP,"SELECT count(*) FROM "+table); }
    void safe(org.junit.jupiter.api.function.Executable action) {
        var failure=assertThrows(IllegalStateException.class,action); assertEquals("GUEST_ACCESS_UNAVAILABLE",failure.getMessage());
        assertNull(failure.getCause()); assertEquals(0,failure.getSuppressed().length);
    }

    @Test void creationAtomicallyConsumesProofAndPersistsBoundThirtyMinuteScopeWithoutAccount() {
        var proof=creation(); var grant=redeem(proof,Kind.CREATE).orElseThrow();
        assertEquals(Kind.CREATE,grant.kind()); assertNull(grant.bookingId()); assertEquals(grant.issuedAt().plusSeconds(1800),grant.expiresAt());
        assertTrue(value("SELECT customer_id::text FROM access_grant WHERE id=?",grant.id()).equals(grant.customerId().toString()));
        assertTrue(value("SELECT browser_hash FROM access_grant WHERE id=?",grant.id()).equals(BROWSER));
        assertEquals("t",value("SELECT g.issued_at=c.consumed_at AND g.challenge_id=c.id FROM access_grant g JOIN email_challenge c ON c.id=g.challenge_id WHERE g.id=?",grant.id()));
        assertEquals(1,count("customer_identity")); assertEquals(0,count("customer_account")); assertEquals(0,count("customer_profile"));
        assertTrue(redeem(proof,Kind.CREATE).isEmpty()); assertEquals(1,count("access_grant"));
    }
    @Test void wrongBrowserPurposeAndUnknownProofCannotConsumeOrCreateAuthority() {
        var proof=creation(); assertTrue(access.redeem(new Redemption(proof.token(),Kind.CREATE,"b".repeat(64))).isEmpty());
        assertTrue(redeem(proof,Kind.MANAGE).isEmpty()); assertTrue(access.redeem(new Redemption(Secrets.token(),Kind.CREATE,BROWSER)).isEmpty());
        assertTrue(access.redeem(new Redemption(Secrets.token(),Kind.MANAGE,BROWSER)).isEmpty());
        var account=issue(Purpose.ACCOUNT,"synthetic@example.test",null); assertTrue(redeem(account,Kind.CREATE).isEmpty());
        assertEquals(0,count("access_grant")); assertEquals(0,count("customer_identity")); assertNull(value("SELECT consumed_at FROM email_challenge WHERE id=?",proof.id()));
        assertTrue(redeem(proof,Kind.CREATE).isPresent());
    }
    @Test void expiredAndConsumedChallengeNeverCreateGrants() {
        for(String change:List.of("expires_at=clock_timestamp()","consumed_at=clock_timestamp()","expires_at=to_timestamp(0)")) {
            var proof=creation(); sql("UPDATE email_challenge SET "+change+" WHERE id=?",proof.id()); assertTrue(redeem(proof,Kind.CREATE).isEmpty());
        }
        assertEquals(0,count("customer_identity")); assertEquals(0,count("access_grant"));
    }
    @Test void capturedExpiryMinusOneMicrosecondSucceedsButEqualityAndAfterFail() {
        for(long micros:new long[]{-1,0,1}) {
            var proof=creation(); Instant captured=proof.expiresAt().plusNanos(micros*1000); var reads=new AtomicInteger();
            var fixed=new PGSimpleDataSource() {
                @Override public Connection getConnection() throws SQLException {
                    var connection=source.getConnection();
                    return proxy(Connection.class,(p,m,a)->{
                        Object result=invoke(connection,m,a);
                        if(!m.getName().equals("createStatement"))return result;
                        var statement=(Statement)result;
                        return proxy(Statement.class,(sp,sm,sa)->{
                            Object rows=invoke(statement,sm,sa);
                            if(!sm.getName().equals("executeQuery") || !"SELECT clock_timestamp()".equals(sa[0]))return rows;
                            reads.incrementAndGet();
                            return proxy(ResultSet.class,(rp,rm,ra)->rm.getName().equals("getTimestamp") && Integer.valueOf(1).equals(ra[0])
                                    ?Timestamp.from(captured):invoke(rows,rm,ra));
                        });
                    });
                }
            };
            var result=new PostgresGuestAccess(fixed).redeem(new Redemption(proof.token(),Kind.CREATE,BROWSER));
            assertEquals(1,reads.get()); assertEquals(micros<0,result.isPresent());
            result.ifPresent(grant->assertEquals(captured,grant.issuedAt()));
        }
        assertEquals(1,count("access_grant"));
    }
    static <T>T proxy(Class<T> type,InvocationHandler handler) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler)); }
    static Object invoke(Object target,Method method,Object[] arguments) throws Throwable {
        try { return method.invoke(target,arguments); } catch(InvocationTargetException failed) { throw failed.getCause(); }
    }
    @Test void normalizedProofReusesStableIdentityWithoutRewritingVerificationOrMergingPlusAddresses() {
        var first=redeem(issue(Purpose.CREATE,"SYNTHETIC@EXAMPLE.TEST",null),Kind.CREATE).orElseThrow();
        String verified=value("SELECT verified_at FROM customer_identity WHERE id=?",first.customerId());
        var second=redeem(creation(),Kind.CREATE).orElseThrow(); assertTrue(first.customerId().equals(second.customerId()));
        assertEquals(verified,value("SELECT verified_at FROM customer_identity WHERE id=?",first.customerId()));
        var separate=redeem(issue(Purpose.CREATE,"synthetic+other@example.test",null),Kind.CREATE).orElseThrow();
        assertFalse(first.customerId().equals(separate.customerId())); assertEquals(2,count("customer_identity")); assertEquals(3,count("access_grant"));
    }
    @Test void managementUsesExistingBookingOwnerWithoutTransferringOwnershipToDeliveryMailbox() {
        UUID booking=database.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        String owner=value("SELECT customer_id FROM booking WHERE id=?",booking);
        var grant=redeem(issue(Purpose.MANAGE,"synthetic@example.test",booking),Kind.MANAGE).orElseThrow();
        assertEquals(Kind.MANAGE,grant.kind()); assertTrue(booking.equals(grant.bookingId())); assertTrue(owner.equals(grant.customerId().toString()));
        assertEquals(grant.issuedAt().plusSeconds(7200),grant.expiresAt()); assertEquals(1,count("customer_identity"));
        assertEquals(0,count("customer_identity WHERE email_key='synthetic@example.test'")); assertEquals(0,count("customer_account"));
    }
    @Test void changedContactMalformedScopesAndContactPurposeRemainUnconsumed() {
        UUID booking=database.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        var proof=issue(Purpose.MANAGE,"synthetic@example.test",booking); sql("UPDATE booking SET delivery_email='changed@example.test' WHERE id=?",booking);
        assertTrue(redeem(proof,Kind.MANAGE).isEmpty()); assertNull(value("SELECT consumed_at FROM email_challenge WHERE id=?",proof.id()));
        var contact=issue(Purpose.CONTACT,"changed@example.test",booking); assertTrue(redeem(contact,Kind.MANAGE).isEmpty());
        var malformed=issue(Purpose.MANAGE,"changed@example.test",booking); sql("UPDATE email_challenge SET browser_hash=? WHERE id=?",BROWSER,malformed.id());
        assertTrue(redeem(malformed,Kind.MANAGE).isEmpty());
        var create=creation(); sql("UPDATE email_challenge SET booking_id=? WHERE id=?",booking,create.id());
        assertTrue(redeem(create,Kind.CREATE).isEmpty()); assertEquals(0,count("access_grant"));
    }
    @Test void twentyConcurrentSameProofRedemptionsProduceExactlyOneGrant() throws Exception {
        var proof=creation(); assertEquals(1,race(Collections.nCopies(20,proof))); assertEquals(1,count("access_grant")); assertEquals(1,count("customer_identity"));
    }
    @Test void twentyDistinctProofsForOneMailboxStillCreateOneStableIdentity() throws Exception {
        var proofs=new ArrayList<Proof>(); for(int i=0;i<20;i++) proofs.add(creation());
        assertEquals(20,race(proofs)); assertEquals(20,count("access_grant")); assertEquals(1,count("customer_identity"));
    }
    int race(List<Proof> proofs) throws Exception {
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var ready=new CountDownLatch(proofs.size()); var start=new CountDownLatch(1); var results=new ArrayList<Future<Boolean>>();
            for(var proof:proofs) results.add(pool.submit(()->{ ready.countDown(); start.await(); return redeem(proof,Kind.CREATE).isPresent(); }));
            assertTrue(ready.await(5,TimeUnit.SECONDS)); start.countDown(); int winners=0;
            for(var result:results) if(result.get(20,TimeUnit.SECONDS)) winners++; return winners;
        }
    }
    @Test void failedGrantInsertRollsBackChallengeConsumptionAndNewIdentity() {
        var proof=creation();
        try(var c=database.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(c,"CREATE FUNCTION reject_guest() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'SYNTHETIC_FAILURE'; END $$");
            MigrationTest.execute(c,"CREATE TRIGGER reject_guest BEFORE INSERT ON access_grant FOR EACH ROW EXECUTE FUNCTION reject_guest()");
        } catch(SQLException e) { throw MigrationTest.safe(e); }
        safe(()->redeem(proof,Kind.CREATE)); assertEquals(0,count("customer_identity")); assertEquals(0,count("access_grant"));
        assertNull(value("SELECT consumed_at FROM email_challenge WHERE id=?",proof.id()));
    }
    @Test void timeIsCapturedAfterChallengeLockWait() throws Exception {
        var proof=creation(); String name="guest-proof-"+UUID.randomUUID(); source.setApplicationName(name);
        try(var blocker=database.connection(MigrationTest.APP);var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false); MigrationTest.execute(blocker,"SELECT id FROM email_challenge WHERE id=? FOR UPDATE",proof.id());
            var result=pool.submit(()->redeem(proof,Kind.CREATE)); boolean blocked=false; long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<deadline) {
                if(value("SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock'",name).equals("1")) { blocked=true; break; }
                Thread.sleep(10);
            }
            assertTrue(blocked); MigrationTest.execute(blocker,"UPDATE email_challenge SET expires_at=clock_timestamp()+interval '400 milliseconds' WHERE id=?",proof.id());
            Thread.sleep(800); blocker.commit(); assertTrue(result.get(5,TimeUnit.SECONDS).isEmpty()); assertEquals(0,count("access_grant"));
        }
    }
    @Test void creationCanBindOnlyItsOwnersBookingAndDeriveOneImmutableManagementScope() {
        UUID booking=database.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        String email=value("SELECT c.email_key FROM booking b JOIN customer_identity c ON c.id=b.customer_id WHERE b.id=?",booking);
        var parent=redeem(issue(Purpose.CREATE,email,null),Kind.CREATE).orElseThrow();
        for(String change:List.of("customer_id='"+UUID.randomUUID()+"'","kind='MANAGE'","browser_hash='"+"b".repeat(64)+"'","expires_at=expires_at+interval '1 minute'"))
            database.denied("23514","UPDATE access_grant SET "+change+" WHERE id=?",parent.id());
        var wrong=redeem(creation(),Kind.CREATE).orElseThrow(); database.denied("23514","UPDATE access_grant SET booking_id=?,consumed_at=clock_timestamp() WHERE id=?",booking,wrong.id());
        sql("UPDATE access_grant SET booking_id=?,consumed_at=clock_timestamp() WHERE id=?",booking,parent.id());
        UUID derived=UUID.randomUUID();
        sql("INSERT INTO access_grant(id,customer_id,kind,booking_id,issued_at,expires_at,browser_hash,parent_grant_id) SELECT ?,customer_id,'MANAGE',booking_id,consumed_at,issued_at+interval '2 hours',browser_hash,id FROM access_grant WHERE id=?",derived,parent.id());
        assertEquals(1,count("access_grant WHERE parent_grant_id IS NOT NULL"));
        database.denied("23505","INSERT INTO access_grant(id,customer_id,kind,booking_id,issued_at,expires_at,browser_hash,parent_grant_id) SELECT ?,customer_id,'MANAGE',booking_id,consumed_at,issued_at+interval '2 hours',browser_hash,id FROM access_grant WHERE id=?",UUID.randomUUID(),parent.id());
        database.denied("23514","UPDATE access_grant SET consumed_at=NULL,booking_id=NULL WHERE id=?",parent.id());
        sql("UPDATE access_grant SET revoked_at=clock_timestamp() WHERE id=?",derived); database.denied("23514","UPDATE access_grant SET revoked_at=NULL WHERE id=?",derived);
    }
    @Test void unavailableStorageExposesNoProviderErrorOrPrivateInput() {
        var unavailable=new PGSimpleDataSource(); unavailable.setURL("jdbc:postgresql://127.0.0.1:1/unavailable?connectTimeout=1");
        safe(()->new PostgresGuestAccess(unavailable).redeem(new Redemption(Secrets.token(),Kind.CREATE,BROWSER)));
        assertEquals("GuestAccessStore[REDACTED]",access.toString());
    }
    @Test void upgradePreservesButRevokesLegacyUnboundAuthority() throws Exception {
        database.newSchema(); database.migrate(10); UUID owner=UUID.randomUUID(),legacy=UUID.randomUUID();
        sql("INSERT INTO customer_identity VALUES(?,'legacy@example.test',clock_timestamp())",owner);
        sql("INSERT INTO access_grant(id,customer_id,kind,expires_at) VALUES(?,?,'CREATE',clock_timestamp()+interval '30 minutes')",legacy,owner);
        database.migrate(0); database.migrate(0);
        assertEquals(11,database.scalar(MigrationTest.MIGRATOR,"SELECT count(*) FROM databasechangelog"));
        assertEquals(1,count("access_grant WHERE revoked_at IS NOT NULL AND issued_at IS NULL AND browser_hash IS NULL"));
        assertEquals(1,count("customer_identity")); database.denied("23514","UPDATE access_grant SET revoked_at=NULL WHERE id=?",legacy);
    }
}
