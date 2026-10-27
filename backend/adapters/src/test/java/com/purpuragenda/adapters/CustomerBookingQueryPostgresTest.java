package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.payment.PaymentLedger;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(180)
@ResourceLock("postgres-migration-fixture")
class CustomerBookingQueryPostgresTest {
    final CustomerCreationPostgresTest c=new CustomerCreationPostgresTest();
    @TempDir Path directory;
    PostgresCustomerBookingQuery query;
    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void setup() throws Exception { c.directory=directory;c.setup();query=reader(c.f.source); }
    @AfterEach void cleanup() { c.cleanup(); }
    PostgresCustomerBookingQuery reader(DataSource source) { return new PostgresCustomerBookingQuery(source,Set.of("zoom.us")); }

    @Test void reschedulingContextUsesCapturedReadOnlySnapshotAndKeepsOwnOccupancyForApplicationExclusion() {
        var session=c.guest("advice@example.test");UUID id=c.create(session,"advice",c.request(3,9)).entityId();
        var booking=c.load(id);var range=new com.purpuragenda.domain.scheduling.ReservationInterval(booking.start().minusSeconds(86400),booking.start().plusSeconds(86400));
        String seen=c.f.value("SELECT last_seen_at FROM app_session WHERE id=?",session.reference().id());
        var count=new AtomicInteger();Instant now=c.jdbc.queryForObject("SELECT clock_timestamp()",(r,n)->r.getTimestamp(1).toInstant());
        var context=reader(c.clock(now,count)).find(c.binding(session),id,range).orElseThrow();
        assertEquals(1,count.get());assertEquals(now,context.now());assertEquals(booking,context.booking());assertEquals(1,context.active());
        assertTrue(context.occupied().contains(booking));assertEquals(1,context.policy().version());assertFalse(context.calendar().weekly().isEmpty());
        assertEquals(seen,c.f.value("SELECT last_seen_at FROM app_session WHERE id=?",session.reference().id()));
        var other=c.guest("other@example.test");assertTrue(query.find(c.binding(other),id,range).isEmpty());
        c.sessions.revoke(session.reference());assertTrue(query.find(c.binding(session),id,range).isEmpty());
    }
    @Test void exactGuestScopeAndAccountOwnershipNeverBecomeGuestHistory() {
        var a=c.guest("one@example.test");var b=c.guest("one@example.test");
        UUID mine=c.create(a,"a",c.request(3,9)).entityId(),other=c.create(b,"b",c.request(4,9)).entityId();
        assertEquals(c.load(mine),query.find(c.binding(a),mine).orElseThrow().booking());
        assertTrue(query.find(c.binding(a),other).isEmpty());assertTrue(query.find(c.binding(a),UUID.randomUUID()).isEmpty());
        var management=c.guest("one@example.test",other);
        assertTrue(query.find(c.binding(management),other).isPresent());assertTrue(query.find(c.binding(management),mine).isEmpty());
        var account=c.account();UUID owned=c.command.create(account.binding(),"account",c.request(5,9)).entityId();
        assertTrue(query.find(account.binding(),owned).isPresent());assertTrue(query.find(account.binding(),mine).isEmpty());
        assertTrue(query.find(c.account("rival@example.test").binding(),owned).isEmpty());
        try(var anonymous=c.sessions.anonymous()) { assertTrue(query.find(c.binding(anonymous.session()),mine).isEmpty()); }
        assertTrue(query.find(new SessionBinding(UUID.randomUUID(),a.reference().browserHash()),mine).isEmpty());
        assertTrue(query.find(new SessionBinding(a.reference().id(),"0".repeat(64)),mine).isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"session","parent","child"})
    void committedRevocationsImmediatelyRemoveAuthority(String target) {
        var s=c.guest("synthetic@example.test");UUID id=c.create(s,"a",c.request(3,9)).entityId();
        if(target.equals("session"))c.sessions.revoke(s.reference());
        else c.jdbc.update("UPDATE access_grant SET revoked_at=clock_timestamp() WHERE "+(target.equals("parent")?"id=?":"parent_grant_id=?"),s.grantId());
        assertTrue(query.find(c.binding(s),id).isEmpty());assertTrue(query.find(c.binding(s)).isEmpty());
    }
    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void idleAndGrantExpiryUseOneCapturedTimeWithStrictEquality(long micros) {
        var s=c.guest("synthetic@example.test");UUID id=c.create(s,"a",c.request(3,9)).entityId();
        Instant idle=c.jdbc.queryForObject("SELECT last_seen_at+interval '30 minutes' FROM app_session WHERE id=?",(r,n)->r.getTimestamp(1).toInstant(),s.reference().id());
        var reads=new AtomicInteger();assertEquals(micros<0,reader(c.clock(idle.plusNanos(micros*1000),reads)).find(c.binding(s),id).isPresent());assertEquals(1,reads.get());
        var scopedReads=new AtomicInteger();assertEquals(micros<0,reader(c.clock(idle.plusNanos(micros*1000),scopedReads)).find(c.binding(s)).isPresent());assertEquals(1,scopedReads.get());
        Instant expiry=c.jdbc.queryForObject("SELECT expires_at FROM access_grant WHERE parent_grant_id=?",(r,n)->r.getTimestamp(1).toInstant(),s.grantId());
        c.jdbc.update("UPDATE app_session SET last_seen_at=? WHERE id=?",Timestamp.from(expiry.minusSeconds(1)),s.reference().id());
        assertEquals(micros<0,reader(c.clock(expiry.plusNanos(micros*1000),new AtomicInteger())).find(c.binding(s),id).isPresent());
    }
    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void snapshotRejectsActivityFromAfterItsCapturedTime(long micros) {
        var session=c.guest("synthetic@example.test");UUID id=c.create(session,"clock",c.request(3,9)).entityId();
        Instant seen=c.jdbc.queryForObject("SELECT clock_timestamp()+interval '10 seconds'",(r,n)->r.getTimestamp(1).toInstant());
        c.jdbc.update("UPDATE app_session SET last_seen_at=? WHERE id=?",Timestamp.from(seen),session.reference().id());
        assertEquals(micros>=0,reader(c.clock(seen.plusNanos(micros*1000),new AtomicInteger())).find(c.binding(session),id).isPresent());
    }
    @Test void futureAuthorityAndUnconsumedCreationCannotReadBookings() {
        var s=c.guest("synthetic@example.test");UUID id=c.create(s,"a",c.request(3,9)).entityId();
        var unused=c.guest("synthetic@example.test");assertTrue(query.find(c.binding(unused),id).isEmpty());
        Instant issued=c.jdbc.queryForObject("SELECT issued_at FROM app_session WHERE id=?",(r,n)->r.getTimestamp(1).toInstant(),s.reference().id());
        assertTrue(reader(c.clock(issued.minusNanos(1000),new AtomicInteger())).find(c.binding(s),id).isEmpty());
        Instant childIssued=c.jdbc.queryForObject("SELECT issued_at FROM access_grant WHERE parent_grant_id=?",(r,n)->r.getTimestamp(1).toInstant(),s.grantId());
        assertTrue(reader(c.clock(childIssued.minusNanos(1000),new AtomicInteger())).find(c.binding(s),id).isEmpty());
    }
    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void accountAbsoluteExpiryCannotBeExtendedByActivity(long micros) {
        var a=c.account();UUID id=c.command.create(a.binding(),"a",c.request(3,9)).entityId();
        Instant expiry=c.jdbc.queryForObject("SELECT absolute_expires_at FROM app_session WHERE id=?",(r,n)->r.getTimestamp(1).toInstant(),a.binding().sessionId());
        c.jdbc.update("UPDATE app_session SET last_seen_at=? WHERE id=?",Timestamp.from(expiry.minusSeconds(1)),a.binding().sessionId());
        assertEquals(micros<0,reader(c.clock(expiry.plusNanos(micros*1000),new AtomicInteger())).find(a.binding(),id).isPresent());
    }
    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void directManagementExpiryIsStrict(long micros) {
        var s=c.guest("synthetic@example.test");UUID id=c.create(s,"a",c.request(3,9)).entityId();
        var manager=c.guest("synthetic@example.test",id);
        Instant expiry=c.jdbc.queryForObject("SELECT expires_at FROM access_grant WHERE id=?",(r,n)->r.getTimestamp(1).toInstant(),manager.grantId());
        c.jdbc.update("UPDATE app_session SET last_seen_at=? WHERE id=?",Timestamp.from(expiry.minusSeconds(1)),manager.reference().id());
        assertEquals(micros<0,reader(c.clock(expiry.plusNanos(micros*1000),new AtomicInteger())).find(c.binding(manager),id).isPresent());
        assertEquals(micros<0,reader(c.clock(expiry.plusNanos(micros*1000),new AtomicInteger())).find(c.binding(manager)).isPresent());
    }
    @Test void derivedManagementSessionStillRequiresItsParent() {
        var s=c.guest("synthetic@example.test");UUID id=c.create(s,"a",c.request(3,9)).entityId();
        c.jdbc.update("UPDATE app_session SET grant_id=(SELECT id FROM access_grant WHERE parent_grant_id=?) WHERE id=?",s.grantId(),s.reference().id());
        assertTrue(query.find(c.binding(s),id).isPresent());
        c.jdbc.update("UPDATE access_grant SET revoked_at=clock_timestamp() WHERE id=?",s.grantId());
        assertTrue(query.find(c.binding(s),id).isEmpty());
    }
    @Test void readsDoNotTouchSessionsReclaimExpiryOrWriteEffects() {
        var s=c.guest("synthetic@example.test");UUID id=c.create(s,"a",c.request(3,9)).entityId();
        UUID due=c.f.database.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        String seen=c.f.value("SELECT last_seen_at FROM app_session WHERE id=?",s.reference().id());
        int events=c.count("outbox_event"),audit=c.count("audit_event"),receipts=c.count("idempotency_record");
        assertTrue(query.find(c.binding(s),id).isPresent());assertTrue(query.find(c.binding(s)).isPresent());
        assertTrue(query.find(c.binding(s),id,c.load(id).interval()).isPresent());
        assertEquals(seen,c.f.value("SELECT last_seen_at FROM app_session WHERE id=?",s.reference().id()));
        assertEquals(Booking.State.PENDING_CONFIRMATION,c.load(due).state());
        assertEquals(events,c.count("outbox_event"));assertEquals(audit,c.count("audit_event"));assertEquals(receipts,c.count("idempotency_record"));
    }
    @Test void readsStayAvailableWhileSchedulingAndAuthorityRowsAreLocked() throws Exception {
        var s=c.guest("synthetic@example.test");UUID id=c.create(s,"a",c.request(3,9)).entityId();
        try(var lock=c.f.source.getConnection();var statement=lock.createStatement();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            lock.setAutoCommit(false);
            for(String table:List.of("scheduling_resource","app_session","access_grant","booking"))statement.executeQuery("SELECT * FROM "+table+" FOR UPDATE").close();
            assertTrue(executor.submit(()->query.find(c.binding(s),id).isPresent()).get(3,TimeUnit.SECONDS));
            assertTrue(executor.submit(()->query.find(c.binding(s)).isPresent()).get(3,TimeUnit.SECONDS));
            assertTrue(executor.submit(()->query.find(c.binding(s),id,c.load(id).interval()).isPresent()).get(3,TimeUnit.SECONDS));lock.rollback();
        }
    }
    @Test void moneyAndBookingUseOneReadOnlySnapshotAcrossAConcurrentConfirmation() {
        var s=c.guest("synthetic@example.test");UUID id=c.create(s,"a",c.request(3,9)).entityId();var changed=new AtomicBoolean();
        DataSource concurrent=new DelegatingDataSource(c.f.source) {
            @Override public Connection getConnection() throws SQLException {
                var connection=super.getConnection();
                return GuestAccessDatabaseTest.proxy(Connection.class,(p,m,a)->{
                    var result=GuestAccessDatabaseTest.invoke(connection,m,a);
                    if(!m.getName().equals("prepareStatement") || !a[0].toString().startsWith("SELECT b.*"))return result;
                    assertTrue(connection.isReadOnly());assertEquals(Connection.TRANSACTION_REPEATABLE_READ,connection.getTransactionIsolation());
                    return GuestAccessDatabaseTest.proxy(PreparedStatement.class,(sp,sm,sa)->{
                        var rows=GuestAccessDatabaseTest.invoke(result,sm,sa);
                        if(sm.getName().equals("executeQuery") && changed.compareAndSet(false,true))c.scheduling.execute(c.internal(),tx->tx.attempt(()->{
                            Booking b=tx.booking(id).orElseThrow().approve(tx.now(),Meeting.parse("https://zoom.us/j/123456789",Set.of("zoom.us")),Booking.ConfirmationBasis.MANUAL_APPROVAL);
                            tx.save(b);c.jdbc.update("INSERT INTO payment_entry(id,booking_id,kind,amount_minor,currency,method,reference,verified_at) VALUES(?,?,'RECEIPT',1500,'EUR','SYNTHETIC','synthetic-paid',clock_timestamp())",UUID.randomUUID(),id);
                            return new Receipt(Code.OK,id,b.version());
                        }));
                        return rows;
                    });
                });
            }
        };
        var before=reader(concurrent).find(c.binding(s),id).orElseThrow();assertTrue(changed.get());
        assertEquals(Booking.State.PENDING_CONFIRMATION,before.booking().state());assertTrue(before.ledger().entries().isEmpty());
        var after=query.find(c.binding(s),id).orElseThrow();assertEquals(Booking.State.CONFIRMED,after.booking().state());
        assertEquals(PaymentLedger.Status.PAID_EXTERNAL,after.ledger().summary(after.booking().snapshot().service().price()).status());
        UUID payment=after.ledger().entries().getFirst().id();
        c.jdbc.update("INSERT INTO refund_obligation(receipt_id,due_minor,reason) VALUES(?,1500,'SYNTHETIC')",payment);
        assertEquals(PaymentLedger.Status.REFUND_DUE,query.find(c.binding(s),id).orElseThrow().ledger().summary(after.booking().snapshot().service().price()).status());
    }
    @Test void storageFailuresAreSanitizedAndConstructorRejectsMissingHostConfiguration() {
        var unavailable=new DelegatingDataSource(c.f.source) { @Override public Connection getConnection() throws SQLException { throw new SQLException("synthetic-provider-detail"); } };
        var binding=new SessionBinding(UUID.randomUUID(),"b".repeat(64));
        assertEquals(Failure.Kind.STORAGE_FAILURE,c.failure(()->reader(unavailable).find(binding,UUID.randomUUID())).kind());
        assertEquals(Failure.Kind.STORAGE_FAILURE,c.failure(()->reader(unavailable).find(binding)).kind());
        assertThrows(NullPointerException.class,()->query.find(null));
        assertThrows(NullPointerException.class,()->reader(c.f.source).find(null,UUID.randomUUID()));
        assertThrows(NullPointerException.class,()->query.find(binding,null));
        assertThrows(NullPointerException.class,()->reader(null));
        assertThrows(IllegalArgumentException.class,()->new PostgresCustomerBookingQuery(c.f.source,null));
        assertThrows(IllegalArgumentException.class,()->new PostgresCustomerBookingQuery(c.f.source,Set.of()));
    }
    @Test void scopedLookupReturnsOnlyTheBoundBookingForConsumedCreationAndManagement() {
        var first=c.guest("scoped@example.test");var second=c.guest("scoped@example.test");
        UUID a=c.create(first,"first",c.request(3,9)).entityId(),b=c.create(second,"second",c.request(4,9)).entityId();
        assertEquals(a,query.find(c.binding(first)).orElseThrow().booking().id());
        assertEquals(b,query.find(c.binding(second)).orElseThrow().booking().id());
        var manager=c.guest("scoped@example.test",a);
        assertEquals(a,query.find(c.binding(manager)).orElseThrow().booking().id());
        c.jdbc.update("UPDATE app_session SET grant_id=(SELECT id FROM access_grant WHERE parent_grant_id=?) WHERE id=?",first.grantId(),first.reference().id());
        assertEquals(a,query.find(c.binding(first)).orElseThrow().booking().id());
        c.jdbc.update("UPDATE access_grant SET revoked_at=clock_timestamp() WHERE id=?",first.grantId());
        assertTrue(query.find(c.binding(first)).isEmpty());
        assertEquals(a,query.find(c.binding(manager)).orElseThrow().booking().id());
    }
    @Test void scopedLookupDoesNotChooseFromAccountHistoryOrUnverifiedSessions() {
        var account=c.account();UUID id=c.command.create(account.binding(),"account",c.request(3,9)).entityId();
        assertTrue(query.find(account.binding(),id).isPresent());assertTrue(query.find(account.binding()).isEmpty());
        var unused=c.guest("unused@example.test");assertTrue(query.find(c.binding(unused)).isEmpty());
        try(var anonymous=c.sessions.anonymous()) { assertTrue(query.find(c.binding(anonymous.session())).isEmpty()); }
        assertTrue(query.find(new SessionBinding(UUID.randomUUID(),"b".repeat(64))).isEmpty());
        assertTrue(query.find(new SessionBinding(unused.reference().id(),"0".repeat(64))).isEmpty());
    }

}
