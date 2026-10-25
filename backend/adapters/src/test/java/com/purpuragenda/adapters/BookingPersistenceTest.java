package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.postgres.PostgresScheduling;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.customer.Intake;
import com.purpuragenda.domain.scheduling.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class BookingPersistenceTest {
    MigrationTest fixture;
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    PostgresScheduling unit;
    UUID customer;
    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void prepare() {
        fixture=new MigrationTest();fixture.fresh();
        source=new DriverManagerDataSource(MigrationTest.jdbcUrl,MigrationTest.APP,MigrationTest.appPassword);
        Properties properties=new Properties();properties.setProperty("currentSchema",fixture.schema+",public");source.setConnectionProperties(properties);
        jdbc=new JdbcTemplate(source);unit=new PostgresScheduling(source);customer=customer();
    }
    @AfterEach void cleanup() { fixture.cleanup(); }
    UUID customer() {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO customer_identity VALUES(?,?,clock_timestamp())",id,id+"@example.test");return id;
    }
    CommandKey key() { return new CommandKey(new Scope(UUID.randomUUID(),"SYNTHETIC",null),UUID.randomUUID().toString(),CommandKey.fingerprint(List.of("normalized"))); }
    Receipt ok(Booking value) { return new Receipt(Code.OK,value.id(),value.version()); }
    Booking pending(Transaction tx,Instant start) {
        var snapshot=new Booking.Snapshot(tx.services().getFirst(),tx.policy(),new Intake("Alex","Synthetic","+32400123456",
                LocalDate.of(1990,1,1),LocalTime.of(10,30,15),"Synthetic city","Synthetic note"),"synthetic@example.test","America/New_York","test");
        return Booking.pending(UUID.randomUUID(),"synthetic-"+UUID.randomUUID(),customer,snapshot,Booking.Origin.CUSTOMER,tx.now(),start);
    }
    Booking create() {
        AtomicReference<Booking> result=new AtomicReference<>();
        unit.execute(key(),tx->tx.attempt(()->{Booking value=pending(tx,tx.now().plusSeconds(259200));tx.save(value);result.set(value);return ok(value);}));
        return result.get();
    }
    Booking load(UUID id) { return unit.read(view->view.booking(id).orElseThrow()); }
    String snapshotHash(UUID id) { return jdbc.queryForObject("SELECT md5(document::text) FROM booking_snapshot WHERE booking_id=?",String.class,id); }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
    void corrupt(String table,String sql,Object... values) {
        try(Connection c=fixture.connection(MigrationTest.MIGRATOR)) {
            c.setAutoCommit(false);MigrationTest.execute(c,"ALTER TABLE "+table+" DISABLE TRIGGER USER");
            MigrationTest.execute(c,sql,values);MigrationTest.execute(c,"ALTER TABLE "+table+" ENABLE TRIGGER USER");c.commit();
        } catch(SQLException failure) { throw MigrationTest.safe(failure); }
    }
    void invalidRead(UUID id) {
        Failure failure=assertThrows(Failure.class,()->load(id));
        assertEquals(Failure.Kind.STORAGE_FAILURE,failure.kind());assertNull(failure.getCause());
    }
    Booking copy(Booking b,UUID identity,String reference,Booking.Origin origin,Instant created,Booking.Snapshot snapshot,long version) {
        return new Booking(b.id(),reference,identity,snapshot,b.deliveryEmail(),b.state(),origin,created,b.start(),b.expiresAt(),b.meeting(),b.confirmationBasis(),version,b.calendarRevision(),b.previouslyConfirmed());
    }
    @Test void catalogPolicyCalendarAndBoundedBookingsRoundTrip() {
        AtomicReference<Booking> first=new AtomicReference<>(),second=new AtomicReference<>();
        unit.execute(key(),tx->tx.attempt(()->{
            Booking a=pending(tx,tx.now().plusSeconds(259200)),b=pending(tx,a.reservedEnd());
            tx.save(a);tx.save(b);first.set(a);second.set(b);return ok(a);
        }));
        unit.read(view->{
            assertEquals(2,view.services().size());assertEquals(SchedulingPolicy.defaults(),view.policy());
            assertEquals(AvailabilityCalendar.defaults(),view.calendar());assertTrue(view.booking(UUID.randomUUID()).isEmpty());
            assertEquals(first.get(),view.booking(first.get().id()).orElseThrow());
            assertEquals(List.of(first.get()),view.bookings(first.get().interval()));
            assertEquals(List.of(second.get()),view.bookings(second.get().interval()));
            assertThrows(UnsupportedOperationException.class,()->view.services().clear());
            assertThrows(UnsupportedOperationException.class,()->view.bookings(first.get().interval()).clear());return null;
        });
        assertEquals(LocalTime.of(10,30,15),load(first.get().id()).snapshot().intake().birthTime());
    }
    @Test void movesKeepRawSnapshotsAcrossServiceEditsAndLegacyDurations() {
        Booking original=create();UUID id=original.id();
        corrupt("booking_snapshot","UPDATE booking_snapshot SET document=jsonb_set(jsonb_set(document,'{policy,pendingLifetime}','86400'),'{policy,expiryBuffer}','3600') WHERE booking_id=?",id);
        String before=snapshotHash(id);
        jdbc.update("UPDATE service SET name='Synthetic edit',price_minor=2700,active=false WHERE id=?",original.snapshot().service().id());
        AtomicReference<Booking> moved=new AtomicReference<>();
        unit.execute(key(),tx->tx.attempt(()->{Booking value=tx.booking(id).orElseThrow().reschedule(tx.now(),original.start().plusSeconds(86400),false);tx.save(value);moved.set(value);return ok(value);}));
        assertEquals(moved.get(),load(id));assertEquals(original.expiresAt(),load(id).expiresAt());assertEquals(before,snapshotHash(id));
        assertEquals(1500,load(id).snapshot().service().price().minor());
        unit.execute(key(),tx->tx.attempt(()->{Booking same=tx.booking(id).orElseThrow();tx.save(same);return ok(same);}));
        assertEquals(before,snapshotHash(id));assertEquals(1,count("booking_occupancy"));
        assertThrows(Failure.class,()->unit.execute(key(),tx->tx.attempt(()->{tx.save(original);return ok(original);})));
    }
    @Test void immutableSnapshotIdentityAndVersionCannotBeSilentlyIgnored() {
        Booking pending=create();AtomicReference<Booking> confirmed=new AtomicReference<>();
        unit.execute(key(),tx->tx.attempt(()->{Booking b=tx.booking(pending.id()).orElseThrow().approve(tx.now(),Meeting.parse("https://zoom.us/j/123456789012",Set.of("zoom.us")),Booking.ConfirmationBasis.MANUAL_APPROVAL);tx.save(b);confirmed.set(b);return ok(b);}));
        Booking b=confirmed.get();UUID other=customer();
        var service=b.snapshot().service();var changed=new Booking.Snapshot(new ServiceDefinition(service.id(),service.version(),"Changed snapshot",service.durationMinutes(),service.price(),service.active()),b.snapshot().policy(),b.snapshot().intake(),b.snapshot().creationEmail(),b.snapshot().displayZone(),b.snapshot().privacyNoticeVersion());
        for(Booking invalid:List.of(copy(b,other,b.reference(),b.origin(),b.createdAt(),b.snapshot(),3),
                copy(b,b.customerId(),"changed-reference",b.origin(),b.createdAt(),b.snapshot(),3),
                copy(b,b.customerId(),b.reference(),Booking.Origin.ADMINISTRATOR,b.createdAt(),b.snapshot(),3),
                copy(b,b.customerId(),b.reference(),b.origin(),b.createdAt().minusSeconds(1),b.snapshot(),3),
                copy(b,b.customerId(),b.reference(),b.origin(),b.createdAt(),changed,3),
                copy(b,b.customerId(),b.reference(),b.origin(),b.createdAt(),b.snapshot(),4))) {
            assertThrows(Failure.class,()->unit.execute(key(),tx->tx.attempt(()->{tx.save(invalid);return ok(invalid);})));
        }
        assertEquals(b,load(b.id()));
    }
    @Test void administratorConfirmationAndCancellationUseCapturedReleaseTime() {
        AtomicReference<Booking> created=new AtomicReference<>();
        unit.execute(key(),tx->tx.attempt(()->{
            Booking p=pending(tx,tx.now().plusSeconds(259200));
            Booking b=Booking.confirmed(p.id(),p.reference(),customer,p.snapshot(),Booking.Origin.ADMINISTRATOR,tx.now(),p.start(),Meeting.parse("https://zoom.us/j/234567890123",Set.of("zoom.us")));
            tx.save(b);created.set(b);return ok(b);
        }));
        Booking b=load(created.get().id());assertNull(b.expiresAt());assertEquals(Booking.ConfirmationBasis.MANUAL_APPROVAL,b.confirmationBasis());
        AtomicReference<Instant> released=new AtomicReference<>();
        unit.execute(key(),tx->tx.attempt(()->{Booking cancelled=tx.booking(b.id()).orElseThrow().cancel(tx.now(),false);tx.save(cancelled);released.set(tx.now());return ok(cancelled);}));
        assertEquals(Booking.State.CANCELLED,load(b.id()).state());
        assertEquals(released.get(),jdbc.queryForObject("SELECT released_at FROM booking_occupancy WHERE booking_id=?",(row,n)->row.getTimestamp(1).toInstant(),b.id()));
        assertEquals(0,count("payment_entry"));
    }
    @Test void outcomesRetainFullReservationAndPendingBufferCeilingIsDerived() {
        for(Booking.State outcome:List.of(Booking.State.COMPLETED,Booking.State.NO_SHOW)) {
            UUID id=fixture.booking(Instant.parse("2026-10-10T10:00:00Z").plusSeconds(outcome==Booking.State.NO_SHOW?86400:0),"CONFIRMED",MigrationTest.snapshot());
            unit.execute(key(),tx->tx.attempt(()->{Booking b=tx.booking(id).orElseThrow().outcome(tx.now(),outcome,"");tx.save(b);return ok(b);}));
            assertEquals(outcome,load(id).state());assertEquals(120,Duration.between(load(id).start(),load(id).reservedEnd()).toMinutes());
            assertNull(jdbc.queryForObject("SELECT released_at FROM booking_occupancy WHERE booking_id=?",Timestamp.class,id));
        }
        unit.execute(key(),tx->tx.attempt(()->{Booking shortNotice=pending(tx,tx.now().plusSeconds(7200));tx.save(shortNotice);assertEquals(tx.now().plusSeconds(3600),shortNotice.expiresAt());return ok(shortNotice);}));
    }
    @Test void overlapAndFailedMoveLeaveNoPartialBookingSnapshotOrOccupancy() {
        Booking first=create();AtomicReference<Booking> second=new AtomicReference<>();
        unit.execute(key(),tx->tx.attempt(()->{Booking b=pending(tx,first.reservedEnd());tx.save(b);second.set(b);return ok(b);}));
        Receipt conflict=unit.execute(key(),tx->tx.attempt(()->{Booking b=pending(tx,first.start().plusSeconds(3600));tx.save(b);return ok(b);}));
        assertEquals(Code.SLOT_UNAVAILABLE,conflict.code());assertEquals(2,count("booking"));assertEquals(2,count("booking_snapshot"));assertEquals(2,count("booking_occupancy"));
        Receipt failedMove=unit.execute(key(),tx->tx.attempt(()->{Booking b=tx.booking(second.get().id()).orElseThrow().reschedule(tx.now(),first.start(),false);tx.save(b);return ok(b);}));
        assertEquals(Code.SLOT_UNAVAILABLE,failedMove.code());assertEquals(second.get(),load(second.get().id()));
    }
    @Test void malformedSnapshotsMissingChildrenAndRowDisagreementFailClosed() {
        Booking b=create();String original=jdbc.queryForObject("SELECT document::text FROM booking_snapshot WHERE booking_id=?",String.class,b.id());
        String invalidPrice=jdbc.queryForObject("SELECT jsonb_set(?::jsonb,'{service,price,minor}','-1')::text",String.class,original);
        for(String invalid:List.of("{}","null",invalidPrice,original.replace("1990-01-01","2100-01-01"))) {
            corrupt("booking_snapshot","UPDATE booking_snapshot SET document=?::jsonb WHERE booking_id=?",invalid,b.id());invalidRead(b.id());
        }
        corrupt("booking_snapshot","UPDATE booking_snapshot SET document=?::jsonb WHERE booking_id=?",original,b.id());
        corrupt("booking","UPDATE booking SET service_id='10000000-0000-0000-0000-000000000002' WHERE id=?",b.id());invalidRead(b.id());
        corrupt("booking","UPDATE booking SET service_id=? WHERE id=?",b.snapshot().service().id(),b.id());
        corrupt("booking","UPDATE booking SET expiry_ceiling=expiry_ceiling+interval '1 second' WHERE id=?",b.id());invalidRead(b.id());
        corrupt("booking","UPDATE booking SET expiry_ceiling=expiry_ceiling-interval '1 second' WHERE id=?",b.id());
        corrupt("booking_occupancy","DELETE FROM booking_occupancy WHERE booking_id=?",b.id());invalidRead(b.id());
        corrupt("booking_snapshot","DELETE FROM booking_snapshot WHERE booking_id=?",b.id());invalidRead(b.id());
    }
    @Test void meetingIdAgreementAndConfiguredHostValidationAreRequired() {
        UUID id=fixture.booking(MigrationTest.START,"CONFIRMED",MigrationTest.snapshot());
        String originalId=jdbc.queryForObject("SELECT meeting_id FROM booking WHERE id=?",String.class,id);
        String changedId=originalId.equals("123456789012")?"123456789013":"123456789012";
        jdbc.update("UPDATE booking SET meeting_id=? WHERE id=?",changedId,id);invalidRead(id);
        jdbc.update("UPDATE booking SET meeting_url=? WHERE id=?","https://meeting.example.test/j/"+changedId,id);invalidRead(id);
        Set<String> hosts=new HashSet<>(Set.of("meeting.example.test"));PostgresScheduling configured=new PostgresScheduling(source,hosts);hosts.clear();
        assertEquals(changedId,configured.read(v->v.booking(id).orElseThrow().meeting().meetingId()));
        assertThrows(IllegalArgumentException.class,()->new PostgresScheduling(source,Set.of()));
        assertThrows(IllegalArgumentException.class,()->new PostgresScheduling(source,null));
    }
    @Test void readOnlyAndEscapedOrCrossThreadContextsCannotWrite() throws Exception {
        AtomicReference<Transaction> escaped=new AtomicReference<>();
        unit.execute(key(),tx->{escaped.set(tx);return new Receipt(Code.OK,null,0);});
        assertThrows(Failure.class,()->escaped.get().attempt(()->new Receipt(Code.OK,null,0)));
        assertThrows(Failure.class,()->escaped.get().now());
        assertThrows(Failure.class,()->escaped.get().save(null));
        assertThrows(Failure.class,()->unit.read(view->{jdbc.update("UPDATE service SET name='Forbidden'");return null;}));
        unit.execute(key(),tx->{
            try(var executor=Executors.newSingleThreadExecutor()) {
                assertEquals(Failure.class,executor.submit(()->{try{tx.save(null);return Void.class;}catch(Failure expected){return expected.getClass();}}).get());
            } catch(Exception failure) { throw new AssertionError("CROSS_THREAD_GUARD_FAILED"); }
            return new Receipt(Code.OK,null,0);
        });
    }
    @Test void invalidPolicyCalendarPrecisionAndCreationAuthorityFailClosed() {
        jdbc.update("INSERT INTO policy_version(version,document) SELECT 2,jsonb_set(document,'{version}','99') FROM policy_version WHERE version=1");
        jdbc.update("UPDATE business_settings SET policy_version=2");assertThrows(Failure.class,()->unit.read(View::policy));
        jdbc.update("UPDATE business_settings SET policy_version=1,calendar='{}'");assertThrows(Failure.class,()->unit.read(View::calendar));
        unit.execute(key(),tx->{
            Booking normal=pending(tx,tx.now().plusSeconds(259200));
            Booking wrongCreated=Booking.pending(normal.id(),normal.reference(),customer,normal.snapshot(),normal.origin(),tx.now().minusSeconds(1),normal.start());
            assertThrows(Failure.class,()->tx.save(wrongCreated));
            Booking fractionalCreated=Booking.pending(normal.id(),normal.reference(),customer,normal.snapshot(),normal.origin(),tx.now().plusNanos(1),normal.start());
            assertThrows(Failure.class,()->tx.save(fractionalCreated));
            Booking nanos=pending(tx,normal.start().plusNanos(1));assertThrows(Failure.class,()->tx.save(nanos));
            Booking fractionalExpiry=new Booking(normal.id(),normal.reference(),normal.customerId(),normal.snapshot(),normal.deliveryEmail(),normal.state(),normal.origin(),
                    normal.createdAt(),normal.start(),normal.expiresAt().minusNanos(1),null,null,1,0,false);
            assertThrows(Failure.class,()->tx.save(fractionalExpiry));
            assertThrows(Failure.class,()->tx.save(copy(normal,customer,normal.reference(),normal.origin(),normal.createdAt(),normal.snapshot(),2)));
            return new Receipt(Code.OK,null,0);
        });
        assertEquals(0,count("booking"));
    }
}
