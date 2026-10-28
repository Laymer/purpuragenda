package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.application.port.out.AdministratorHistoryQuery.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.AdministratorNotes;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
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
class AdministratorHistoryPostgresTest {
    final AdminBookingPostgresTest b=new AdminBookingPostgresTest();
    @TempDir Path directory;
    CustomerCreationPostgresTest c;PostgresAdministratorHistory query;UUID booking;final Request page=new Request(null,100);
    @BeforeAll static void start(){MigrationTest.start();}
    @AfterAll static void stop(){MigrationTest.stop();}
    @BeforeEach void setup() throws Exception {
        b.directory=directory;b.setup();c=b.c;booking=b.create("create",b.request(3,10)).entityId();query=new PostgresAdministratorHistory(c.f.source);
    }
    @AfterEach void cleanup(){b.cleanup();}
    UUID note(String text){return new AdministratorNotes(new PostgresNotes(c.f.source,Set.of("zoom.us"))).append(b.session,booking,UUID.randomUUID().toString(),new AdministratorNotes.Addition(text)).entityId();}
    @Test void privateNotesAndTextFreeAuditsAreSeparateBoundedProjections() {
        assertTrue(query.notes(b.session,booking,page).entries().isEmpty());UUID id=note("Synthetic private note");
        NotePage notes=query.notes(b.session,booking,page);assertEquals(1,notes.entries().size());assertEquals(id,notes.entries().getFirst().id());assertEquals("Synthetic private note",notes.entries().getFirst().text());assertNull(notes.next());
        AuditPage events=query.audit(b.session,booking,page);Audit event=events.entries().stream().filter(e->e.type().equals("NOTE_ADDED")).findFirst().orElseThrow();
        assertEquals(id,event.id());assertEquals(booking,event.bookingId());assertEquals("ADMIN",event.actorKind());assertEquals("",event.reason());assertEquals(1,event.version());
        UUID global=UUID.randomUUID();c.jdbc.update("INSERT INTO audit_event(id,actor_kind,type,occurred_at) VALUES(?,'ADMIN','SYNTHETIC_GLOBAL',clock_timestamp())",global);
        assertTrue(query.audit(b.session,null,page).entries().stream().anyMatch(e->e.id().equals(global)&&e.bookingId()==null&&e.version()==null));
        assertFalse(query.audit(b.session,booking,page).entries().stream().anyMatch(e->e.id().equals(global)));
    }
    @Test void sameTimeCursorPagesAreStableNewestFirstAndDoNotLeakOtherBookings() {
        Instant time=c.now();var ids=new ArrayList<UUID>();
        for(int i=0;i<5;i++){UUID id=UUID.randomUUID();ids.add(id);c.jdbc.update("INSERT INTO booking_note(id,booking_id,actor_id,note,created_at) VALUES(?,?,?,'Synthetic cursor note',?)",id,booking,b.adminId,Timestamp.from(time));
            c.jdbc.update("INSERT INTO audit_event(id,booking_id,actor_kind,type,occurred_at) VALUES(?,?,'ADMIN','SYNTHETIC_CURSOR',?)",id,booking,Timestamp.from(time));}
        ids.sort(Comparator.comparing(UUID::toString).reversed());var first=query.notes(b.session,booking,new Request(null,2));var second=query.notes(b.session,booking,new Request(first.next(),2));var last=query.notes(b.session,booking,new Request(second.next(),2));
        assertEquals(ids.subList(0,2),first.entries().stream().map(Note::id).toList());assertEquals(ids.subList(2,4),second.entries().stream().map(Note::id).toList());assertEquals(ids.subList(4,5),last.entries().stream().map(Note::id).toList());assertNull(last.next());
        var auditFirst=query.audit(b.session,booking,new Request(null,2));var auditSecond=query.audit(b.session,booking,new Request(auditFirst.next(),2));assertEquals(ids.subList(0,2),auditFirst.entries().stream().map(Audit::id).toList());assertEquals(ids.subList(2,4),auditSecond.entries().stream().map(Audit::id).toList());
        Cursor oldest=new Cursor(Instant.parse("0001-01-01T00:00:00Z"),UUID.randomUUID());assertTrue(query.notes(b.session,booking,new Request(oldest,1)).entries().isEmpty());assertTrue(query.audit(b.session,null,new Request(oldest,1)).entries().isEmpty());
        UUID other=b.create("other",b.request(4,10)).entityId();assertTrue(query.notes(b.session,other,page).entries().isEmpty());assertTrue(query.audit(b.session,other,page).entries().stream().allMatch(e->e.bookingId().equals(other)));
    }
    @ParameterizedTest @ValueSource(strings={"disabled","unenrolled","epoch","revoked","browser","missing"})
    void authorityIsCheckedBeforeReadingEvenUnknownBookings(String change) {
        note("Synthetic private note");SessionBinding binding=b.session;
        switch(change){case "disabled"->c.jdbc.update("UPDATE admin_user SET enabled=false");case "unenrolled"->c.jdbc.update("UPDATE admin_user SET enrolled=false");case "epoch"->c.jdbc.update("UPDATE admin_user SET security_version=security_version+1");case "revoked"->c.jdbc.update("UPDATE app_session SET revoked_at=clock_timestamp() WHERE id=?",binding.sessionId());case "browser"->binding=new SessionBinding(binding.sessionId(),"f".repeat(64));case "missing"->binding=new SessionBinding(UUID.randomUUID(),binding.browserHash());}
        var denied=binding;for(UUID id:List.of(booking,UUID.randomUUID())){
            assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,b.fail(()->query.notes(denied,id,page)).kind());assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,b.fail(()->query.audit(denied,id,page)).kind());}
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,b.fail(()->query.audit(denied,null,page)).kind());
    }
    @Test void nonAdministratorsCannotReadAndUnknownTargetsReturnNotFound() {
        try(var anonymous=c.sessions.anonymous()) {var session=c.binding(anonymous.session());assertEquals(Failure.Kind.FORBIDDEN,b.fail(()->query.notes(session,booking,page)).kind());assertEquals(Failure.Kind.FORBIDDEN,b.fail(()->query.audit(session,null,page)).kind());}
        var guest=c.binding(c.guest("synthetic@example.test"));assertEquals(Failure.Kind.FORBIDDEN,b.fail(()->query.notes(guest,booking,page)).kind());
        UUID missing=UUID.randomUUID();assertEquals(Failure.Kind.NOT_FOUND,b.fail(()->query.notes(b.session,missing,page)).kind());assertEquals(Failure.Kind.NOT_FOUND,b.fail(()->query.audit(b.session,missing,page)).kind());
    }
    @Test void readsUseOneClockWithoutFreshFactorOrSessionTouchOrMutationLocks() throws Exception {
        note("Synthetic private note");Instant issued=c.jdbc.queryForObject("SELECT issued_at FROM app_session WHERE id=?",Timestamp.class,b.session.sessionId()).toInstant();var reads=new AtomicInteger();
        var staleFactor=new PostgresAdministratorHistory(c.clock(issued.plusSeconds(601),reads));String seen=c.f.value("SELECT last_seen_at FROM app_session WHERE id=?",b.session.sessionId());
        try(var lock=c.f.source.getConnection();var statement=lock.createStatement();var pool=Executors.newVirtualThreadPerTaskExecutor()){
            lock.setAutoCommit(false);for(String table:List.of("scheduling_resource","admin_user","app_session","booking","booking_note","audit_event"))statement.executeQuery("SELECT * FROM "+table+" FOR UPDATE").close();
            assertEquals(1,pool.submit(()->staleFactor.notes(b.session,booking,page).entries().size()).get(3,TimeUnit.SECONDS));assertEquals(1,reads.get());
            assertFalse(pool.submit(()->staleFactor.audit(b.session,null,page).entries().isEmpty()).get(3,TimeUnit.SECONDS));assertEquals(2,reads.get());lock.rollback();}
        assertEquals(seen,c.f.value("SELECT last_seen_at FROM app_session WHERE id=?",b.session.sessionId()));
        var expired=new PostgresAdministratorHistory(c.clock(issued.plusSeconds(901),new AtomicInteger()));assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,b.fail(()->expired.notes(b.session,booking,page)).kind());
    }
    @Test void concurrentRevocationAndNoteAppendCannotMixAuthorityAndDataSnapshots() {
        note("Synthetic original");var changed=new AtomicBoolean();
        var source=new DelegatingDataSource(c.f.source){@Override public Connection getConnection() throws SQLException {
            var connection=super.getConnection();return GuestAccessDatabaseTest.proxy(Connection.class,(p,m,a)->{
                Object result=GuestAccessDatabaseTest.invoke(connection,m,a);if(!m.getName().equals("prepareStatement")||!a[0].toString().startsWith("SELECT id,note,created_at"))return result;
                assertTrue(connection.isReadOnly());assertEquals(Connection.TRANSACTION_REPEATABLE_READ,connection.getTransactionIsolation());
                return GuestAccessDatabaseTest.proxy(PreparedStatement.class,(sp,sm,sa)->{Object rows=GuestAccessDatabaseTest.invoke(result,sm,sa);if(sm.getName().equals("executeQuery")&&changed.compareAndSet(false,true)){note("Synthetic concurrent");c.jdbc.update("UPDATE admin_user SET enabled=false");}return rows;});});}};
        assertEquals(1,new PostgresAdministratorHistory(source).notes(b.session,booking,page).entries().size());assertTrue(changed.get());assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,b.fail(()->query.notes(b.session,booking,page)).kind());
    }
    @Test void providerFailuresAndInvalidArgumentsStaySafe() {
        var source=new DelegatingDataSource(c.f.source){@Override public Connection getConnection() throws SQLException {throw new SQLException("SYNTHETIC_PRIVATE_PROVIDER_DETAIL");}};
        var unavailable=new PostgresAdministratorHistory(source);assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->unavailable.notes(b.session,booking,page)).kind());assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->unavailable.audit(b.session,null,page)).kind());
        assertThrows(NullPointerException.class,()->new PostgresAdministratorHistory(null));assertThrows(NullPointerException.class,()->query.notes(null,booking,page));assertThrows(NullPointerException.class,()->query.notes(b.session,null,page));assertThrows(NullPointerException.class,()->query.notes(b.session,booking,null));assertThrows(NullPointerException.class,()->query.audit(b.session,null,null));
    }
}
