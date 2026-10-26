package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.application.port.out.BookingChangesUnitOfWork;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.port.out.SessionStore;
import com.purpuragenda.application.scheduling.CustomerBookingChanges;
import com.purpuragenda.domain.booking.*;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL customer cancellation/rescheduling: authority, atomic effects, boundaries and independent-connection races. */
@Timeout(240)
@ResourceLock("postgres-migration-fixture")
class BookingChangesPostgresTest {
    final CustomerCreationPostgresTest c=new CustomerCreationPostgresTest();
    @TempDir Path directory;
    PostgresBookingChanges adapter;CustomerBookingChanges changes;
    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void setup() throws Exception {
        c.directory=directory;c.setup();adapter=new PostgresBookingChanges(c.f.source);changes=new CustomerBookingChanges(adapter);
    }
    @AfterEach void cleanup() { c.cleanup(); }

    UUID createFor(SessionStore.Snapshot session,String key,int day,int hour) {
        Receipt created=c.create(session,key,c.request(day,hour));assertTrue(created.success());return created.entityId();
    }
    void confirm(UUID id) {
        c.scheduling.execute(c.internal(),tx->tx.attempt(()->{
            Booking b=tx.booking(id).orElseThrow().approve(tx.now(),Meeting.parse("https://zoom.us/j/123456789",Set.of("zoom.us")),Booking.ConfirmationBasis.MANUAL_APPROVAL);
            tx.save(b);return new Receipt(Code.OK,id,b.version());
        }));
    }
    UUID receipt(UUID booking,long amount,String reference) {
        UUID id=UUID.randomUUID();
        c.jdbc.update("INSERT INTO payment_entry(id,booking_id,kind,amount_minor,currency,method,reference,verified_at) VALUES(?,?,'RECEIPT',?,'EUR','SYNTHETIC',?,clock_timestamp())",id,booking,amount,reference);
        return id;
    }
    Receipt cancel(SessionBinding who,UUID id,String key,long version) { return changes.cancel(who,id,key,new CustomerBookingChanges.Cancellation(version)); }
    Receipt move(SessionBinding who,UUID id,String key,long version,Instant start) { return changes.reschedule(who,id,key,new CustomerBookingChanges.Reschedule(version,1,start)); }
    SessionBinding bind(SessionStore.Snapshot s) { return c.binding(s); }
    Failure fail(org.junit.jupiter.api.function.Executable work) { return c.failure(work); }
    Instant start(int day,int hour) { return c.request(day,hour).start(); }
    int count(String sql) { return c.count(sql); }

    @Test void guestCancellationCommitsStateOccupancyAuditOutboxAndReplaysWithoutRepeatingEffects() {
        var session=c.guest("synthetic@example.test");UUID id=createFor(session,"create",3,9);Booking before=c.load(id);
        Receipt cancelled=cancel(bind(session),id,"cancel",before.version());
        assertEquals(new Receipt(Code.OK,id,before.version()+1),cancelled);Booking after=c.load(id);
        assertEquals(Booking.State.CANCELLED,after.state());assertEquals(before.snapshot(),after.snapshot());assertEquals(before.start(),after.start());
        assertNotNull(c.f.value("SELECT released_at FROM booking_occupancy WHERE booking_id=?",id));
        assertEquals(1,count("audit_event WHERE type='CANCELLED' AND actor_kind='GUEST'"));assertEquals(1,count("outbox_event WHERE type='CANCELLED'"));
        assertEquals(1,count("request_charge"));assertEquals(0,count("refund_obligation"));
        String manage=c.f.value("SELECT id FROM access_grant WHERE kind='MANAGE'");
        assertEquals(manage+"/CANCEL_BOOKING/"+id,c.f.value("SELECT scope FROM idempotency_record WHERE scope LIKE '%CANCEL_BOOKING%'"));
        assertEquals(cancelled,cancel(bind(session),id,"cancel",before.version()));
        assertEquals(1,count("outbox_event WHERE type='CANCELLED'"));
        assertEquals(Code.IDEMPOTENCY_KEY_REUSED,cancel(bind(session),id,"cancel",before.version()+5).code());
        assertEquals(Code.INVALID_TRANSITION,cancel(bind(session),id,"again",after.version()).code());
        assertEquals(1,count("outbox_event WHERE type='CANCELLED'"));
    }

