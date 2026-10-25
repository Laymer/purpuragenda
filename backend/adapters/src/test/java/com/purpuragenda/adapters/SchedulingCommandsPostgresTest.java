package com.purpuragenda.adapters;

import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.SchedulingCommands;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.Booking;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class SchedulingCommandsPostgresTest {
    ExpiryReclamationPostgresTest expiry;
    BookingPersistenceTest f;
    SchedulingCommands commands;
    @BeforeAll static void start(){MigrationTest.start();}
    @AfterAll static void stop(){MigrationTest.stop();}
    @BeforeEach void prepare(){expiry=new ExpiryReclamationPostgresTest();expiry.prepare();f=expiry.f;commands=new SchedulingCommands(f.unit);}
    @AfterEach void cleanup(){expiry.cleanup();}

    @Test void businessFailureCommitsExpiryAndReceiptButRollsBackAttemptedBookingAndCharge() {
        Booking due=expiry.due();expiry.receipt(due,100,"EUR");
        CommandKey key=f.key();
        Receipt result=commands.execute(key,tx->{
            Booking attempted=f.pending(tx,due.reservedEnd());tx.save(attempted);
            assertTrue(tx.chargeCreated(attempted));
            throw new DomainFailure("ACTIVE_LIMIT_REACHED");
        });
        assertEquals(Receipt.failure(Code.ACTIVE_LIMIT_REACHED),result);
        assertEquals(Booking.State.EXPIRED,f.load(due.id()).state());
        assertEquals(1,f.count("booking"));assertEquals(1,f.count("booking_snapshot"));
        assertEquals(0,f.count("request_charge"));assertEquals(0,f.count("customer_request_day"));
        assertEquals(1,f.count("refund_obligation"));assertEquals(1,f.count("audit_event"));assertEquals(1,f.count("outbox_event"));
        assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM booking_occupancy WHERE released_at IS NULL",Integer.class));
        assertEquals(result,commands.execute(key,tx->{throw new AssertionError("Business receipt must replay");}));
        assertEquals(1,f.count("outbox_event"));
    }

    @Test void unknownFailureRollsBackReclamationEffectsAndReceiptTogether() {
        Booking due=expiry.due();expiry.receipt(due,100,"EUR");
        int receipts=f.count("idempotency_record");
        DomainFailure unexpected=new DomainFailure("INVALID_SNAPSHOT");
        assertSame(unexpected,assertThrows(DomainFailure.class,()->commands.execute(f.key(),tx->{
            Booking attempted=f.pending(tx,due.reservedEnd());tx.save(attempted);tx.chargeCreated(attempted);throw unexpected;
        })));
        assertEquals(due,f.load(due.id()));assertEquals(receipts,f.count("idempotency_record"));
        assertEquals(1,f.count("booking"));assertEquals(0,f.count("request_charge"));
        assertEquals(1,f.count("payment_entry"));assertEquals(0,f.count("refund_obligation"));
        assertEquals(0,f.count("audit_event"));assertEquals(0,f.count("outbox_event"));
        assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM booking_occupancy WHERE released_at IS NULL",Integer.class));
    }

    @Test void databaseOverlapUsesTheSameBoundaryAndPreservesTheOriginalReservation() {
        AtomicReference<Booking> occupied=new AtomicReference<>();
        f.unit.execute(f.key(),tx->{Booking booking=f.pending(tx,tx.now().plusSeconds(259200));tx.save(booking);occupied.set(booking);return f.ok(booking);});
        Booking due=expiry.due(occupied.get().reservedEnd());
        Receipt result=commands.execute(f.key(),tx->{Booking conflict=f.pending(tx,occupied.get().start());tx.save(conflict);return f.ok(conflict);});
        assertEquals(Receipt.failure(Code.SLOT_UNAVAILABLE),result);
        assertEquals(occupied.get(),f.load(occupied.get().id()));assertEquals(Booking.State.EXPIRED,f.load(due.id()).state());
        assertEquals(2,f.count("booking"));assertEquals(1,f.count("outbox_event"));
    }

    @Test void concurrentFailureRetriesExecuteOnceAndChangedFingerprintCannotRepeatTheAttempt() throws Exception {
        Booking due=expiry.due();CommandKey key=f.key();AtomicInteger attempts=new AtomicInteger();
        try(ExecutorService workers=Executors.newFixedThreadPool(2)) {
            Callable<Receipt> work=()->commands.execute(key,tx->{attempts.incrementAndGet();throw new DomainFailure("DAILY_CAPACITY_REACHED");});
            Future<Receipt> first=workers.submit(work),second=workers.submit(work);
            assertEquals(Receipt.failure(Code.DAILY_CAPACITY_REACHED),first.get(30,TimeUnit.SECONDS));
            assertEquals(first.get(),second.get(30,TimeUnit.SECONDS));
        }
        assertEquals(1,attempts.get());assertEquals(Booking.State.EXPIRED,f.load(due.id()).state());
        assertEquals(1,f.count("outbox_event"));
        CommandKey changed=new CommandKey(key.scope(),key.key(),CommandKey.fingerprint(List.of("changed")));
        assertEquals(Receipt.failure(Code.IDEMPOTENCY_KEY_REUSED),commands.execute(changed,tx->{throw new AssertionError("No changed attempt");}));
    }

    @Test void oldReceiptFormatAndEveryNewFailureCodeReplayAfterStorageRoundtrip() {
        for(Code code:Code.values()) {
            CommandKey key=f.key();Receipt expected=code==Code.OK?new Receipt(Code.OK,null,0):Receipt.failure(code);
            assertEquals(expected,f.unit.execute(key,tx->expected));
            assertEquals(expected,commands.execute(key,tx->{throw new AssertionError("Stored code must replay");}));
        }
        assertEquals(Code.values().length,f.count("idempotency_record"));
    }
}
