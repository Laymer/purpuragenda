package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.NotesUnitOfWork.*;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.AdministratorNotes;
import com.purpuragenda.application.scheduling.AdministratorNotes.Addition;
import com.purpuragenda.application.scheduling.SchedulingCommands;
import com.purpuragenda.domain.booking.Booking;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(240)
@ResourceLock("postgres-migration-fixture")
class NotesPostgresTest {
    static final String TEXT="Synthetic private note";
    final AdminBookingPostgresTest b=new AdminBookingPostgresTest();
    @TempDir Path directory;
    CustomerCreationPostgresTest c;PostgresNotes unit;AdministratorNotes notes;UUID booking;
    @BeforeAll static void start(){MigrationTest.start();}
    @AfterAll static void stop(){MigrationTest.stop();}
    @BeforeEach void setup() throws Exception {
        b.directory=directory;b.setup();c=b.c;unit=new PostgresNotes(c.f.source,Set.of("zoom.us"));notes=new AdministratorNotes(unit);
        booking=b.create("synthetic-create",b.request(3,10)).entityId();assertNotNull(booking);
    }
    @AfterEach void cleanup(){b.cleanup();}
    Receipt append(String key){return notes.append(b.session,booking,key,new Addition(TEXT));}
    Command command(String key){return new Command(b.session,booking,key,CommandKey.fingerprint(List.of(TEXT)));}
    int count(String table){return c.count(table);}
    Booking booking(){return c.scheduling.read(tx->tx.booking(booking).orElseThrow());}

