package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.application.port.out.AdministratorBookingQuery.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.AdministratorBookingView;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(180)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class AdministratorBookingQueryPostgresTest {
    final AdminBookingPostgresTest fixture=new AdminBookingPostgresTest();
    final CustomerCreationPostgresTest c=fixture.c;
    @TempDir Path directory;
    PostgresAdministratorBookingQuery query;
    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void setup() throws Exception { fixture.directory=directory;fixture.setup();query=reader(c.f.source); }
    @AfterEach void cleanup() { fixture.cleanup(); }
    PostgresAdministratorBookingQuery reader(DataSource source) { return new PostgresAdministratorBookingQuery(source,Set.of("zoom.us")); }
    UUID book(Instant start,String state) { return c.f.database.booking(start,state,MigrationTest.snapshot()); }
    Window window() { return new Window(MigrationTest.START.minusSeconds(86400),MigrationTest.START.plusSeconds(86400)); }
    Detail detail(UUID id) { return query.detail(fixture.session,id).orElseThrow(); }

    @Test void enabledPersistedAdministratorReadsImmutableSnapshotsActualDurationAndIndependentMoney() {
        UUID id=book(MigrationTest.START,"CONFIRMED");var first=detail(id);assertEquals(Booking.State.CONFIRMED,first.booking().state());
        assertEquals(PaymentLedger.Status.UNPAID,first.ledger().summary(first.booking().snapshot().service().price()).status());
        assertEquals("AdministratorBookingDetail[REDACTED]",first.toString());
        c.jdbc.update("UPDATE service SET name='Changed synthetic service',version=version+1 WHERE id=?",UUID.fromString(MigrationTest.SERVICE));
        c.jdbc.update("UPDATE booking SET delivery_email='current@example.test',version=version+1 WHERE id=?",id);
        UUID receipt=UUID.randomUUID();c.jdbc.update("INSERT INTO payment_entry(id,booking_id,kind,amount_minor,currency,method,reference,verified_at) VALUES(?,?,'RECEIPT',1500,'EUR','SYNTHETIC','synthetic-payment',clock_timestamp())",receipt,id);
        c.jdbc.update("INSERT INTO refund_obligation(receipt_id,due_minor,reason) VALUES(?,1500,'SYNTHETIC')",receipt);
        var current=detail(id);assertEquals(first.booking().snapshot(),current.booking().snapshot());assertEquals("current@example.test",current.booking().deliveryEmail());
        assertEquals(1,current.ledger().entries().size());assertEquals(Map.of("EUR",1500L),current.ledger().summary(current.booking().snapshot().service().price()).refundDueByCurrency());
        var agenda=query.agenda(fixture.session,window(),null,100);assertEquals(1,agenda.entries().size());var row=agenda.entries().getFirst();
        assertEquals(current.booking().sessionEnd(),row.sessionEnd());assertEquals(1800,Duration.between(row.start(),row.sessionEnd()).toSeconds());
        assertEquals(first.booking().snapshot().service().name(),row.serviceName());assertNull(agenda.next());
        assertEquals(Set.of("id","reference","version","state","start","sessionEnd","serviceName"),Arrays.stream(Entry.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet()));
        assertTrue(query.detail(fixture.session,UUID.randomUUID()).isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"disabled","unenrolled","epoch","revoked","browser","missing"})
    void revokedDisabledUnenrolledOrStalePersistedAuthorityNeverReadsEvenUnknownTargets(String change) {
        UUID id=book(MigrationTest.START,"CONFIRMED");SessionBinding binding=fixture.session;
        switch(change) {
            case "disabled" -> c.jdbc.update("UPDATE admin_user SET enabled=false");
            case "unenrolled" -> c.jdbc.update("UPDATE admin_user SET enrolled=false");
            case "epoch" -> c.jdbc.update("UPDATE admin_user SET security_version=security_version+1");
            case "revoked" -> c.jdbc.update("UPDATE app_session SET revoked_at=clock_timestamp() WHERE id=?",binding.sessionId());
            case "browser" -> binding=new SessionBinding(binding.sessionId(),"f".repeat(64));
            case "missing" -> binding=new SessionBinding(UUID.randomUUID(),binding.browserHash());
        }
        var denied=binding;
        for(UUID target:List.of(id,UUID.randomUUID()))assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,c.failure(()->query.detail(denied,target)).kind());
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,c.failure(()->query.agenda(denied,window(),null,1)).kind());
    }
    @Test void anonymousAndGuestAuthorityCannotReadTheAdministratorArea() {
        UUID id=book(MigrationTest.START,"CONFIRMED");
        try(var anonymous=c.sessions.anonymous()) {
            var binding=c.binding(anonymous.session());assertEquals(Failure.Kind.FORBIDDEN,c.failure(()->query.detail(binding,id)).kind());
            assertEquals(Failure.Kind.FORBIDDEN,c.failure(()->query.agenda(binding,window(),null,1)).kind());
        }
        var guest=c.guest("synthetic@example.test");assertEquals(Failure.Kind.FORBIDDEN,c.failure(()->query.detail(c.binding(guest),id)).kind());
    }
    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void idleAndAbsoluteExpiryUseOneDatabaseTimeAndStrictEquality(long micros) {
        UUID id=book(MigrationTest.START,"CONFIRMED");
        Instant idle=c.jdbc.queryForObject("SELECT last_seen_at+interval '15 minutes' FROM app_session WHERE id=?",(row,n)->row.getTimestamp(1).toInstant(),fixture.session.sessionId());
        var reads=new AtomicInteger();var atIdle=reader(c.clock(idle.plusNanos(micros*1000),reads));
        if(micros<0)assertTrue(atIdle.detail(fixture.session,id).isPresent());else assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,c.failure(()->atIdle.detail(fixture.session,id)).kind());
        assertEquals(1,reads.get());
        Instant expiry=c.jdbc.queryForObject("SELECT absolute_expires_at FROM app_session WHERE id=?",(row,n)->row.getTimestamp(1).toInstant(),fixture.session.sessionId());
        c.jdbc.update("UPDATE app_session SET last_seen_at=? WHERE id=?",Timestamp.from(expiry.minusSeconds(1)),fixture.session.sessionId());
        var atExpiry=reader(c.clock(expiry.plusNanos(micros*1000),new AtomicInteger()));
        if(micros<0)assertTrue(atExpiry.detail(fixture.session,id).isPresent());else assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,c.failure(()->atExpiry.detail(fixture.session,id)).kind());
    }
    @Test void readsRequireIssuedMfaButDoNotInventATenMinuteDetailStepUp() {
        UUID id=book(MigrationTest.START,"CONFIRMED");Instant issued=c.jdbc.queryForObject("SELECT issued_at FROM app_session WHERE id=?",(row,n)->row.getTimestamp(1).toInstant(),fixture.session.sessionId());
        var future=reader(c.clock(issued.minusNanos(1000),new AtomicInteger()));assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,c.failure(()->future.detail(fixture.session,id)).kind());
        var ordinary=reader(c.clock(issued.plusSeconds(601),new AtomicInteger()));assertTrue(ordinary.detail(fixture.session,id).isPresent());
    }
    @Test void stableStartIdCursorIncludesEverySameTimeCancelledRowWithoutRepeatingOrSkipping() {
        var expected=new ArrayList<UUID>();for(int i=0;i<4;i++)expected.add(book(MigrationTest.START,"CANCELLED"));
        expected.sort(Comparator.comparing(UUID::toString));var first=query.agenda(fixture.session,window(),null,2);assertEquals(expected.subList(0,2),first.entries().stream().map(Entry::id).toList());assertNotNull(first.next());
        var second=query.agenda(fixture.session,window(),first.next(),2);assertEquals(expected.subList(2,4),second.entries().stream().map(Entry::id).toList());assertNull(second.next());
        assertTrue(query.agenda(fixture.session,window(),new Cursor(second.entries().getLast().start(),second.entries().getLast().id()),1).entries().isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"2027-03-28","2027-10-31"})
    void inclusiveCivilDateRangeUsesBrusselsDstMidnightsAndExcludesTheNextDay(String value) {
        LocalDate date=LocalDate.parse(value);Instant start=date.atStartOfDay(ZoneId.of("Europe/Brussels")).toInstant(),end=date.plusDays(1).atStartOfDay(ZoneId.of("Europe/Brussels")).toInstant();
        book(start.minusNanos(1000),"CANCELLED");UUID first=book(start,"CANCELLED"),last=book(end.minusNanos(1000),"CANCELLED");book(end,"CANCELLED");
        var result=new AdministratorBookingView(query).agenda(fixture.session,date,date,null,100);assertEquals(List.of(first,last),result.entries().stream().map(Entry::id).toList());
    }
    @Test void readersNeverTouchSessionsExpireHoldsOrWriteAndDoNotWaitForSchedulingOrAuthorityLocks() throws Exception {
        UUID id=book(MigrationTest.START,"PENDING_CONFIRMATION");String seen=c.f.value("SELECT last_seen_at FROM app_session WHERE id=?",fixture.session.sessionId());
        int events=c.count("outbox_event"),audit=c.count("audit_event");
        try(var lock=c.f.source.getConnection();var statement=lock.createStatement();var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            lock.setAutoCommit(false);for(String table:List.of("scheduling_resource","admin_user","app_session","booking"))statement.executeQuery("SELECT * FROM "+table+" FOR UPDATE").close();
            assertEquals(1,pool.submit(()->query.agenda(fixture.session,window(),null,100).entries().size()).get(3,TimeUnit.SECONDS));
            assertTrue(pool.submit(()->query.detail(fixture.session,id).isPresent()).get(3,TimeUnit.SECONDS));lock.rollback();
        }
        assertEquals(seen,c.f.value("SELECT last_seen_at FROM app_session WHERE id=?",fixture.session.sessionId()));assertEquals("PENDING_CONFIRMATION",c.f.value("SELECT state FROM booking WHERE id=?",id));assertEquals(events,c.count("outbox_event"));assertEquals(audit,c.count("audit_event"));
    }
    @Test void independentReadSnapshotDoesNotJoinAnUnrelatedCallerTransaction() {
        UUID id=book(MigrationTest.START,"CONFIRMED");
        new TransactionTemplate(new DataSourceTransactionManager(c.f.source)).execute(status->{
            c.jdbc.update("UPDATE admin_user SET enabled=false");assertTrue(query.detail(fixture.session,id).isPresent());status.setRollbackOnly();return null;
        });assertTrue(detail(id).booking().previouslyConfirmed());
    }
    @Test void concurrentBookingMoneyAndAuthorityChangesCannotMixSnapshots() {
        UUID id=book(MigrationTest.START,"PENDING_CONFIRMATION");var changed=new AtomicBoolean();
        var concurrent=new DelegatingDataSource(c.f.source) {
            @Override public Connection getConnection() throws SQLException {
                var connection=super.getConnection();return GuestAccessDatabaseTest.proxy(Connection.class,(p,m,a)->{
                    var result=GuestAccessDatabaseTest.invoke(connection,m,a);if(!m.getName().equals("prepareStatement") || !a[0].toString().startsWith("SELECT b.*"))return result;
                    assertTrue(connection.isReadOnly());assertEquals(Connection.TRANSACTION_REPEATABLE_READ,connection.getTransactionIsolation());
                    return GuestAccessDatabaseTest.proxy(PreparedStatement.class,(sp,sm,sa)->{
                        var rows=GuestAccessDatabaseTest.invoke(result,sm,sa);
                        if(sm.getName().equals("executeQuery") && changed.compareAndSet(false,true)) {
                            c.jdbc.update("UPDATE admin_user SET enabled=false");
                            c.jdbc.update("INSERT INTO payment_entry(id,booking_id,kind,amount_minor,currency,method,reference,verified_at) VALUES(?,?,'RECEIPT',1500,'EUR','SYNTHETIC','synthetic-payment',clock_timestamp())",UUID.randomUUID(),id);
                        }
                        return rows;
                    });
                });
            }
        };
        var before=reader(concurrent).detail(fixture.session,id).orElseThrow();assertTrue(changed.get());assertTrue(before.ledger().entries().isEmpty());
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,c.failure(()->query.detail(fixture.session,id)).kind());
    }
    @Test void PostgreSqlEnforcesReadOnlyAndFailureRollsBackWithoutLeakingTheConnectionState() {
        UUID id=book(MigrationTest.START,"CONFIRMED");var attempted=new AtomicBoolean();
        var injected=new DelegatingDataSource(c.f.source) {
            @Override public Connection getConnection() throws SQLException {
                var connection=super.getConnection();return GuestAccessDatabaseTest.proxy(Connection.class,(p,m,a)->{
                    var result=GuestAccessDatabaseTest.invoke(connection,m,a);if(!m.getName().equals("prepareStatement") || !a[0].toString().contains("SELECT s.kind"))return result;
                    return GuestAccessDatabaseTest.proxy(PreparedStatement.class,(sp,sm,sa)->{
                        if(sm.getName().equals("executeQuery") && attempted.compareAndSet(false,true)) {
                            try(var statement=connection.createStatement()) {
                                SQLException denied=assertThrows(SQLException.class,()->statement.executeUpdate("INSERT INTO audit_event(id,actor_kind,type,occurred_at) VALUES(gen_random_uuid(),'SYSTEM','SYNTHETIC_READ_WRITE',clock_timestamp())"));
                                assertEquals("25006",denied.getSQLState());throw denied;
                            }
                        }
                        return GuestAccessDatabaseTest.invoke(result,sm,sa);
                    });
                });
            }
        };
        assertEquals(Failure.Kind.STORAGE_FAILURE,c.failure(()->reader(injected).detail(fixture.session,id)).kind());assertTrue(attempted.get());
        assertEquals(0,c.count("audit_event WHERE type='SYNTHETIC_READ_WRITE'"));assertTrue(query.detail(fixture.session,id).isPresent());
        c.jdbc.update("INSERT INTO audit_event(id,actor_kind,type,occurred_at) VALUES(?,'SYSTEM','SYNTHETIC_AFTER_READ',clock_timestamp())",UUID.randomUUID());
        assertEquals(1,c.count("audit_event WHERE type='SYNTHETIC_AFTER_READ'"));
    }
    @Test void invalidInputsAndProviderFailuresStayBoundedAndSafe() {
        var unavailable=new DelegatingDataSource(c.f.source) { @Override public Connection getConnection() throws SQLException { throw new SQLException("SYNTHETIC_PRIVATE_PROVIDER_DETAIL"); } };
        assertEquals(Failure.Kind.STORAGE_FAILURE,c.failure(()->reader(unavailable).detail(fixture.session,UUID.randomUUID())).kind());
        assertEquals(Failure.Kind.STORAGE_FAILURE,c.failure(()->reader(unavailable).agenda(fixture.session,window(),null,1)).kind());
        assertThrows(NullPointerException.class,()->reader(null));assertThrows(IllegalArgumentException.class,()->new PostgresAdministratorBookingQuery(c.f.source,null));assertThrows(IllegalArgumentException.class,()->new PostgresAdministratorBookingQuery(c.f.source,Set.of()));
        assertThrows(NullPointerException.class,()->query.detail(null,UUID.randomUUID()));assertThrows(NullPointerException.class,()->query.detail(fixture.session,null));assertThrows(NullPointerException.class,()->query.agenda(fixture.session,null,null,1));
        for(int limit:new int[]{0,101})assertThrows(IllegalArgumentException.class,()->query.agenda(fixture.session,window(),null,limit));
        assertThrows(IllegalArgumentException.class,()->query.agenda(fixture.session,new Window(Instant.EPOCH,Instant.EPOCH.plusSeconds(746*3600)),null,1));
        for(Instant invalid:List.of(window().from().minusNanos(1),window().until()))assertThrows(IllegalArgumentException.class,()->query.agenda(fixture.session,window(),new Cursor(invalid,UUID.randomUUID()),1));
    }
}