    @Test void confirmedCancellationRecordsOnlyRemainingRefundObligationsAndNeverTouchesPaymentFacts() {
        var session=c.guest("synthetic@example.test");UUID id=createFor(session,"create",3,9);confirm(id);
        UUID full=receipt(id,1500,"synthetic-full"),partial=receipt(id,2000,"synthetic-partial"),settled=receipt(id,700,"synthetic-settled");
        c.jdbc.update("INSERT INTO payment_entry(id,booking_id,kind,amount_minor,currency,method,reference,verified_at,receipt_id) VALUES(?,?,'REFUND',500,'EUR','SYNTHETIC','synthetic-refund-1',clock_timestamp(),?)",UUID.randomUUID(),id,partial);
        c.jdbc.update("INSERT INTO payment_entry(id,booking_id,kind,amount_minor,currency,method,reference,verified_at,receipt_id) VALUES(?,?,'REFUND',700,'EUR','SYNTHETIC','synthetic-refund-2',clock_timestamp(),?)",UUID.randomUUID(),id,settled);
        Booking before=c.load(id);assertTrue(cancel(bind(session),id,"cancel",before.version()).success());
        assertEquals(before.calendarRevision()+1,c.load(id).calendarRevision());
        assertEquals(2,count("refund_obligation"));assertEquals(1500L,Long.valueOf(c.f.value("SELECT due_minor FROM refund_obligation WHERE receipt_id=?",full)));
        assertEquals(2000L,Long.valueOf(c.f.value("SELECT due_minor FROM refund_obligation WHERE receipt_id=?",partial)),"the obligation covers the whole receipt; the ledger subtracts the 500 already refunded");
        assertEquals("CUSTOMER_CANCELLED",c.f.value("SELECT reason FROM refund_obligation WHERE receipt_id=?",full));
        assertEquals(5,count("payment_entry"));
    }

    @Test void pendingAndConfirmedReschedulesMoveOccupancyKeepFactsAndQueueOneEvent() {
        var session=c.guest("synthetic@example.test");UUID pending=createFor(session,"p",3,9);Booking original=c.load(pending);
        Instant target=start(4,11);Receipt moved=move(bind(session),pending,"move",original.version(),target);
        assertEquals(new Receipt(Code.OK,pending,original.version()+1),moved);Booking after=c.load(pending);
        assertEquals(target,after.start());assertEquals(original.snapshot(),after.snapshot());assertEquals(original.expiresAt(),after.expiresAt());
        assertEquals(original.createdAt(),after.createdAt());assertEquals(0,after.calendarRevision());
        assertEquals(1,count("request_charge"));assertEquals(1,count("audit_event WHERE type='RESCHEDULED'"));assertEquals(1,count("outbox_event WHERE type='RESCHEDULED'"));
        assertTrue(c.f.value("SELECT metadata::text FROM audit_event WHERE type='RESCHEDULED'").contains("previousStart"));
        assertEquals(1,count("booking_occupancy WHERE released_at IS NULL"));assertEquals(moved,move(bind(session),pending,"move",original.version(),target));
        assertEquals(1,count("outbox_event WHERE type='RESCHEDULED'"));
        // The released original range is immediately reusable by another customer; the new range is not.
        assertTrue(c.create(c.guest("other@example.test"),"reuse",c.request(3,9)).success());
        assertEquals(Code.SLOT_UNAVAILABLE,c.create(c.guest("other@example.test"),"overlap",c.request(4,11)).code());
        confirm(pending);Booking confirmed=c.load(pending);receipt(pending,1500,"synthetic-kept");
        assertTrue(move(bind(session),pending,"confirmed-move",confirmed.version(),start(5,10)).success());
        assertEquals(confirmed.calendarRevision()+1,c.load(pending).calendarRevision());assertEquals(confirmed.meeting(),c.load(pending).meeting());
        assertEquals(1,count("payment_entry"));assertEquals(0,count("refund_obligation"));
    }