    @Test void appendsOnePrivateNoteWithTextFreeAuditAndUnchangedBookingFacts() {
        Booking original=booking();int outbox=count("outbox_event");
        Receipt result=append("synthetic-note");assertEquals(1,result.version());assertNotEquals(booking,result.entityId());assertEquals(original,booking());
        assertEquals(TEXT,c.jdbc.queryForObject("SELECT note FROM booking_note WHERE id=?",String.class,result.entityId()));
        assertEquals(b.adminId,c.jdbc.queryForObject("SELECT actor_id FROM booking_note WHERE id=?",UUID.class,result.entityId()));
        assertEquals(1,count("audit_event WHERE type='NOTE_ADDED' AND reason='' AND metadata='{}'::jsonb"));
        assertEquals(1,count("booking_note n JOIN audit_event a ON a.id=n.id WHERE n.created_at=a.occurred_at"));
        assertEquals(outbox,count("outbox_event"));assertEquals(0,count("payment_entry"));assertEquals(0,count("request_charge"));
        assertEquals(result,append("synthetic-note"));assertEquals(1,count("booking_note"));
        assertEquals(Receipt.failure(Code.IDEMPOTENCY_KEY_REUSED),notes.append(b.session,booking,"synthetic-note",new Addition("Synthetic other note")));
    }
    @Test void scopesReplayToPersistedAdministratorAndBookingInsteadOfSession() {
        Receipt first=append("same-key");SessionBinding second=b.session(b.adminId,0);
        assertEquals(first,notes.append(second,booking,"same-key",new Addition(TEXT)));
        UUID secondBooking=b.create("second-booking",b.request(4,10)).entityId();
        assertNotEquals(first,notes.append(b.session,secondBooking,"same-key",new Addition(TEXT)));
        c.jdbc.update("UPDATE admin_user SET enabled=false WHERE id=?",b.adminId);
        SessionBinding other=b.adminSession(UUID.randomUUID(),true);
        assertNotEquals(first,notes.append(other,booking,"same-key",new Addition(TEXT)));assertEquals(3,count("booking_note"));
    }
    @Test void freshAuthorityIsCheckedBeforeReplayAndMissingBookingsAreSafe() {
        append("note");
        c.jdbc.update("UPDATE app_session SET issued_at=issued_at-interval '602 seconds',absolute_expires_at=absolute_expires_at-interval '602 seconds',authenticated_at=issued_at-interval '601 seconds' WHERE id=?",b.session.sessionId());
        assertEquals(Failure.Kind.FRESH_AUTHENTICATION_REQUIRED,b.fail(()->append("note")).kind());
        c.jdbc.update("UPDATE admin_user SET security_version=security_version+1 WHERE id=?",b.adminId);
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,b.fail(()->append("note")).kind());
        b.session=b.session(b.adminId,1);
        assertEquals(Failure.Kind.NOT_FOUND,b.fail(()->notes.append(b.session,UUID.randomUUID(),"missing",new Addition(TEXT))).kind());
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,b.fail(()->notes.append(new SessionBinding(b.session.sessionId(),"f".repeat(64)),booking,"note",new Addition(TEXT))).kind());
        assertEquals(1,count("booking_note"));
    }
    @Test void disabledAdministratorCannotObserveItsOldReceipt() {
        append("note");c.jdbc.update("UPDATE admin_user SET enabled=false WHERE id=?",b.adminId);
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,b.fail(()->append("note")).kind());assertEquals(1,count("booking_note"));
    }
    @Test void oneCapturedClockReclaimsExpiryBeforeAppendingWithoutAnotherBookingMutation() {
        Booking original=booking();Instant time=original.expiresAt();AtomicInteger reads=new AtomicInteger();
        c.jdbc.update("UPDATE app_session SET issued_at=?,last_seen_at=?,authenticated_at=?,absolute_expires_at=? WHERE id=?",
                Timestamp.from(time.minusSeconds(60)),Timestamp.from(time.minusSeconds(60)),Timestamp.from(time.minusSeconds(60)),Timestamp.from(time.minusSeconds(60).plus(Duration.ofHours(8))),b.session.sessionId());
        var fixed=new AdministratorNotes(new PostgresNotes(c.clock(time,reads),Set.of("zoom.us")));
        Receipt result=fixed.append(b.session,booking,"expiry",new Addition(TEXT));assertEquals(1,reads.get());assertEquals(1,result.version());
        assertEquals(Booking.State.EXPIRED,booking().state());assertEquals(original.version()+1,booking().version());
        assertEquals(time,c.jdbc.queryForObject("SELECT created_at FROM booking_note",Timestamp.class).toInstant());
        assertEquals(1,count("audit_event WHERE type='EXPIRED'"));assertEquals(1,count("outbox_event WHERE type='EXPIRED'"));
    }
    @Test void twentyIndependentConnectionsCommitOneNoteAndOneReceipt() throws Exception {
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch gate=new CountDownLatch(1);var jobs=new ArrayList<Future<Receipt>>();
            for(int i=0;i<20;i++)jobs.add(pool.submit(()->{gate.await();return append("race");}));gate.countDown();
            Receipt first=jobs.getFirst().get(30,TimeUnit.SECONDS);for(var job:jobs)assertEquals(first,job.get(30,TimeUnit.SECONDS));
        }
        assertEquals(1,count("booking_note"));assertEquals(1,count("audit_event WHERE type='NOTE_ADDED'"));assertEquals(1,count("idempotency_record WHERE key='race'"));
    }
    @Test void badCallbacksCannotWriteBookingsOrCommitMismatchedNoteEffects() {
        List<Function<Notes,Receipt>> invalid=List.of(
            tx->new Receipt(Code.OK,UUID.randomUUID(),1),
            tx->{tx.authorizedBooking();return new Receipt(Code.OK,UUID.randomUUID(),1);},
            tx->{tx.noteAdded(UUID.randomUUID(),TEXT);return new Receipt(Code.OK,UUID.randomUUID(),1);},
            tx->{tx.authorizedBooking();tx.authorizedBooking();return new Receipt(Code.OK,UUID.randomUUID(),1);},
            tx->{tx.authorizedBooking();tx.noteAdded(null,TEXT);return new Receipt(Code.OK,UUID.randomUUID(),1);},
            tx->{tx.authorizedBooking();tx.noteAdded(UUID.randomUUID(),"");return new Receipt(Code.OK,UUID.randomUUID(),1);},
            tx->{tx.authorizedBooking();tx.noteAdded(UUID.randomUUID()," "+TEXT);return new Receipt(Code.OK,UUID.randomUUID(),1);},
            tx->{tx.authorizedBooking();tx.noteAdded(UUID.randomUUID(),"Synthetic other");return new Receipt(Code.OK,UUID.randomUUID(),1);},
            tx->{tx.authorizedBooking();UUID id=UUID.randomUUID();tx.noteAdded(id,TEXT);tx.noteAdded(UUID.randomUUID(),TEXT);return new Receipt(Code.OK,id,1);},
            tx->{tx.authorizedBooking();UUID id=UUID.randomUUID();tx.noteAdded(id,TEXT);return new Receipt(Code.OK,id,2);},
            tx->{tx.authorizedBooking();tx.noteAdded(UUID.randomUUID(),TEXT);return new Receipt(Code.OK,UUID.randomUUID(),1);},
            tx->{tx.authorizedBooking();tx.noteAdded(UUID.randomUUID(),TEXT);return Receipt.failure(Code.STALE_VERSION);},
            tx->{tx.save(tx.authorizedBooking().reject());return new Receipt(Code.OK,booking,1);},
            tx->{tx.chargeCreated(tx.authorizedBooking());return new Receipt(Code.OK,booking,1);},
            tx->{tx.expiryEffects(booking,1);return new Receipt(Code.OK,booking,1);});
        Booking original=booking();
        for(int i=0;i<invalid.size();i++) {var callback=invalid.get(i);String key="bad-"+i;
            assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->unit.append(command(key),tx->SchedulingCommands.within(tx,()->callback.apply(tx)))).kind());
        }
        assertEquals(original,booking());assertEquals(0,count("booking_note"));assertEquals(0,count("audit_event WHERE type='NOTE_ADDED'"));assertEquals(0,count("idempotency_record WHERE key LIKE 'bad-%'"));
    }
    @Test void callbacksCannotBypassAttemptsOrForgeExpiryReclamation() {
        List<Function<Notes,Receipt>> invalid=List.of(
            tx->{tx.authorizedBooking();return new Receipt(Code.OK,booking,1);},
            tx->{tx.noteAdded(UUID.randomUUID(),TEXT);return new Receipt(Code.OK,booking,1);},
            tx->{tx.save(tx.booking(booking).orElseThrow().reject());return new Receipt(Code.OK,booking,1);},
            tx->{tx.save(tx.booking(booking).orElseThrow());return new Receipt(Code.OK,booking,1);},
            tx->{tx.expiryEffects(booking,1);return new Receipt(Code.OK,booking,1);},
            tx->{tx.attempt(()->Receipt.failure(Code.STALE_VERSION));return tx.attempt(()->Receipt.failure(Code.STALE_VERSION));},
            tx->new Receipt(Code.OK,booking,1));
        for(int i=0;i<invalid.size();i++){var callback=invalid.get(i);String key="bypass-"+i;assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->unit.append(command(key),callback)).kind());}
        assertEquals(0,count("booking_note"));assertEquals(0,count("idempotency_record WHERE key LIKE 'bypass-%'"));
    }
    @Test void cachedReceiptsMustNameAnExistingNoteOfTheSameActorAndBooking() {
        Receipt original=append("cached");Receipt other=notes.append(b.session,booking,"other-note",new Addition("Synthetic different private note"));String scope=new Scope(b.adminId,"ADD_NOTE",booking).storageKey();
        for(String receipt:List.of("{\"code\":\"OK\",\"entityId\":\""+other.entityId()+"\",\"version\":1}","{\"code\":\"OK\",\"entityId\":\""+original.entityId()+"\",\"version\":2}",
                "{\"code\":\"OK\",\"entityId\":\""+UUID.randomUUID()+"\",\"version\":1}","{\"code\":\"STALE_VERSION\",\"entityId\":null,\"version\":0}")) {
            c.jdbc.update("UPDATE idempotency_record SET receipt=?::jsonb WHERE scope=? AND key='cached'",receipt,scope);
            assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->append("cached")).kind());
        }
        assertEquals(2,count("booking_note"));
    }
    @Test void suppressedDatabaseWritesCannotCommitPartialNotesOrReceipts() throws Exception {
        try(var connection=c.f.database.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(connection,"CREATE FUNCTION suppress_note() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$");
            for(String table:List.of("booking_note","audit_event")) {
                MigrationTest.execute(connection,"CREATE TRIGGER suppress_note BEFORE INSERT ON "+table+" FOR EACH ROW EXECUTE FUNCTION suppress_note()");
                assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->append("suppressed-"+table)).kind());
                assertEquals(0,count("booking_note"));assertEquals(0,count("audit_event WHERE type='NOTE_ADDED'"));
                assertEquals(0,count("idempotency_record WHERE key LIKE 'suppressed-%'"));
                MigrationTest.execute(connection,"DROP TRIGGER suppress_note ON "+table);
            }
        }
    }

    @Test void databaseAuditFailureRollsBackNoteAndReceiptAndMaintenanceBlocksReplay() throws Exception {
        Receipt first=append("existing");
        try(var connection=c.f.database.connection(MigrationTest.MIGRATOR)) {
        MigrationTest.execute(connection,"CREATE FUNCTION fail_note_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.type='NOTE_ADDED' THEN RAISE EXCEPTION 'SYNTHETIC_FAILURE'; END IF; RETURN NEW; END $$");
        MigrationTest.execute(connection,"CREATE TRIGGER fail_note_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION fail_note_audit()");
        }
        assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->append("failed")).kind());assertEquals(1,count("booking_note"));assertEquals(0,count("idempotency_record WHERE key='failed'"));
        assertEquals(first,append("existing"));c.jdbc.update("UPDATE operation_control SET maintenance=true WHERE id=1");
        assertEquals(Failure.Kind.MAINTENANCE,b.fail(()->append("existing")).kind());
    }
}
