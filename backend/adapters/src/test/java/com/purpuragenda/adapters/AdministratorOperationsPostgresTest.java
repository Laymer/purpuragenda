package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.application.port.out.AdministratorOperationsQuery.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(240)
@ResourceLock("postgres-migration-fixture")
class AdministratorOperationsPostgresTest {
    final AdminBookingPostgresTest b=new AdminBookingPostgresTest();
    @TempDir Path directory;
    CustomerCreationPostgresTest c;PostgresAdministratorOperations query;UUID booking;final Request page=new Request(null,100);
    @BeforeAll static void start(){MigrationTest.start();}
    @AfterAll static void stop(){MigrationTest.stop();}
    @BeforeEach void setup() throws Exception {b.directory=directory;b.setup();c=b.c;booking=b.create("create",b.request(3,10)).entityId();query=new PostgresAdministratorOperations(c.f.source);}
    @AfterEach void cleanup(){b.cleanup();}
    UUID payment(UUID target,String currency,long amount,UUID receipt) {
        UUID id=UUID.randomUUID();c.jdbc.update("INSERT INTO payment_entry(id,booking_id,kind,amount_minor,currency,method,reference,verified_at,receipt_id) VALUES(?,?,?,?,?,'SYNTHETIC',?,clock_timestamp(),?)",id,target,receipt==null?"RECEIPT":"REFUND",amount,currency,id.toString(),receipt);return id;
    }
    UUID due(UUID target,String currency,long amount) {UUID id=payment(target,currency,amount,null);c.jdbc.update("INSERT INTO refund_obligation VALUES(?,?,'LATE_RECEIPT')",id,amount);return id;}
    UUID job(UUID target,String template,NotificationState state,String safe) {
        UUID id=UUID.randomUUID();c.jdbc.update("INSERT INTO notification_job(id,booking_id,audience,template,version,due_at,state,attempts,safe_error) VALUES(?,?,'CUSTOMER',?,1,clock_timestamp(),?,7,?)",id,target,template,state.name(),safe);return id;
    }
    @Test void reconcilesWholeObligationsAgainstLinkedImmutableRefundsWithoutChangingMoneyOrBooking() {
        UUID partial=due(booking,"EUR",1000),full=due(booking,"EUR",2000),foreign=due(booking,"USD",3000);payment(booking,"EUR",700,null);payment(booking,"EUR",400,partial);payment(booking,"EUR",2000,full);payment(booking,"USD",500,foreign);
        long version=c.jdbc.queryForObject("SELECT version FROM booking WHERE id=?",Long.class,booking);long facts=c.f.count("payment_entry");var result=query.reconciliation(b.session,booking,page);assertEquals(2,result.entries().size());
        var euro=result.entries().stream().filter(r->r.receiptId().equals(partial)).findFirst().orElseThrow();assertEquals(1000,euro.receivedMinor());assertEquals(1000,euro.dueMinor());assertEquals(400,euro.refundedMinor());assertEquals(600,euro.outstandingMinor());assertEquals("LATE_RECEIPT",euro.reason());assertEquals("PENDING_CONFIRMATION",euro.state());assertNotNull(euro.verifiedAt());
        var usd=result.entries().stream().filter(r->r.receiptId().equals(foreign)).findFirst().orElseThrow();assertEquals("USD",usd.currency());assertEquals(2500,usd.outstandingMinor());assertEquals(facts,c.f.count("payment_entry"));assertEquals(version,c.jdbc.queryForObject("SELECT version FROM booking WHERE id=?",Long.class,booking));
        payment(booking,"EUR",600,partial);assertEquals(List.of(foreign),query.reconciliation(b.session,booking,page).entries().stream().map(Refund::receiptId).toList());
    }
    @Test void reconciliationPagesAreBoundedByStableReceiptIdsAndBookingFilters() {
        var expected=new ArrayList<UUID>();for(int i=0;i<5;i++)expected.add(due(booking,"EUR",100));expected.sort(Comparator.comparing(UUID::toString));
        var first=query.reconciliation(b.session,null,new Request(null,2));var second=query.reconciliation(b.session,null,new Request(first.next(),2));var last=query.reconciliation(b.session,null,new Request(second.next(),2));
        assertEquals(expected.subList(0,2),first.entries().stream().map(Refund::receiptId).toList());assertEquals(expected.subList(2,4),second.entries().stream().map(Refund::receiptId).toList());assertEquals(expected.subList(4,5),last.entries().stream().map(Refund::receiptId).toList());assertNull(last.next());assertTrue(query.reconciliation(b.session,null,new Request(expected.getLast(),1)).entries().isEmpty());
        UUID other=b.create("other",b.request(4,10)).entityId();due(other,"EUR",300);assertEquals(5,query.reconciliation(b.session,booking,page).entries().size());assertEquals(6,query.reconciliation(b.session,null,page).entries().size());
    }
    @Test void notificationFollowUpFiltersStatesAndNeverExposesAuthJobsOrArbitraryProviderErrors() {
        UUID failed=job(booking,"BOOKING_CONFIRMED",NotificationState.FAILED,"SMTP_DELIVERY_FAILED");job(booking,"BOOKING_CREATED",NotificationState.SENT,null);UUID unsafe=job(booking,"BOOKING_CONFIRMED",NotificationState.SUPPRESSED,"SYNTHETIC_PRIVATE_PROVIDER");job(booking,"AUTH",NotificationState.FAILED,"SMTP_DELIVERY_FAILED");job(null,"BOOKING_CONFIRMED",NotificationState.FAILED,null);
        for(var state:List.of(NotificationState.QUEUED,NotificationState.LEASED))job(booking,"BOOKING_CREATED",state,"SUPERSEDED");
        var all=query.notifications(b.session,booking,null,page);assertEquals(5,all.entries().size());assertEquals("DELIVERY_STATUS_UNAVAILABLE",all.entries().stream().filter(j->j.id().equals(unsafe)).findFirst().orElseThrow().safeCode());assertFalse(all.toString().contains("PRIVATE_PROVIDER"));
        var f=query.notifications(b.session,null,NotificationState.FAILED,page).entries();assertEquals(1,f.size());assertEquals(failed,f.getFirst().id());assertEquals(booking,f.getFirst().bookingId());assertEquals("CUSTOMER",f.getFirst().audience());assertEquals(7,f.getFirst().attempts());assertEquals(1,f.getFirst().version());assertEquals("SMTP_DELIVERY_FAILED",f.getFirst().safeCode());assertNotNull(f.getFirst().dueAt());
        for(var state:NotificationState.values())assertEquals(1,query.notifications(b.session,booking,state,page).entries().size());assertNull(query.notifications(b.session,booking,NotificationState.SENT,page).entries().getFirst().safeCode());
    }
    @Test void notificationPagesRemainStableAndBookingScopeDoesNotLeakOtherTargets() {
        var expected=new ArrayList<UUID>();for(int i=0;i<5;i++)expected.add(job(booking,"BOOKING_CONFIRMED",NotificationState.FAILED,null));expected.sort(Comparator.comparing(UUID::toString));
        var first=query.notifications(b.session,null,null,new Request(null,2));var second=query.notifications(b.session,null,null,new Request(first.next(),2));var last=query.notifications(b.session,null,null,new Request(second.next(),2));
        assertEquals(expected.subList(0,2),first.entries().stream().map(Notification::id).toList());assertEquals(expected.subList(2,4),second.entries().stream().map(Notification::id).toList());assertEquals(expected.subList(4,5),last.entries().stream().map(Notification::id).toList());assertNull(last.next());assertTrue(query.notifications(b.session,null,null,new Request(expected.getLast(),1)).entries().isEmpty());
        UUID other=b.create("other",b.request(4,10)).entityId();job(other,"BOOKING_CREATED",NotificationState.FAILED,null);assertEquals(5,query.notifications(b.session,booking,null,page).entries().size());assertEquals(6,query.notifications(b.session,null,null,page).entries().size());
    }
    @ParameterizedTest @ValueSource(strings={"disabled","unenrolled","epoch","revoked","browser","missing"})
    void persistedAuthorityPrecedesEveryProjectionAndUnknownBookingLookup(String change) {
        SessionBinding binding=b.session;switch(change){case "disabled"->c.jdbc.update("UPDATE admin_user SET enabled=false");case "unenrolled"->c.jdbc.update("UPDATE admin_user SET enrolled=false");case "epoch"->c.jdbc.update("UPDATE admin_user SET security_version=security_version+1");case "revoked"->c.jdbc.update("UPDATE app_session SET revoked_at=clock_timestamp() WHERE id=?",binding.sessionId());case "browser"->binding=new SessionBinding(binding.sessionId(),"f".repeat(64));case "missing"->binding=new SessionBinding(UUID.randomUUID(),binding.browserHash());}
        var denied=binding;for(UUID id:Arrays.asList(booking,UUID.randomUUID(),null)){assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,b.fail(()->query.reconciliation(denied,id,page)).kind());assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,b.fail(()->query.notifications(denied,id,null,page)).kind());}
    }
    @Test void nonAdministratorDenialsAndMissingBookingsDoNotBecomeEmptyAuthorizedPages() {
        try(var anonymous=c.sessions.anonymous()){var session=c.binding(anonymous.session());assertEquals(Failure.Kind.FORBIDDEN,b.fail(()->query.reconciliation(session,null,page)).kind());assertEquals(Failure.Kind.FORBIDDEN,b.fail(()->query.notifications(session,booking,null,page)).kind());}
        assertEquals(Failure.Kind.NOT_FOUND,b.fail(()->query.reconciliation(b.session,UUID.randomUUID(),page)).kind());assertEquals(Failure.Kind.NOT_FOUND,b.fail(()->query.notifications(b.session,UUID.randomUUID(),null,page)).kind());
        assertTrue(query.reconciliation(b.session,booking,page).entries().isEmpty());assertTrue(query.notifications(b.session,booking,null,page).entries().isEmpty());
    }
    @Test void staleMfaReadsCaptureOneTimeWithoutTouchingSessionOrWaitingForWriteLocks() throws Exception {
        due(booking,"EUR",100);job(booking,"BOOKING_CONFIRMED",NotificationState.FAILED,null);Instant issued=c.jdbc.queryForObject("SELECT issued_at FROM app_session WHERE id=?",Timestamp.class,b.session.sessionId()).toInstant();var calls=new AtomicInteger();var reader=new PostgresAdministratorOperations(c.clock(issued.plusSeconds(601),calls));String seen=c.f.value("SELECT last_seen_at FROM app_session WHERE id=?",b.session.sessionId());
        try(var lock=c.f.source.getConnection();var statement=lock.createStatement();var pool=Executors.newVirtualThreadPerTaskExecutor()){
            lock.setAutoCommit(false);for(String table:List.of("scheduling_resource","app_session","admin_user","booking","payment_entry","notification_job"))statement.executeQuery("SELECT * FROM "+table+" FOR UPDATE").close();
            assertEquals(1,pool.submit(()->reader.reconciliation(b.session,booking,page).entries().size()).get(3,TimeUnit.SECONDS));assertEquals(1,calls.get());assertEquals(1,pool.submit(()->reader.notifications(b.session,booking,null,page).entries().size()).get(3,TimeUnit.SECONDS));assertEquals(2,calls.get());lock.rollback();}
        assertEquals(seen,c.f.value("SELECT last_seen_at FROM app_session WHERE id=?",b.session.sessionId()));
    }
    @Test void concurrentRefundAndAuthorityChangesCannotMixReadSnapshots() {
        UUID receipt=due(booking,"EUR",1000);var changed=new AtomicBoolean();var source=new DelegatingDataSource(c.f.source){@Override public Connection getConnection() throws SQLException {
            var connection=super.getConnection();return GuestAccessDatabaseTest.proxy(Connection.class,(p,m,a)->{Object result=GuestAccessDatabaseTest.invoke(connection,m,a);if(!m.getName().equals("prepareStatement")||!a[0].toString().startsWith("SELECT count(*) FROM booking"))return result;
                assertTrue(connection.isReadOnly());assertEquals(Connection.TRANSACTION_REPEATABLE_READ,connection.getTransactionIsolation());return GuestAccessDatabaseTest.proxy(PreparedStatement.class,(sp,sm,sa)->{Object rows=GuestAccessDatabaseTest.invoke(result,sm,sa);if(sm.getName().equals("executeQuery")&&changed.compareAndSet(false,true)){payment(booking,"EUR",1000,receipt);c.jdbc.update("UPDATE admin_user SET enabled=false");}return rows;});});}};
        assertEquals(1000,new PostgresAdministratorOperations(source).reconciliation(b.session,booking,page).entries().getFirst().outstandingMinor());assertTrue(changed.get());assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,b.fail(()->query.reconciliation(b.session,booking,page)).kind());
    }
    @Test void malformedArgumentsAndProviderFailuresRemainBoundedAndSafe() {
        var source=new DelegatingDataSource(c.f.source){@Override public Connection getConnection() throws SQLException {throw new SQLException("SYNTHETIC_PRIVATE_PROVIDER");}};var unavailable=new PostgresAdministratorOperations(source);
        assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->unavailable.reconciliation(b.session,null,page)).kind());assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->unavailable.notifications(b.session,null,null,page)).kind());
        assertThrows(NullPointerException.class,()->new PostgresAdministratorOperations(null));assertThrows(NullPointerException.class,()->query.reconciliation(null,null,page));assertThrows(NullPointerException.class,()->query.reconciliation(b.session,null,null));assertThrows(NullPointerException.class,()->query.notifications(b.session,null,null,null));
    }
}