    @Test void unchangedStartIsAVersionPreservingNoOpThatStillStoresItsReceiptAndIgnoresStalePolicy() {
        var session=c.guest("synthetic@example.test");UUID id=createFor(session,"create",3,9);Booking before=c.load(id);
        Receipt first=changes.reschedule(bind(session),id,"same",new CustomerBookingChanges.Reschedule(before.version(),99,before.start()));
        assertEquals(new Receipt(Code.OK,id,before.version()),first);assertEquals(before,c.load(id));
        assertEquals(0,count("audit_event WHERE type='RESCHEDULED'"));assertEquals(0,count("outbox_event WHERE type='RESCHEDULED'"));
        assertEquals(first,changes.reschedule(bind(session),id,"same",new CustomerBookingChanges.Reschedule(before.version(),99,before.start())));
    }

    @Test void staleVersionAndPolicyReceiptsKeepEarlierExpiryReclamationButRollBackTheAttempt() {
        UUID due=c.f.database.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        var session=c.guest("synthetic@example.test");UUID id=createFor(session,"create",3,9);Booking before=c.load(id);
        assertEquals(Code.STALE_VERSION,cancel(bind(session),id,"stale",before.version()+1).code());
        assertEquals(Booking.State.EXPIRED,c.load(due).state());assertEquals(1,count("outbox_event WHERE type='EXPIRED'"));assertEquals(before,c.load(id));
        assertEquals(Code.STALE_VERSION,cancel(bind(session),id,"stale",before.version()+1).code());assertEquals(1,count("outbox_event WHERE type='EXPIRED'"));
        assertEquals(Code.POLICY_CHANGED,changes.reschedule(bind(session),id,"policy",new CustomerBookingChanges.Reschedule(before.version(),99,start(4,9))).code());
        assertEquals(before,c.load(id));assertEquals(0,count("outbox_event WHERE type='RESCHEDULED'"));
    }

    @Test void slotConflictRollsBackTheMoveButCommitsTheExpectedFailureReceipt() {
        var first=c.guest("one@example.test");var second=c.guest("two@example.test");UUID a=createFor(first,"a",3,9),b=createFor(second,"b",4,9);
        Booking before=c.load(b);Receipt result=move(bind(second),b,"conflict",before.version(),start(3,10));
        assertEquals(Code.SLOT_UNAVAILABLE,result.code());assertEquals(before,c.load(b));assertEquals(0,count("outbox_event WHERE type='RESCHEDULED'"));
        assertEquals(result,move(bind(second),b,"conflict",before.version(),start(3,10)));assertEquals(c.load(a).start(),start(3,9));
        assertEquals(2,count("booking_occupancy WHERE released_at IS NULL"));
    }

    @Test void uniformFailureForUnknownUnownedAndDifferentlyScopedBookingsWithoutTouchingAnything() {
        var owner=c.guest("owner@example.test");var other=c.guest("other@example.test");UUID mine=createFor(owner,"o",3,9),theirs=createFor(other,"t",4,9);
        UUID second=createFor(c.guest("owner@example.test"),"o2",5,9);Booking before=c.load(theirs);
        // The owner's creation session derives scope only for its first booking: even a second owned booking is outside it.
        for(UUID target:List.of(UUID.randomUUID(),theirs,second)) {
            assertEquals(Failure.Kind.FORBIDDEN,fail(()->cancel(bind(owner),target,"x",1)).kind());
            assertEquals(Failure.Kind.FORBIDDEN,fail(()->move(bind(owner),target,"x",1,start(6,9))).kind());
        }
        try(var anonymous=c.sessions.anonymous()) { assertEquals(Failure.Kind.FORBIDDEN,fail(()->cancel(bind(anonymous.session()),mine,"x",1)).kind()); }
        assertEquals(before,c.load(theirs));assertEquals(0,count("idempotency_record WHERE scope LIKE '%CANCEL_BOOKING%' OR scope LIKE '%RESCHEDULE_BOOKING%'"));
        assertEquals(0,count("outbox_event WHERE type IN ('CANCELLED','RESCHEDULED')"));
    }

