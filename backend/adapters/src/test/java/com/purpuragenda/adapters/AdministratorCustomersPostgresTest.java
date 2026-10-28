package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.application.port.out.AdministratorCustomerQuery.*;
import com.purpuragenda.application.port.out.AdministratorHistoryQuery;
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
class AdministratorCustomersPostgresTest {
    final AdminBookingPostgresTest b=new AdminBookingPostgresTest();
    @TempDir Path directory;
    CustomerCreationPostgresTest c;PostgresAdministratorCustomers query;UUID booking,customer;
    final AdministratorHistoryQuery.Request page=new AdministratorHistoryQuery.Request(null,100);
    @BeforeAll static void start(){MigrationTest.start();}
    @AfterAll static void stop(){MigrationTest.stop();}
    @BeforeEach void setup() throws Exception {
        b.directory=directory;b.setup();c=b.c;booking=b.create("create",b.request(3,10)).entityId();customer=c.jdbc.queryForObject("SELECT customer_id FROM booking WHERE id=?",UUID.class,booking);query=new PostgresAdministratorCustomers(c.f.source);
    }
    @AfterEach void cleanup(){b.cleanup();}
    Page search(String text){return query.search(b.session,new Search(text,null,100));}
    UUID identity(String email){UUID id=UUID.randomUUID();c.jdbc.update("INSERT INTO customer_identity VALUES(?,?,clock_timestamp())",id,email);return id;}
    @Test void searchesStableEmailProfileSnapshotDeliveryAndReferenceWithoutExposingPrivateFields() {
        assertEquals(customer,search("CUSTOMER@EXAMPLE").entries().getFirst().id());assertEquals(customer,search("Synthetic").entries().getFirst().id());
        String reference=c.jdbc.queryForObject("SELECT reference FROM booking WHERE id=?",String.class,booking);assertEquals(customer,search(reference).entries().getFirst().id());
        c.jdbc.update("UPDATE booking SET delivery_email='new-delivery@example.test',version=version+1 WHERE id=?",booking);Customer result=search("NEW-DELIVERY").entries().getFirst();assertEquals(customer,result.id());assertEquals("customer@example.test",result.email());assertFalse(result.enrolled());
        UUID profile=identity("profile@example.test");c.jdbc.update("INSERT INTO customer_profile(customer_id,document) VALUES(?,?::jsonb)",profile,"{\"firstName\":\"Distinct\",\"lastName\":\"Profile\",\"telephone\":\"\"}");
        c.jdbc.update("INSERT INTO customer_account(id,customer_id,enrolled_at) VALUES(?,?,clock_timestamp())",UUID.randomUUID(),profile);
        var found=search("distinct profile").entries();assertEquals(1,found.size());assertEquals(profile,found.getFirst().id());assertTrue(found.getFirst().enrolled());assertNull(search("no matching identity").next());
    }
    @Test void literalSearchAndStableIdentityCursorCannotBecomeWildcardsOrSkipMatchingRows() {
        List<UUID> expected=new ArrayList<>();for(int i=0;i<5;i++)expected.add(identity("paged-"+i+"@example.test"));expected.sort(Comparator.comparing(UUID::toString));
        Page first=query.search(b.session,new Search("paged-",null,2)),second=query.search(b.session,new Search("paged-",first.next(),2)),last=query.search(b.session,new Search("paged-",second.next(),2));
        assertEquals(expected.subList(0,2),first.entries().stream().map(Customer::id).toList());assertEquals(expected.subList(2,4),second.entries().stream().map(Customer::id).toList());assertEquals(expected.subList(4,5),last.entries().stream().map(Customer::id).toList());assertNull(last.next());
        for(String term:List.of("%_","' OR true --","\\\\"))assertTrue(search(term).entries().isEmpty());
        assertTrue(query.search(b.session,new Search("paged-",expected.getLast(),1)).entries().isEmpty());
    }
    @Test void historyUsesStableOwnershipAndImmutableServiceSnapshotsWithBoundedNewestFirstPages() {
        UUID later=b.create("later",b.request(4,10)).entityId();UUID other=identity("unrelated@example.test");String oldName=query.bookings(b.session,customer,page).history().bookings().getFirst().serviceName();
        c.jdbc.update("UPDATE booking SET delivery_email='unrelated@example.test',version=version+1 WHERE id=?",booking);c.jdbc.update("UPDATE service SET name='Changed catalog',version=version+1");
        var first=query.bookings(b.session,customer,new AdministratorHistoryQuery.Request(null,1));assertEquals(customer,first.customer().id());assertEquals(later,first.history().bookings().getFirst().id());assertEquals(oldName,first.history().bookings().getFirst().serviceName());assertNotNull(first.history().next());
        var cursor=first.history().next();var second=query.bookings(b.session,customer,new AdministratorHistoryQuery.Request(new AdministratorHistoryQuery.Cursor(cursor.start(),cursor.id()),1));assertEquals(booking,second.history().bookings().getFirst().id());assertNull(second.history().next());
        assertTrue(query.bookings(b.session,other,page).history().bookings().isEmpty());assertEquals(2,search("unrelated@example.test").entries().size());
        var empty=query.bookings(b.session,customer,new AdministratorHistoryQuery.Request(new AdministratorHistoryQuery.Cursor(Instant.EPOCH,UUID.randomUUID()),1));assertTrue(empty.history().bookings().isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"disabled","unenrolled","epoch","revoked","browser","missing"})
    void persistedAuthorityPrecedesEverySearchAndUnknownCustomerLookup(String change) {
        SessionBinding binding=b.session;switch(change){case "disabled"->c.jdbc.update("UPDATE admin_user SET enabled=false");case "unenrolled"->c.jdbc.update("UPDATE admin_user SET enrolled=false");case "epoch"->c.jdbc.update("UPDATE admin_user SET security_version=security_version+1");case "revoked"->c.jdbc.update("UPDATE app_session SET revoked_at=clock_timestamp() WHERE id=?",binding.sessionId());case "browser"->binding=new SessionBinding(binding.sessionId(),"f".repeat(64));case "missing"->binding=new SessionBinding(UUID.randomUUID(),binding.browserHash());}
        var denied=binding;assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,b.fail(()->query.search(denied,new Search("synthetic",null,1))).kind());
        for(UUID id:List.of(customer,UUID.randomUUID()))assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,b.fail(()->query.bookings(denied,id,page)).kind());
    }
    @Test void nonAdministratorsCannotSearchAndUnknownCustomersReturnNotFoundAfterAuthority() {
        try(var anonymous=c.sessions.anonymous()){var session=c.binding(anonymous.session());assertEquals(Failure.Kind.FORBIDDEN,b.fail(()->query.search(session,new Search("synthetic",null,1))).kind());assertEquals(Failure.Kind.FORBIDDEN,b.fail(()->query.bookings(session,customer,page)).kind());}
        assertEquals(Failure.Kind.NOT_FOUND,b.fail(()->query.bookings(b.session,UUID.randomUUID(),page)).kind());
    }
    @Test void staleMfaReadsCaptureOneTimeWithoutTouchingSessionsOrWaitingForWriteLocks() throws Exception {
        Instant issued=c.jdbc.queryForObject("SELECT issued_at FROM app_session WHERE id=?",Timestamp.class,b.session.sessionId()).toInstant();var calls=new AtomicInteger();var reader=new PostgresAdministratorCustomers(c.clock(issued.plusSeconds(601),calls));
        String seen=c.f.value("SELECT last_seen_at FROM app_session WHERE id=?",b.session.sessionId());
        try(var lock=c.f.source.getConnection();var statement=lock.createStatement();var pool=Executors.newVirtualThreadPerTaskExecutor()){
            lock.setAutoCommit(false);for(String table:List.of("scheduling_resource","app_session","admin_user","customer_identity","booking"))statement.executeQuery("SELECT * FROM "+table+" FOR UPDATE").close();
            assertEquals(1,pool.submit(()->reader.search(b.session,new Search("customer@",null,10)).entries().size()).get(3,TimeUnit.SECONDS));assertEquals(1,calls.get());
            assertEquals(1,pool.submit(()->reader.bookings(b.session,customer,page).history().bookings().size()).get(3,TimeUnit.SECONDS));assertEquals(2,calls.get());lock.rollback();}
        assertEquals(seen,c.f.value("SELECT last_seen_at FROM app_session WHERE id=?",b.session.sessionId()));
    }
    @Test void concurrentChangesCannotMixAuthorizedCustomerAndBookingSnapshots() {
        var changed=new AtomicBoolean();var source=new DelegatingDataSource(c.f.source){@Override public Connection getConnection() throws SQLException {
            var connection=super.getConnection();return GuestAccessDatabaseTest.proxy(Connection.class,(p,m,a)->{Object result=GuestAccessDatabaseTest.invoke(connection,m,a);if(!m.getName().equals("prepareStatement")||!a[0].toString().startsWith("SELECT c.id"))return result;
                assertTrue(connection.isReadOnly());assertEquals(Connection.TRANSACTION_REPEATABLE_READ,connection.getTransactionIsolation());return GuestAccessDatabaseTest.proxy(PreparedStatement.class,(sp,sm,sa)->{Object rows=GuestAccessDatabaseTest.invoke(result,sm,sa);if(sm.getName().equals("executeQuery")&&changed.compareAndSet(false,true)){b.create("concurrent",b.request(4,10));c.jdbc.update("UPDATE admin_user SET enabled=false");}return rows;});});}};
        assertEquals(1,new PostgresAdministratorCustomers(source).bookings(b.session,customer,page).history().bookings().size());assertTrue(changed.get());assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,b.fail(()->search("customer")).kind());
    }
    @Test void malformedArgumentsAndProviderFailuresStayBoundedAndSafe() {
        var source=new DelegatingDataSource(c.f.source){@Override public Connection getConnection() throws SQLException {throw new SQLException("SYNTHETIC_PRIVATE_PROVIDER");}};var unavailable=new PostgresAdministratorCustomers(source);
        assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->unavailable.search(b.session,new Search("synthetic",null,1))).kind());assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->unavailable.bookings(b.session,customer,page)).kind());
        assertThrows(NullPointerException.class,()->new PostgresAdministratorCustomers(null));assertThrows(NullPointerException.class,()->query.search(b.session,null));assertThrows(NullPointerException.class,()->query.search(null,new Search("synthetic",null,1)));assertThrows(NullPointerException.class,()->query.bookings(b.session,null,page));assertThrows(NullPointerException.class,()->query.bookings(b.session,customer,null));
    }
}
