package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.postgres.PostgresScheduling;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.*;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.scheduling.SchedulingPolicy;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class CustomerQuotaPostgresTest {
    BookingPersistenceTest f;
    @BeforeAll static void start(){MigrationTest.start();}
    @AfterAll static void stop(){MigrationTest.stop();}
    @BeforeEach void prepare(){f=new BookingPersistenceTest();f.prepare();}
    @AfterEach void cleanup(){f.cleanup();}
    Receipt ok(){return new Receipt(Code.OK,null,0);}
    Booking candidate(Transaction tx,int slot,Booking.Origin origin,boolean shortHold) {
        Booking base=f.pending(tx,tx.now().plusSeconds(259200L+slot*10800L));var snapshot=base.snapshot();
        if(shortHold){var p=snapshot.policy();snapshot=new Booking.Snapshot(snapshot.service(),new SchedulingPolicy(p.version(),p.gridMinutes(),p.dailyCapacity(),p.minimumNotice(),Duration.ofNanos(1000),p.expiryBuffer(),p.customerCutoff()),snapshot.intake(),snapshot.creationEmail(),snapshot.displayZone(),snapshot.privacyNoticeVersion());}
        return Booking.pending(base.id(),base.reference(),base.customerId(),snapshot,origin,tx.now(),base.start());
    }
    Booking create(int slot,Booking.Origin origin,boolean charge,boolean shortHold) {
        AtomicReference<Booking> result=new AtomicReference<>();
        f.unit.execute(f.key(),tx->tx.attempt(()->{Booking value=candidate(tx,slot,origin,shortHold);tx.save(value);
            if(charge)assertEquals(origin==Booking.Origin.CUSTOMER,tx.chargeCreated(value));result.set(value);return f.ok(value);}));return result.get();
    }
    CustomerQuota quota(){AtomicReference<CustomerQuota> q=new AtomicReference<>();f.unit.execute(f.key(),tx->{q.set(tx.quota(f.customer));return ok();});return q.get();}
    void cancel(Booking value){f.unit.execute(f.key(),tx->{tx.save(tx.booking(value.id()).orElseThrow().cancel(tx.now(),false));return ok();});}
    void legacy(){f.fixture.newSchema();f.fixture.migrate(7);Properties p=new Properties();p.setProperty("currentSchema",f.fixture.schema+",public");f.source.setConnectionProperties(p);f.customer=f.customer();}
    void rawCharge(Booking b,UUID customer,LocalDate day){f.jdbc.update("INSERT INTO request_charge VALUES(?,?,?,'CUSTOMER')",b.id(),customer,day);}
    void badShape(Booking b,UUID customer,LocalDate day) {
        try(Connection c=f.fixture.connection(MigrationTest.APP)){
            c.setAutoCommit(false);
            AssertionError failure=assertThrows(AssertionError.class,()->{
                MigrationTest.execute(c,"INSERT INTO customer_request_day VALUES(?,?,1) ON CONFLICT(customer_id,submission_day) DO UPDATE SET count=customer_request_day.count+1",customer,day);
                MigrationTest.execute(c,"INSERT INTO request_charge VALUES(?,?,?,'CUSTOMER')",b.id(),customer,day);
                MigrationTest.execute(c,"SET CONSTRAINTS ALL IMMEDIATE");
            });
            assertEquals("SQLSTATE=23514",failure.getMessage());c.rollback();
        }catch(SQLException failure){throw MigrationTest.safe(failure);}
    }
    @Test void immutableChargeUsesCreationDayStableIdentityAndIsIdempotentAcrossContactChanges() {
        Booking b=create(0,Booking.Origin.CUSTOMER,true,false);assertEquals(1,quota().requests());assertEquals(1,quota().active());
        assertEquals(CustomerQuota.dayAt(b.createdAt()),quota().submissionDay());
        f.unit.execute(f.key(),tx->{assertFalse(tx.chargeCreated(b));Booking changed=b.deliveryContact("changed@example.test");tx.save(changed);
            assertFalse(tx.chargeCreated(changed));return ok();});
        assertEquals(1,f.count("request_charge"));assertEquals(1,f.jdbc.queryForObject("SELECT count FROM customer_request_day",Integer.class));
        f.fixture.denied("23514","UPDATE request_charge SET submission_day=submission_day+1");
        f.fixture.denied("23514","DELETE FROM request_charge");
    }
    @Test void administratorOriginCountsActiveButNeverConsumesCustomerSubmissionAllowance() {
        Booking admin=create(0,Booking.Origin.ADMINISTRATOR,true,false);
        assertEquals(1,quota().active());assertEquals(0,quota().requests());assertEquals(0,f.count("request_charge"));
        cancel(admin);assertEquals(0,quota().active());
    }
    @Test void cancellationRejectionAndSynchronousExpiryNeverRefundSuccessfulRequestCharges() {
        Booking canceled=create(0,Booking.Origin.CUSTOMER,true,false);cancel(canceled);
        Booking rejected=create(1,Booking.Origin.CUSTOMER,true,false);
        f.unit.execute(f.key(),tx->{tx.save(tx.booking(rejected.id()).orElseThrow().reject());return ok();});
        create(2,Booking.Origin.CUSTOMER,true,true);
        f.unit.execute(f.key(),tx->{assertEquals(1,ExpiryReclamation.reclaim(tx));return ok();});
        assertEquals(0,quota().active());assertEquals(3,quota().requests());
        assertEquals("REQUEST_LIMIT_REACHED",assertThrows(DomainFailure.class,()->create(3,Booking.Origin.CUSTOMER,true,false)).code());
        assertEquals(3,f.count("booking"));assertEquals(3,f.count("request_charge"));
    }
    @Test void failedAttemptRollsBackBookingChargeAndCounterWhileEarlierChangesRemain() {
        f.unit.execute(f.key(),tx->{Booking discarded=candidate(tx,0,Booking.Origin.CUSTOMER,false);
            Receipt result=tx.attempt(()->{tx.save(discarded);assertTrue(tx.chargeCreated(discarded));return Receipt.failure(Code.SLOT_UNAVAILABLE);});
            assertEquals(Code.SLOT_UNAVAILABLE,result.code());assertEquals(0,tx.quota(f.customer).requests());return ok();});
        assertEquals(0,f.count("booking"));assertEquals(0,f.count("request_charge"));assertEquals(0,f.count("customer_request_day"));
    }
    @Test void usageMatchesExactPendingExpiryAndOngoingConfirmedSessionEndBoundaries() {
        UUID id=f.fixture.booking(Instant.now().minusSeconds(1800),"CONFIRMED",MigrationTest.snapshot());
        f.unit.execute(f.key(),tx->{
            Instant boundary=tx.now().minusSeconds(1800);
            f.jdbc.update("UPDATE booking SET customer_id=?,start_at=?,session_end=?,reserved_end=?,business_date=? WHERE id=?",f.customer,Timestamp.from(boundary),Timestamp.from(tx.now()),Timestamp.from(boundary.plusSeconds(7200)),CustomerQuota.dayAt(boundary),id);
            f.jdbc.update("UPDATE booking_occupancy SET occupied=tstzrange(?,?,'[)') WHERE booking_id=?",Timestamp.from(boundary),Timestamp.from(boundary.plusSeconds(7200)),id);
            assertEquals(0,tx.quota(f.customer).active()); // sessionEnd == captured now
            Instant start=tx.now().minusSeconds(900);
            f.jdbc.update("UPDATE booking SET start_at=?,session_end=?,reserved_end=?,business_date=? WHERE id=?",Timestamp.from(start),Timestamp.from(start.plusSeconds(1800)),Timestamp.from(start.plusSeconds(7200)),CustomerQuota.dayAt(start),id);
            f.jdbc.update("UPDATE booking_occupancy SET occupied=tstzrange(?,?,'[)') WHERE booking_id=?",Timestamp.from(start),Timestamp.from(start.plusSeconds(7200)),id);
            assertEquals(1,tx.quota(f.customer).active());
            for(String state:List.of("COMPLETED","NO_SHOW")){f.jdbc.update("UPDATE booking SET state=? WHERE id=?",state,id);assertEquals(0,tx.quota(f.customer).active());}
            Booking pending=candidate(tx,0,Booking.Origin.CUSTOMER,false);tx.save(pending);
            f.jdbc.update("UPDATE booking SET created_at=?,expires_at=?,expiry_ceiling=? WHERE id=?",Timestamp.from(tx.now().minusSeconds(3600)),Timestamp.from(tx.now()),Timestamp.from(tx.now().plusSeconds(82800)),pending.id());
            assertEquals(0,tx.quota(f.customer).active());
            f.jdbc.update("UPDATE booking SET expires_at=? WHERE id=?",Timestamp.from(tx.now().plusNanos(1000)),pending.id());
            assertEquals(1,tx.quota(f.customer).active());return ok();
        });
    }
    @Test void unknownIdentityUnsavedStaleBookingAndCorruptCountersFailClosed() {
        assertThrows(Failure.class,()->f.unit.execute(f.key(),tx->{tx.quota(UUID.randomUUID());return ok();}));
        assertThrows(Failure.class,()->f.unit.execute(f.key(),tx->{tx.chargeCreated(candidate(tx,0,Booking.Origin.CUSTOMER,false));return ok();}));
        Booking b=create(0,Booking.Origin.CUSTOMER,true,false);
        f.unit.execute(f.key(),tx->{tx.save(b.deliveryContact("changed@example.test"));return ok();});
        assertThrows(Failure.class,()->f.unit.execute(f.key(),tx->{tx.chargeCreated(b);return ok();}));
        Booking uncharged=create(1,Booking.Origin.CUSTOMER,false,false);
        assertThrows(Failure.class,()->f.unit.execute(f.key(),tx->{tx.chargeCreated(uncharged);return ok();}));
        assertThrows(Failure.class,()->f.unit.execute(f.key(),tx->{
            Booking changed=candidate(tx,2,Booking.Origin.CUSTOMER,false);tx.save(changed);changed=changed.deliveryContact("changed@example.test");
            tx.save(changed);tx.chargeCreated(changed);return ok();
        }));
        f.corrupt("customer_request_day","UPDATE customer_request_day SET count=0");
        assertThrows(Failure.class,this::quota);
    }
    @Test void twentyIndependentConnectionsCompeteForExactlyOneRemainingSubmission() throws Exception {
        cancel(create(0,Booking.Origin.CUSTOMER,true,false));cancel(create(1,Booking.Origin.CUSTOMER,true,false));
        var source=new DriverManagerDataSource(MigrationTest.jdbcUrl,MigrationTest.APP,MigrationTest.appPassword);
        Properties p=new Properties();p.setProperty("currentSchema",f.fixture.schema+",public");p.setProperty("ApplicationName","p09_quota_race");source.setConnectionProperties(p);
        var unit=new PostgresScheduling(source);List<Future<Boolean>> results=new ArrayList<>();
        try(Connection blocker=f.fixture.connection(MigrationTest.APP);ExecutorService workers=Executors.newFixedThreadPool(20)) {
            blocker.setAutoCommit(false);MigrationTest.execute(blocker,"SELECT id FROM scheduling_resource WHERE id=1 FOR UPDATE");
            for(int i=0;i<20;i++){int slot=i+2;results.add(workers.submit(()->{boolean[] won={false};
                unit.execute(f.key(),tx->{if(tx.quota(f.customer).requests()==3)return ok();
                    return tx.attempt(()->{Booking value=candidate(tx,slot,Booking.Origin.CUSTOMER,false);tx.save(value);won[0]=tx.chargeCreated(value);return f.ok(value);});});return won[0];}));}
            try {
                long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();int waiting=0;
                while(System.nanoTime()<deadline){waiting=f.jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE application_name='p09_quota_race' AND wait_event_type='Lock' AND query LIKE 'SELECT id FROM scheduling_resource%FOR UPDATE'",Integer.class);if(waiting==20)break;Thread.sleep(10);}
                assertEquals(20,waiting,"Every worker must reach the actual resource lock before release");
            } finally {blocker.commit();}
            int winners=0;for(Future<Boolean> result:results)if(result.get(10,TimeUnit.SECONDS))winners++;
            assertEquals(1,winners);
        }
        assertEquals(3,quota().requests());assertEquals(1,quota().active());assertEquals(3,f.count("booking"));assertEquals(3,f.count("request_charge"));
    }
    @Test void validUpgradePreservesExistingChargesAndUnchargedHistoricalBookings() {
        legacy();Booking b=create(0,Booking.Origin.CUSTOMER,true,false);create(1,Booking.Origin.CUSTOMER,false,false);
        String snapshot=f.snapshotHash(b.id());f.fixture.migrate(0);f.fixture.migrate(0);
        assertEquals(8,f.fixture.scalar(MigrationTest.MIGRATOR,"SELECT count(*) FROM databasechangelog"));
        assertEquals(snapshot,f.snapshotHash(b.id()));assertEquals(2,f.count("booking"));assertEquals(1,quota().requests());
    }
    @Test void upgradeRejectsMalformedHistoricalChargeIdentityDateOriginAndCounters() {
        for(String defect:List.of("identity","date","origin","missing-counter","counter","overlimit")){
            legacy();Booking b=create(0,Booking.Origin.CUSTOMER,false,false);LocalDate day=CustomerQuota.dayAt(b.createdAt());
            if(defect.equals("identity")){UUID other=f.customer();rawCharge(b,other,day);f.jdbc.update("INSERT INTO customer_request_day VALUES(?,?,1)",other,day);}
            else if(defect.equals("date")){rawCharge(b,f.customer,day.minusDays(1));f.jdbc.update("INSERT INTO customer_request_day VALUES(?,?,1)",f.customer,day.minusDays(1));}
            else if(defect.equals("origin")){f.jdbc.update("UPDATE booking SET origin='ADMINISTRATOR' WHERE id=?",b.id());rawCharge(b,f.customer,day);f.jdbc.update("INSERT INTO customer_request_day VALUES(?,?,1)",f.customer,day);}
            else if(defect.equals("missing-counter"))rawCharge(b,f.customer,day);
            else if(defect.equals("overlimit")){
                rawCharge(b,f.customer,day);
                for(int slot=1;slot<4;slot++)rawCharge(create(slot,Booking.Origin.CUSTOMER,false,false),f.customer,day);
                f.jdbc.update("INSERT INTO customer_request_day VALUES(?,?,4)",f.customer,day);
            } else f.jdbc.update("INSERT INTO customer_request_day VALUES(?,?,1)",f.customer,day);
            assertEquals("MIGRATION_FAILED",assertThrows(AssertionError.class,()->f.fixture.migrate(0)).getMessage());
            assertEquals(7,f.fixture.scalar(MigrationTest.MIGRATOR,"SELECT count(*) FROM databasechangelog"));
        }
    }
    @Test void deferredConsistencyAllowsEitherInsertOrderAndRejectsTamperedCommitOrRekey() {
        Booking first=create(0,Booking.Origin.CUSTOMER,false,false),second=create(1,Booking.Origin.CUSTOMER,false,false);
        LocalDate day=CustomerQuota.dayAt(first.createdAt());
        for(Booking b:List.of(first,second))try(Connection c=f.fixture.connection(MigrationTest.APP)){
            c.setAutoCommit(false);
            if(b==first){MigrationTest.execute(c,"INSERT INTO request_charge VALUES(?,?,?,'CUSTOMER')",b.id(),f.customer,day);MigrationTest.execute(c,"INSERT INTO customer_request_day VALUES(?,?,1)",f.customer,day);}
            else {MigrationTest.execute(c,"UPDATE customer_request_day SET count=2 WHERE customer_id=?",f.customer);MigrationTest.execute(c,"INSERT INTO request_charge VALUES(?,?,?,'CUSTOMER')",b.id(),f.customer,day);}
            c.commit();
        }catch(SQLException failure){throw MigrationTest.safe(failure);}
        assertEquals(2,quota().requests());
        Booking uncharged=create(2,Booking.Origin.CUSTOMER,false,false),admin=create(3,Booking.Origin.ADMINISTRATOR,false,false);
        badShape(uncharged,f.customer(),day);badShape(uncharged,f.customer,day.plusDays(1));badShape(admin,f.customer,day);
        f.fixture.denied("23514","UPDATE customer_request_day SET count=1");
        f.fixture.denied("23514","DELETE FROM customer_request_day");
        f.fixture.denied("23514","UPDATE customer_request_day SET submission_day=submission_day+1");
        f.fixture.denied("23514","UPDATE booking SET customer_id=? WHERE id=?",f.customer(),first.id());
        f.fixture.denied("23514","UPDATE booking SET origin='ADMINISTRATOR' WHERE id=?",first.id());
        f.fixture.denied("23514","UPDATE booking SET created_at=created_at-interval '1 day' WHERE id=?",first.id());
    }
}
