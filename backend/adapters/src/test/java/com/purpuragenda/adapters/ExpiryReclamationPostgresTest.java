package com.purpuragenda.adapters;

import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.ExpiryReclamation;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.scheduling.SchedulingPolicy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class ExpiryReclamationPostgresTest {
    BookingPersistenceTest f;
    @BeforeAll static void start(){MigrationTest.start();}
    @AfterAll static void stop(){MigrationTest.stop();}
    @BeforeEach void prepare(){f=new BookingPersistenceTest();f.prepare();}
    @AfterEach void cleanup(){f.cleanup();}
    Receipt success(){return new Receipt(Code.OK,null,0);}
    Booking due() {return due(null);}
    Booking due(Instant start) {
        AtomicReference<Booking> value=new AtomicReference<>();
        f.unit.execute(f.key(),tx->{
            Booking original=f.pending(tx,start==null?tx.now().plusSeconds(259200):start);
            var p=original.snapshot().policy();
            var shortPolicy=new SchedulingPolicy(p.version(),p.gridMinutes(),p.dailyCapacity(),p.minimumNotice(),Duration.ofNanos(1000),p.expiryBuffer(),p.customerCutoff());
            var s=original.snapshot();var snapshot=new Booking.Snapshot(s.service(),shortPolicy,s.intake(),s.creationEmail(),s.displayZone(),s.privacyNoticeVersion());
            Booking booking=Booking.pending(original.id(),original.reference(),original.customerId(),snapshot,original.origin(),tx.now(),original.start());
            tx.save(booking);value.set(booking);return f.ok(booking);
        });return value.get();
    }
    UUID receipt(Booking booking,long amount,String currency) {
        UUID id=UUID.randomUUID();
        f.jdbc.update("INSERT INTO payment_entry(id,booking_id,kind,amount_minor,currency,method,reference,verified_at) VALUES(?,?,'RECEIPT',?,?,'SYNTHETIC',?,clock_timestamp())",
                id,booking.id(),amount,currency,UUID.randomUUID().toString());return id;
    }
    int reclaim() {
        int[] count={-1};f.unit.execute(f.key(),tx->{count[0]=ExpiryReclamation.reclaim(tx);return success();});return count[0];
    }
    @Test void expiryReleasesOccupancyAndCreatesInsertOnlyMultiCurrencyObligationsAndFixedEvents() {
        Booking booking=due();UUID[] ids=new UUID[2];
        f.unit.execute(f.key(),tx->{ids[0]=receipt(booking,100,"EUR");ids[1]=receipt(booking,200,"USD");
            f.jdbc.update("INSERT INTO payment_entry(id,booking_id,kind,amount_minor,currency,method,reference,verified_at,receipt_id) VALUES(?,?,'REFUND',40,'EUR','SYNTHETIC',?,clock_timestamp(),?)",
                    UUID.randomUUID(),booking.id(),UUID.randomUUID().toString(),ids[0]);return success();});
        assertEquals(1,reclaim());Booking expired=f.load(booking.id());
        assertEquals(Booking.State.EXPIRED,expired.state());assertEquals(booking.snapshot(),expired.snapshot());
        assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM booking_occupancy WHERE released_at IS NULL",Integer.class));
        assertEquals(List.of(100L,200L),f.jdbc.queryForList("SELECT due_minor FROM refund_obligation ORDER BY due_minor",Long.class));
        assertEquals(3,f.count("payment_entry"));assertEquals(1,f.count("outbox_event"));assertEquals(1,f.count("audit_event"));
        assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM audit_event a JOIN outbox_event o ON a.id=o.id JOIN booking_occupancy b ON b.booking_id=a.booking_id WHERE a.type='EXPIRED' AND a.actor_kind='SYSTEM' AND a.actor_id IS NULL AND a.reason='' AND a.version=? AND o.type='EXPIRED' AND o.version=a.version AND a.occurred_at=o.created_at AND o.created_at=b.released_at",Integer.class,expired.version()));
        assertEquals(0,reclaim());
        f.unit.execute(f.key(),tx->{tx.expiryEffects(expired.id(),expired.version());tx.expiryEffects(expired.id(),expired.version());return success();});
        assertEquals(2,f.count("refund_obligation"));assertEquals(1,f.count("outbox_event"));assertEquals(1,f.count("audit_event"));
    }
    @Test void expectedAttemptOverlapRollsBackAttemptButCommitsEarlierExpiryAndFinancialEffects() {
        Booking expired=due();AtomicReference<Booking> blocker=new AtomicReference<>();
        f.unit.execute(f.key(),tx->{Booking value=f.pending(tx,expired.reservedEnd());tx.save(value);blocker.set(value);receipt(expired,100,"EUR");return f.ok(value);});
        CommandKey key=f.key();
        Receipt result=f.unit.execute(key,tx->{
            assertEquals(1,ExpiryReclamation.reclaim(tx));
            return tx.attempt(()->{Booking conflict=f.pending(tx,blocker.get().start());tx.save(conflict);return f.ok(conflict);});
        });
        assertEquals(Code.SLOT_UNAVAILABLE,result.code());
        assertEquals(result,f.unit.execute(key,tx->{throw new AssertionError("Replay must not invoke reclamation or attempted mutation");}));
        assertEquals(Booking.State.EXPIRED,f.load(expired.id()).state());
        assertEquals(2,f.count("booking"));assertEquals(1,f.count("refund_obligation"));assertEquals(1,f.count("outbox_event"));
        assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM booking_occupancy WHERE released_at IS NULL",Integer.class));
    }
    @Test void dueQueryExcludesLiveHoldsAndReturnsImmutableOrderedBookings() {
        Booking first=due(),second=due(first.reservedEnd());AtomicReference<Booking> liveHold=new AtomicReference<>();
        f.unit.execute(f.key(),tx->{Booking live=f.pending(tx,second.reservedEnd());tx.save(live);liveHold.set(live);return f.ok(live);});
        f.unit.execute(f.key(),tx->{
            List<Booking> due=tx.duePending();
            assertEquals(List.of(first,second).stream().sorted(Comparator.comparing(b->b.id().toString())).toList(),due);
            assertFalse(due.contains(liveHold.get()));
            assertThrows(UnsupportedOperationException.class,due::clear);assertTrue(due.getFirst().expiresAt().compareTo(tx.now())<=0);
            return success();
        });
    }
    @Test void unexpectedFailureRollsBackExpiryOccupancyFinancialEffectsAndReceipt() {
        Booking booking=due();f.unit.execute(f.key(),tx->{receipt(booking,100,"EUR");return success();});
        int receipts=f.count("idempotency_record");
        assertThrows(IllegalStateException.class,()->f.unit.execute(f.key(),tx->{
            assertEquals(1,ExpiryReclamation.reclaim(tx));throw new IllegalStateException("SYNTHETIC_ABORT");
        }));
        assertEquals(Booking.State.PENDING_CONFIRMATION,f.load(booking.id()).state());
        assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM booking_occupancy WHERE released_at IS NULL",Integer.class));
        assertEquals(0,f.count("refund_obligation"));assertEquals(0,f.count("outbox_event"));assertEquals(0,f.count("audit_event"));
        assertEquals(receipts,f.count("idempotency_record"));assertEquals(1,f.count("payment_entry"));
    }
    @Test void effectGuardRejectsUnknownNonexpiredAndStaleVersionsWithoutPartialEffects() {
        Booking booking=due();
        for(UUID id:List.of(UUID.randomUUID(),booking.id()))
            assertEquals(Failure.Kind.STORAGE_FAILURE,assertThrows(Failure.class,()->f.unit.execute(f.key(),tx->{tx.expiryEffects(id,1);return success();})).kind());
        assertEquals(1,reclaim());
        assertThrows(Failure.class,()->f.unit.execute(f.key(),tx->{tx.expiryEffects(booking.id(),1);return success();}));
        assertEquals(1,f.count("outbox_event"));assertEquals(0,f.count("refund_obligation"));
    }
    @Test void receiptCommittedByEarlierResourceLockOwnerIsIncludedWhenExpiryAcquiresLock() throws Exception {
        Booking booking=due();CountDownLatch inserted=new CountDownLatch(1),release=new CountDownLatch(1);
        try(ExecutorService workers=Executors.newFixedThreadPool(2)) {
            Future<Receipt> writer=workers.submit(()->f.unit.execute(f.key(),tx->{receipt(booking,250,"EUR");inserted.countDown();
                try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError("SYNTHETIC_INTERRUPTED");}return success();}));
            assertTrue(inserted.await(10,TimeUnit.SECONDS));
            Future<Integer> expiry=workers.submit(this::reclaim);
            try {assertThrows(TimeoutException.class,()->expiry.get(100,TimeUnit.MILLISECONDS));}
            finally {release.countDown();}
            assertTrue(writer.get(10,TimeUnit.SECONDS).success());assertEquals(1,expiry.get(10,TimeUnit.SECONDS));
        }
        assertEquals(250L,f.jdbc.queryForObject("SELECT due_minor FROM refund_obligation",Long.class));
        assertEquals(1,f.count("payment_entry"));
    }
}