    @Test void emailManagementGrantAuthorizesOnlyItsExactBookingAndIgnoresCallerFlags() {
        var owner=c.guest("synthetic@example.test");UUID first=createFor(owner,"a",3,9),second=createFor(c.guest("synthetic@example.test"),"b",4,9);
        var manager=c.guest("synthetic@example.test",second);
        assertEquals(Failure.Kind.FORBIDDEN,fail(()->cancel(bind(manager),first,"wrong",1)).kind());
        assertTrue(cancel(bind(manager),second,"right",1).success());assertEquals(Booking.State.CANCELLED,c.load(second).state());
        assertEquals(Booking.State.PENDING_CONFIRMATION,c.load(first).state());
        assertEquals(c.f.value("SELECT g.id FROM access_grant g WHERE g.kind='MANAGE' AND g.booking_id=? AND g.parent_grant_id IS NULL",second)+"/CANCEL_BOOKING/"+second,
                c.f.value("SELECT scope FROM idempotency_record WHERE scope LIKE '%CANCEL_BOOKING%'"));
    }

    @Test void accountOwnerMayChangeOwnBookingsAndAnotherAccountIsIndistinguishablyDenied() {
        var account=c.account();UUID id=c.command.create(account.binding(),"create",c.request(3,9)).entityId();Booking before=c.load(id);
        var rival=c.account("rival@example.test");
        assertEquals(Failure.Kind.FORBIDDEN,fail(()->cancel(rival.binding(),id,"x",before.version())).kind());
        assertTrue(move(account.binding(),id,"move",before.version(),start(4,9)).success());
        assertTrue(cancel(account.binding(),id,"cancel",before.version()+1).success());
        assertEquals(account.id()+"/CANCEL_BOOKING/"+id,c.f.value("SELECT scope FROM idempotency_record WHERE scope LIKE '%CANCEL_BOOKING%'"));
        assertEquals(2,count("audit_event WHERE actor_kind='ACCOUNT' AND type IN ('RESCHEDULED','CANCELLED')"));
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,fail(()->cancel(new SessionBinding(UUID.randomUUID(),account.binding().browserHash()),id,"x",1)).kind());
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,fail(()->cancel(new SessionBinding(account.binding().sessionId(),"0".repeat(64)),id,"x",1)).kind());
    }

    @ParameterizedTest @ValueSource(strings={"session","creation","derived"})
    void revokedAuthorityIsCheckedBeforeSuccessfulReceiptReplay(String target) {
        var session=c.guest("synthetic@example.test");UUID id=createFor(session,"create",3,9);Booking before=c.load(id);
        assertTrue(cancel(bind(session),id,"cancel",before.version()).success());
        if(target.equals("session"))c.sessions.revoke(session.reference());
        else c.jdbc.update("UPDATE access_grant SET revoked_at=clock_timestamp() WHERE "+(target.equals("creation")?"id=?":"parent_grant_id=?"),session.grantId());
        assertEquals(target.equals("session")?Failure.Kind.AUTHENTICATION_REQUIRED:Failure.Kind.FORBIDDEN,fail(()->cancel(bind(session),id,"cancel",before.version())).kind());
    }

    @Test void revokedEmailManagementGrantAndUnconsumedCreationGrantCannotChangeAnything() {
        var owner=c.guest("synthetic@example.test");UUID id=createFor(owner,"a",3,9);var manager=c.guest("synthetic@example.test",id);
        c.jdbc.update("UPDATE access_grant SET revoked_at=clock_timestamp() WHERE id=?",manager.grantId());
        assertEquals(Failure.Kind.FORBIDDEN,fail(()->cancel(bind(manager),id,"x",1)).kind());
        var fresh=c.guest("synthetic@example.test");assertEquals(Failure.Kind.FORBIDDEN,fail(()->cancel(bind(fresh),id,"x",1)).kind());
        assertEquals(Booking.State.PENDING_CONFIRMATION,c.load(id).state());
    }

    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void managementGrantExpiryIsStrictAtMicrosecondEquality(long micros) {
        var owner=c.guest("synthetic@example.test");UUID id=createFor(owner,"a",3,9);var manager=c.guest("synthetic@example.test",id);
        Instant expires=c.jdbc.queryForObject("SELECT expires_at FROM access_grant WHERE id=?",(r,n)->r.getTimestamp(1).toInstant(),manager.grantId());
        c.jdbc.update("UPDATE app_session SET last_seen_at=? WHERE id=?",Timestamp.from(expires.minusSeconds(1)),manager.reference().id());
        var reads=new AtomicInteger();var fixed=new CustomerBookingChanges(new PostgresBookingChanges(c.clock(expires.plusNanos(micros*1000),reads)));
        if(micros<0)assertTrue(fixed.cancel(bind(manager),id,"edge",new CustomerBookingChanges.Cancellation(1)).success());
        else assertEquals(Failure.Kind.FORBIDDEN,fail(()->fixed.cancel(bind(manager),id,"edge",new CustomerBookingChanges.Cancellation(1))).kind());
        assertEquals(1,reads.get());
    }

    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void idleSessionExpiryIsRecheckedBeforeAnyChangeOrReplay(long micros) {
        var session=c.guest("synthetic@example.test");UUID id=createFor(session,"a",3,9);
        Instant expires=c.jdbc.queryForObject("SELECT last_seen_at+interval '30 minutes' FROM app_session WHERE id=?",(r,n)->r.getTimestamp(1).toInstant(),session.reference().id());
        var fixed=new CustomerBookingChanges(new PostgresBookingChanges(c.clock(expires.plusNanos(micros*1000),new AtomicInteger())));
        var request=new CustomerBookingChanges.Cancellation(1);
        if(micros<0)assertTrue(fixed.cancel(bind(session),id,"edge",request).success());
        else assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,fail(()->fixed.cancel(bind(session),id,"edge",request)).kind());
        assertEquals(micros<0?Booking.State.CANCELLED:Booking.State.PENDING_CONFIRMATION,c.load(id).state());
    }

    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void inclusiveCustomerCutoffAppliesToBothCancellationAndRescheduling(long micros) {
        var account=c.account();UUID id=c.command.create(account.binding(),"create",c.request(5,9)).entityId();confirm(id);
        Booking booking=c.load(id);Instant captured=booking.start().minus(Duration.ofHours(24)).plusNanos(micros*1000);
        c.jdbc.update("UPDATE app_session SET issued_at=?,last_seen_at=?,authenticated_at=?,absolute_expires_at=?::timestamptz+interval '12 hours' WHERE id=?",
                Timestamp.from(captured.minusSeconds(60)),Timestamp.from(captured.minusSeconds(60)),Timestamp.from(captured.minusSeconds(60)),Timestamp.from(captured.minusSeconds(60)),account.binding().sessionId());
        var fixed=new CustomerBookingChanges(new PostgresBookingChanges(c.clock(captured,new AtomicInteger())));
        var reschedule=new CustomerBookingChanges.Reschedule(booking.version(),1,booking.start().plusSeconds(1800));
        Receipt moved=fixed.reschedule(account.binding(),id,"move",reschedule);
        if(micros<=0) {
            assertTrue(moved.success());assertTrue(fixed.cancel(account.binding(),id,"cancel",new CustomerBookingChanges.Cancellation(booking.version()+1)).success());
        } else {
            assertEquals(Code.CUTOFF_PASSED,moved.code());
            assertEquals(Code.CUTOFF_PASSED,fixed.cancel(account.binding(),id,"cancel",new CustomerBookingChanges.Cancellation(booking.version())).code());
            assertEquals(booking,c.load(id));
        }
    }

    @Test void credentialsIssuedAfterTheCapturedTimeCannotAuthorizeChanges() {
        var session=c.guest("synthetic@example.test");UUID guestBooking=createFor(session,"g",3,9);var account=c.account();
        UUID accountBooking=c.command.create(account.binding(),"a",c.request(4,9)).entityId();
        for(var target:List.of(Map.entry(bind(session),guestBooking),Map.entry(account.binding(),accountBooking))) {
            Instant issued=c.jdbc.queryForObject("SELECT issued_at FROM app_session WHERE id=?",(r,n)->r.getTimestamp(1).toInstant(),target.getKey().sessionId());
            var fixed=new CustomerBookingChanges(new PostgresBookingChanges(c.clock(issued.minusNanos(1000),new AtomicInteger())));
            assertThrows(Failure.class,()->fixed.cancel(target.getKey(),target.getValue(),"future",new CustomerBookingChanges.Cancellation(1)));
        }
        assertEquals(0,count("booking WHERE state='CANCELLED'"));
    }

    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void accountAbsoluteExpiryRejectsEqualityEvenWithRecentActivity(long micros) {
        var account=c.account();UUID id=c.command.create(account.binding(),"a",c.request(4,9)).entityId();
        Instant expires=c.jdbc.queryForObject("SELECT absolute_expires_at FROM app_session WHERE id=?",(r,n)->r.getTimestamp(1).toInstant(),account.binding().sessionId());
        c.jdbc.update("UPDATE app_session SET last_seen_at=? WHERE id=?",Timestamp.from(expires.minusSeconds(1)),account.binding().sessionId());
        var fixed=new CustomerBookingChanges(new PostgresBookingChanges(c.clock(expires.plusNanos(micros*1000),new AtomicInteger())));
        var request=new CustomerBookingChanges.Cancellation(1);
        if(micros<0)assertTrue(fixed.cancel(account.binding(),id,"edge",request).success());
        else assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,fail(()->fixed.cancel(account.binding(),id,"edge",request)).kind());
    }

    @Test void sessionCannotBorrowAnotherBrowsersGrantAndDerivedGrantSessionsRequireTheirLiveParent() {
        var session=c.guest("synthetic@example.test");UUID id=createFor(session,"a",3,9);String other="0".repeat(64);
        c.jdbc.update("UPDATE app_session SET browser_hash=? WHERE id=?",other,session.reference().id());
        assertEquals(Failure.Kind.FORBIDDEN,fail(()->cancel(new SessionBinding(session.reference().id(),other),id,"borrowed",1)).kind());
        c.jdbc.update("UPDATE app_session SET browser_hash=? WHERE id=?",session.reference().browserHash(),session.reference().id());
        UUID child=c.jdbc.queryForObject("SELECT id FROM access_grant WHERE parent_grant_id=?",UUID.class,session.grantId());
        c.jdbc.update("UPDATE app_session SET grant_id=? WHERE id=?",child,session.reference().id());
        assertTrue(cancel(bind(session),id,"derived",1).success());
        UUID second=createFor(c.guest("synthetic@example.test"),"b",4,9);UUID secondChild=c.jdbc.queryForObject("SELECT id FROM access_grant WHERE booking_id=? AND parent_grant_id IS NOT NULL",UUID.class,second);
        var next=c.guest("synthetic@example.test");c.jdbc.update("UPDATE app_session SET grant_id=? WHERE id=?",secondChild,next.reference().id());
        c.jdbc.update("UPDATE access_grant SET revoked_at=clock_timestamp() WHERE id=(SELECT parent_grant_id FROM access_grant WHERE id=?)",secondChild);
        assertEquals(Failure.Kind.FORBIDDEN,fail(()->cancel(bind(next),second,"revoked-parent",1)).kind());
    }

    @ParameterizedTest @ValueSource(strings={"audit_event","outbox_event","refund_obligation","booking_occupancy"})
    void unexpectedStorageFailureRollsBackTheMutationEffectsReceiptAndEarlierExpiryReclamation(String table) throws Exception {
        var session=c.guest("synthetic@example.test");UUID id=createFor(session,"create",3,9);confirm(id);receipt(id,1500,"synthetic-receipt");
        UUID due=c.f.database.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());Booking before=c.load(id);
        int audit=count("audit_event"),outbox=count("outbox_event");
        String body=switch(table) {
            case "refund_obligation" -> "RAISE EXCEPTION 'SYNTHETIC_FAILURE';";
            case "booking_occupancy" -> "IF NEW.booking_id='"+id+"' THEN RAISE EXCEPTION 'SYNTHETIC_FAILURE'; END IF;";
            default -> "IF NEW.type='CANCELLED' THEN RAISE EXCEPTION 'SYNTHETIC_FAILURE'; END IF;";
        };
        try(var connection=c.f.database.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(connection,"CREATE FUNCTION reject_change() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "+body+" RETURN NEW; END $$");
            MigrationTest.execute(connection,"CREATE TRIGGER reject_change BEFORE "+(table.equals("booking_occupancy")?"UPDATE":"INSERT")+" ON "+table+" FOR EACH ROW EXECUTE FUNCTION reject_change()");
        }
        assertEquals(Failure.Kind.STORAGE_FAILURE,fail(()->cancel(bind(session),id,"fail",before.version())).kind());
        assertEquals(before,c.load(id));assertEquals(Booking.State.PENDING_CONFIRMATION,c.load(due).state());
        assertEquals(audit,count("audit_event"));assertEquals(outbox,count("outbox_event"));assertEquals(0,count("refund_obligation"));
        assertEquals(0,count("idempotency_record WHERE scope LIKE '%CANCEL_BOOKING%'"));
    }

    @ParameterizedTest @ValueSource(strings={"no-effects","effects-without-save","double-effects","wrong-state","unchanged-start","foreign-receipt","never-authorized","forged-version","unauthorized-original","forged-original"})
    void everyCommittedVersionChangeMustCarryExactlyItsEffects(String misuse) {
        var session=c.guest("synthetic@example.test");UUID id=createFor(session,"create",3,9);Booking before=c.load(id);
        var command=new BookingChangesUnitOfWork.Command(bind(session),id,BookingChangesUnitOfWork.Operation.CANCEL,"misuse",CommandKey.fingerprint(List.of(misuse)));
        Function<BookingChangesUnitOfWork.Change,Receipt> work=tx->{
            if(misuse.equals("never-authorized"))return new Receipt(Code.OK,id,before.version());
            if(misuse.equals("unauthorized-original")){tx.cancelledEffects(before,before.cancel(tx.now(),true));return new Receipt(Code.OK,id,before.version()+1);}
            Booking original=tx.authorizedBooking();Booking cancelled=original.cancel(tx.now(),true);
            switch(misuse) {
                case "forged-original" -> {tx.save(cancelled);tx.cancelledEffects(original.deliveryContact("forged@example.test"),cancelled);}
                case "no-effects" -> tx.save(cancelled);
                case "effects-without-save" -> tx.cancelledEffects(original,cancelled);
                case "double-effects" -> {tx.save(cancelled);tx.cancelledEffects(original,cancelled);tx.cancelledEffects(original,cancelled);}
                case "wrong-state" -> {tx.save(cancelled);tx.rescheduledEffects(original,cancelled);}
                case "unchanged-start" -> {Booking contact=original.deliveryContact("delivery@example.test");tx.save(contact);tx.rescheduledEffects(original,contact);}
                case "foreign-receipt" -> {tx.save(cancelled);tx.cancelledEffects(original,cancelled);return new Receipt(Code.OK,UUID.randomUUID(),cancelled.version());}
                default -> {tx.save(cancelled);tx.cancelledEffects(original,cancelled);return new Receipt(Code.OK,id,cancelled.version()+1);}
            }
            return new Receipt(Code.OK,id,cancelled.version());
        };
        assertEquals(Failure.Kind.STORAGE_FAILURE,fail(()->adapter.change(command,work)).kind());
        assertEquals(before,c.load(id));assertEquals(0,count("outbox_event WHERE type='CANCELLED'"));assertEquals(0,count("idempotency_record WHERE scope LIKE '%CANCEL_BOOKING%'"));
    }

    @Test void storedSuccessForAnotherEntityAndMaintenanceModeFailClosed() {
        var session=c.guest("synthetic@example.test");UUID id=createFor(session,"create",3,9);assertTrue(cancel(bind(session),id,"cancel",1).success());
        c.jdbc.update("UPDATE idempotency_record SET receipt=jsonb_set(receipt,'{entityId}',to_jsonb(?::text)) WHERE scope LIKE '%CANCEL_BOOKING%'",UUID.randomUUID().toString());
        assertEquals(Failure.Kind.STORAGE_FAILURE,fail(()->cancel(bind(session),id,"cancel",1)).kind());
        c.jdbc.update("UPDATE operation_control SET maintenance=true");
        assertEquals(Failure.Kind.MAINTENANCE,fail(()->cancel(bind(session),id,"other",1)).kind());
    }

    @Test void revocationWhileWaitingForTheGrantLockCannotAuthorizeTheChange() throws Exception {
        var session=c.guest("synthetic@example.test");UUID id=createFor(session,"create",3,9);String name="change-lock-"+UUID.randomUUID();c.f.source.setApplicationName(name);
        try(var blocker=c.f.database.connection(MigrationTest.APP);var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);MigrationTest.execute(blocker,"SELECT id FROM access_grant WHERE id=? FOR UPDATE",session.grantId());
            var future=pool.submit(()->fail(()->cancel(bind(session),id,"revoked",1)));boolean waiting=false;long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(System.nanoTime()<limit) {
                if(c.jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock'",Integer.class,name)>0){waiting=true;break;}
                Thread.sleep(10);
            }
            assertTrue(waiting);MigrationTest.execute(blocker,"UPDATE access_grant SET revoked_at=clock_timestamp() WHERE id=?",session.grantId());blocker.commit();
            assertEquals(Failure.Kind.FORBIDDEN,future.get(10,TimeUnit.SECONDS).kind());
        }
        assertEquals(Booking.State.PENDING_CONFIRMATION,c.load(id).state());assertEquals(0,count("outbox_event WHERE type='CANCELLED'"));
    }

    @Test void twentySameKeyCancellationsAcrossTwoInstancesCommitOneChangeAndOneReceipt() throws Exception {
        var session=c.guest("synthetic@example.test");UUID id=createFor(session,"create",3,9);var other=new CustomerBookingChanges(new PostgresBookingChanges(c.f.source));
        var results=race(i->(i%2==0?changes:other).cancel(bind(session),id,"same",new CustomerBookingChanges.Cancellation(1)));
        assertEquals(1,new HashSet<>(results).size());assertTrue(results.getFirst().success());
        assertEquals(1,count("outbox_event WHERE type='CANCELLED'"));assertEquals(1,count("audit_event WHERE type='CANCELLED'"));
    }

    @Test void twentyDifferentKeysForOneBookingCannotCancelOrMoveItMoreThanOnce() throws Exception {
        var session=c.guest("synthetic@example.test");UUID id=createFor(session,"create",3,9);var other=new CustomerBookingChanges(new PostgresBookingChanges(c.f.source));
        var results=race(i->{var who=i%2==0?changes:other;
            return i<10?who.cancel(bind(session),id,"cancel-"+i,new CustomerBookingChanges.Cancellation(1)):who.reschedule(bind(session),id,"move-"+i,new CustomerBookingChanges.Reschedule(1,1,start(4,9).plusSeconds(1800L*(i-10))));});
        assertEquals(1,results.stream().filter(Receipt::success).count());
        assertEquals(1,count("outbox_event WHERE type IN ('CANCELLED','RESCHEDULED')"));
        assertTrue(results.stream().filter(r->!r.success()).allMatch(r->Set.of(Code.STALE_VERSION,Code.INVALID_TRANSITION).contains(r.code())));
    }

    @Test void twentyCustomersCannotMoveIntoTheSameRangeEvenAcrossTwoInstances() throws Exception {
        var sessions=new ArrayList<SessionStore.Snapshot>();var ids=new ArrayList<UUID>();
        for(int i=0;i<20;i++){var s=c.guest("customer"+i+"@example.test");sessions.add(s);ids.add(createFor(s,"create",3+i,9));}
        var other=new CustomerBookingChanges(new PostgresBookingChanges(c.f.source));Instant target=start(2,18);
        var results=race(i->(i%2==0?changes:other).reschedule(bind(sessions.get(i)),ids.get(i),"move",new CustomerBookingChanges.Reschedule(1,1,target)));
        assertEquals(1,results.stream().filter(Receipt::success).count());assertEquals(19,results.stream().filter(r->r.code()==Code.SLOT_UNAVAILABLE).count());
        assertEquals(1,count("booking WHERE state='PENDING_CONFIRMATION' AND version=2"));assertEquals(1,count("outbox_event WHERE type='RESCHEDULED'"));
        assertEquals(20,count("booking_occupancy WHERE released_at IS NULL"));
    }

    List<Receipt> race(IntFunctionWithReceipt work) throws Exception {
        var ready=new CountDownLatch(20);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<Future<Receipt>>();
            for(int i=0;i<20;i++){int index=i;futures.add(pool.submit(()->{ready.countDown();release.await();return work.apply(index);}));}
            assertTrue(ready.await(5,TimeUnit.SECONDS));release.countDown();var results=new ArrayList<Receipt>();for(var future:futures)results.add(future.get(60,TimeUnit.SECONDS));return results;
        }
    }
    interface IntFunctionWithReceipt { Receipt apply(int index) throws Exception; }
}
