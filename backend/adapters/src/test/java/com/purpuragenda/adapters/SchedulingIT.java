package com.purpuragenda.adapters;

import com.purpuragenda.application.port.in.Actor;
import com.purpuragenda.application.usecase.BookingService;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork;
import com.purpuragenda.adapters.out.postgres.PostgresScheduling;
import com.purpuragenda.adapters.codec.Json;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.customer.Intake;
import com.purpuragenda.domain.payment.PaymentLedger;
import com.purpuragenda.domain.scheduling.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import liquibase.integration.spring.SpringLiquibase;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Explicit PG_TEST_URL permits a real isolated PostgreSQL test DB when Docker is unavailable.
 * By default this suite starts the pinned PostgreSQL image with Testcontainers; never skips. */
class SchedulingIT {
    static PostgreSQLContainer container;
    DriverManagerDataSource source;JdbcTemplate jdbc;SchedulingUnitOfWork uow;BookingService service;Actor admin;
    String schema;Instant now;UUID karmic=UUID.fromString("10000000-0000-0000-0000-000000000001");
    @BeforeEach void prepare()throws Exception{
        String url=System.getenv("PG_TEST_URL"),user=System.getenv().getOrDefault("PG_TEST_USER","postgres"),password=System.getenv().getOrDefault("PG_TEST_PASSWORD","");
        if(url==null){if(container==null){container=new PostgreSQLContainer("postgres:18.6-bookworm@sha256:bbf83594798e144bff365a9c191c5d779f181fb3135902064ab8ff11918020fc").withDatabaseName("purpuragenda_test");container.start();}url=container.getJdbcUrl();user=container.getUsername();password=container.getPassword();}
        assertTrue(url.matches("jdbc:postgresql://[^/]+/purpuragenda_test(?:\\?.*)?"),"Only purpuragenda_test database is allowed");
        schema="test_"+UUID.randomUUID().toString().replace("-","");
        var base=new JdbcTemplate(new DriverManagerDataSource(url,user,password));base.execute("CREATE EXTENSION IF NOT EXISTS btree_gist");base.execute("CREATE SCHEMA "+schema);
        source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema+",public",user,password);jdbc=new JdbcTemplate(source);
        SpringLiquibase migrations=new SpringLiquibase();migrations.setDataSource(source);migrations.setDefaultSchema(schema);migrations.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");migrations.afterPropertiesSet();
        uow=new PostgresScheduling(jdbc,new DataSourceTransactionManager(source));service=new BookingService(uow);admin=new Actor(UUID.randomUUID(),Actor.Kind.ADMINISTRATOR,null,null);now=jdbc.queryForObject("SELECT clock_timestamp()",(r,n)->r.getTimestamp(1).toInstant());
    }
    @AfterEach void cleanup(){if(jdbc!=null)jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
    @AfterAll static void shutdown(){if(container!=null)container.stop();}
    Actor account(){UUID customer=UUID.randomUUID();jdbc.update("INSERT INTO customer_identity(id,email_key,verified_at) VALUES(?,?,clock_timestamp())",customer,customer+"@example.test");return new Actor(UUID.randomUUID(),Actor.Kind.ACCOUNT,customer,null);}
    Instant start(int day,int hour){return now.atZone(SchedulingPolicy.BUSINESS_ZONE).toLocalDate().plusDays(day).atTime(hour,0).atZone(SchedulingPolicy.BUSINESS_ZONE).toInstant();}
    BookingService.Request request(Actor actor,Instant start,boolean adminConfirmed){return new BookingService.Request(karmic,1,1,start,"Europe/Brussels",new Intake("Alex","Example","+32400123456",LocalDate.of(1990,1,1),LocalTime.of(9,30),"Synthetic city","Synthetic test"),"local-draft",actor.customerId(),adminConfirmed,adminConfirmed?Meeting.parse("https://zoom.us/j/"+(10000000000L+Math.abs(actor.customerId().getLeastSignificantBits()%80000000000L)),Set.of("zoom.us")):null,SchedulingRules.Override.none());}
    SchedulingUnitOfWork.Receipt create(Actor actor,Instant start){return service.create(actor,UUID.randomUUID().toString(),"a".repeat(64),request(actor,start,false));}
    @Test void seedMigrationRepeatsWithoutResettingEdits()throws Exception{
        assertEquals(2,service.services().size());jdbc.update("UPDATE service SET name='Edited name' WHERE id=?",karmic);
        SpringLiquibase migrations=new SpringLiquibase();migrations.setDataSource(source);migrations.setDefaultSchema(schema);migrations.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");migrations.afterPropertiesSet();
        assertEquals("Edited name",service.services().getFirst().name());assertEquals(6,jdbc.queryForObject("SELECT count(*) FROM databasechangelog",Integer.class));
    }
    @Test void sameSlotTwentyRequestsAcrossIndependentAdaptersHaveOneWinner()throws Exception{
        List<Actor> actors=new ArrayList<>();for(int i=0;i<20;i++)actors.add(account());
        BookingService second=new BookingService(new PostgresScheduling(new JdbcTemplate(source),new DataSourceTransactionManager(source)));
        var results=race(20,i->(i%2==0?service:second).create(actors.get(i),UUID.randomUUID().toString(),"b".repeat(64),request(actors.get(i),start(3,15),false)));
        assertEquals(1,results.stream().filter(SchedulingUnitOfWork.Receipt::success).count());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM booking_occupancy WHERE released_at IS NULL",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM request_charge",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM outbox_event",Integer.class));
    }
    @Test void identicalConcurrentKeysReplayAndDifferentPayloadCannotReuse()throws Exception{
        Actor actor=account();String key=UUID.randomUUID().toString();var request=request(actor,start(3,15),false);
        var results=race(20,i->service.create(actor,key,"c".repeat(64),request));
        assertTrue(results.stream().allMatch(SchedulingUnitOfWork.Receipt::success));assertEquals(1,results.stream().map(SchedulingUnitOfWork.Receipt::bookingId).distinct().count());
        assertEquals("IDEMPOTENCY_KEY_REUSED",assertThrows(com.purpuragenda.domain.DomainFailure.class,()->service.create(actor,key,"d".repeat(64),request)).code());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM request_charge",Integer.class));
    }
    @Test void occupiedMoveRollsBackAndValidMoveKeepsOriginalSnapshotAndCharge(){
        Actor first=account(),other=account();var a=create(first,start(3,15));var b=create(other,start(4,15));assertTrue(a.success());assertTrue(b.success());
        var failed=service.reschedule(first,a.bookingId(),1,UUID.randomUUID().toString(),"e".repeat(64),start(4,15),1,SchedulingRules.Override.none(),"");assertEquals("SLOT_UNAVAILABLE",failed.code());
        assertEquals(start(3,15),service.get(first,a.bookingId()).booking().start());
        var moved=service.reschedule(first,a.bookingId(),1,UUID.randomUUID().toString(),"f".repeat(64),start(5,15),1,SchedulingRules.Override.none(),"");assertTrue(moved.success());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM request_charge WHERE booking_id=?",Integer.class,a.bookingId()));
        assertEquals(1500,service.get(first,a.bookingId()).booking().snapshot().service().price().minor());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM booking_occupancy WHERE booking_id=?",Integer.class,a.bookingId()));
    }
    @Test void finalActiveAndSubmissionAllowancesEachHaveOneWinner()throws Exception{
        Actor actor=account();
        for(int d=3;d<=4;d++){var request=request(actor,start(d,9),false);assertTrue(service.create(admin,UUID.randomUUID().toString(),"1".repeat(64),request).success());}
        var active=race(20,i->create(actor,start(5+i,9)));assertEquals(1,active.stream().filter(SchedulingUnitOfWork.Receipt::success).count());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM request_charge",Integer.class));
        // A distinct identity with two successful but cancelled requests isolates submission accounting.
        Actor submitter=account();for(int d=3;d<=4;d++){var created=create(submitter,start(d,12));assertTrue(created.success());assertTrue(service.cancel(admin,created.bookingId(),1,UUID.randomUUID().toString(),"2".repeat(64),"PROVIDER","Synthetic cancellation").success());}
        var submission=race(20,i->create(submitter,start(5+i,12)));assertEquals(1,submission.stream().filter(SchedulingUnitOfWork.Receipt::success).count());
        assertEquals(3,jdbc.queryForObject("SELECT count FROM customer_request_day WHERE customer_id=?",Integer.class,submitter.customerId()));
    }
    @Test void completedCountsAndDirectOverlapWritesAreRejected(){
        Actor a=account(),b=account();var first=create(a,start(3,9));var second=create(b,start(3,12));assertTrue(first.success());assertTrue(second.success());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE booking_occupancy SET occupied=tstzrange(?,?,'[)') WHERE booking_id=?",Timestamp.from(start(3,9)),Timestamp.from(start(3,11)),second.bookingId()));
        assertEquals(start(3,12),service.get(b,second.bookingId()).booking().start());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE booking SET session_end=session_end+interval '1 minute' WHERE id=?",first.bookingId()));
    }
    @Test void validPaymentSurvivesIneligibleConfirmationAndWrongCurrencyNeverPays(){
        Actor actor=account();var created=create(actor,start(3,15));UUID id=created.bookingId();
        var paid=service.payment(admin,id,1,UUID.randomUUID().toString(),"3".repeat(64),new Money(1500,"USD"),"BANK","USD-001",now,false,null);assertTrue(paid.success());
        assertEquals(PaymentLedger.Status.UNPAID,service.get(actor,id).payment().status());
        var missingMeeting=service.payment(admin,id,1,UUID.randomUUID().toString(),"4".repeat(64),Money.eur(1500),"BANK","EUR-001",now,true,null);assertTrue(missingMeeting.success());assertEquals("MEETING_REQUIRED",missingMeeting.warning());
        assertEquals(Booking.State.PENDING_CONFIRMATION,service.get(actor,id).booking().state());assertEquals(1500,service.get(actor,id).payment().receivedMinor());
        var approval=service.approve(admin,id,1,UUID.randomUUID().toString(),"5".repeat(64),Meeting.parse("https://zoom.us/j/98765432101",Set.of("zoom.us")));assertTrue(approval.success(),approval.code()+":"+approval.warning());
        var duplicate=service.payment(admin,id,2,UUID.randomUUID().toString(),"6".repeat(64),Money.eur(1500),"BANK","EUR-001",now,false,null);assertEquals("DUPLICATE_PAYMENT",duplicate.code());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM payment_entry",Integer.class));
    }
    @Test void emailProofIsBrowserBoundSingleUseAndDoesNotEnrollAnAccount()throws Exception{
        java.nio.file.Path key=java.nio.file.Files.createTempFile("purpuragenda-test-",".key");java.nio.file.Files.write(key,new byte[32]);
        try{
            var secrets=new com.purpuragenda.adapters.out.security.Secrets(key,"test");var identities=new com.purpuragenda.adapters.out.security.PostgresIdentity(jdbc,new DataSourceTransactionManager(source),secrets,"http://localhost:8080");
            identities.challenge("proof@example.test","CREATE",null,"synthetic-browser","synthetic-ip");
            String envelope=jdbc.queryForObject("SELECT encrypted_envelope FROM notification_job WHERE template='AUTH'",String.class);Map<?,?> message=Json.read(secrets.decrypt(envelope),Map.class);String token=message.get("url").toString().split("token=")[1];
            assertThrows(com.purpuragenda.domain.DomainFailure.class,()->identities.redeem(token,"other-browser"));
            var identity=identities.redeem(token,"synthetic-browser");assertEquals(Actor.Kind.CREATION_GRANT,identity.kind());
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM customer_account",Integer.class));
            assertThrows(com.purpuragenda.domain.DomainFailure.class,()->identities.redeem(token,"synthetic-browser"));
            var created=create(identity.actor(),start(3,15));assertTrue(created.success(),created.code());
            var scoped=identities.validate(identity).orElseThrow();assertEquals(Actor.Kind.MANAGEMENT_GRANT,scoped.kind());assertEquals(created.bookingId(),scoped.bookingId());
            assertThrows(com.purpuragenda.domain.DomainFailure.class,()->service.list(scoped.actor()));
            assertEquals("RATE_LIMITED",assertThrows(com.purpuragenda.domain.DomainFailure.class,()->identities.challenge("proof@example.test","ACCOUNT",null,"synthetic-browser","synthetic-ip-2")).code());
        }
        finally{java.nio.file.Files.deleteIfExists(key);}
    }
    @Test void explicitEnrollmentKeepsTheSameVerifiedIdentityAndSecurityTokensAreHashed()throws Exception{
        java.nio.file.Path key=java.nio.file.Files.createTempFile("purpuragenda-test-",".key");java.nio.file.Files.write(key,new byte[32]);
        try{
            var secrets=new com.purpuragenda.adapters.out.security.Secrets(key,"test");var identities=new com.purpuragenda.adapters.out.security.PostgresIdentity(jdbc,new DataSourceTransactionManager(source),secrets,"http://localhost:8080");
            Actor existing=account();String email=jdbc.queryForObject("SELECT email_key FROM customer_identity WHERE id=?",String.class,existing.customerId());
            identities.challenge(email,"ACCOUNT",null,"browser","ip");String envelope=jdbc.queryForObject("SELECT encrypted_envelope FROM notification_job",String.class);String token=Json.read(secrets.decrypt(envelope),Map.class).get("url").toString().split("token=")[1];
            assertNotEquals(token,jdbc.queryForObject("SELECT token_hash FROM email_challenge",String.class));assertFalse(envelope.contains(token));
            var enrolled=identities.redeem(token,"browser");assertEquals(Actor.Kind.ACCOUNT,enrolled.kind());assertEquals(existing.customerId(),enrolled.customerId());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM customer_account",Integer.class));identities.revoke(enrolled);assertTrue(identities.validate(enrolled).isEmpty());
        }finally{java.nio.file.Files.deleteIfExists(key);}
    }
    BookingService at(Instant time){return new BookingService(new PostgresScheduling(jdbc,new DataSourceTransactionManager(source),Clock.fixed(time,ZoneOffset.UTC)));}
    @Test void synchronousExpiryAtEqualityReleasesInventoryButKeepsChargeAndMoney(){
        Instant time=Instant.parse("2027-01-10T09:00:00Z"),target=Instant.parse("2027-01-12T14:30:00Z");Actor first=account(),second=account();var before=at(time);var original=before.create(first,UUID.randomUUID().toString(),"a".repeat(64),request(first,target,false));assertTrue(original.success());
        assertTrue(before.payment(admin,original.bookingId(),1,UUID.randomUUID().toString(),"b".repeat(64),Money.eur(1500),"BANK","EXPIRY-PAID",time,false,null).success());
        var equality=at(time.plusSeconds(86400));var replacement=equality.create(second,UUID.randomUUID().toString(),"c".repeat(64),request(second,target,false));assertTrue(replacement.success(),replacement.code());
        assertEquals(Booking.State.EXPIRED,equality.get(first,original.bookingId()).booking().state());assertEquals(PaymentLedger.Status.REFUND_DUE,equality.get(first,original.bookingId()).payment().status());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM request_charge WHERE booking_id=?",Integer.class,original.bookingId()));
        assertEquals("HOLD_EXPIRED",equality.approve(admin,original.bookingId(),1,UUID.randomUUID().toString(),"d".repeat(64),Meeting.parse("https://zoom.us/j/11111111111",Set.of("zoom.us"))).code());
    }
    @Test void completedBookingRetainsDailyCapacityAndRemainingOccupiedInterval(){
        Instant time=Instant.parse("2027-01-10T09:00:00Z");var before=at(time);List<UUID> ids=new ArrayList<>();
        for(int hour:List.of(8,11,14)){Actor owner=account();Instant target=Instant.parse("2027-01-12T00:00:00Z").plusSeconds(hour*3600L);var created=before.create(owner,UUID.randomUUID().toString(),"1".repeat(64),request(owner,target,false));assertTrue(created.success());ids.add(created.bookingId());assertTrue(before.approve(admin,created.bookingId(),1,UUID.randomUUID().toString(),"2".repeat(64),Meeting.parse("https://zoom.us/j/"+(20000000000L+hour),Set.of("zoom.us"))).success());}
        var after=at(Instant.parse("2027-01-12T08:00:00Z"));assertTrue(after.outcome(admin,ids.getFirst(),2,UUID.randomUUID().toString(),"3".repeat(64),Booking.State.COMPLETED,"Recorded early by owner").success());
        var policy=new SchedulingPolicy(2,30,3,Duration.ZERO,Duration.ofHours(24),Duration.ofHours(1),Duration.ofHours(24));assertTrue(after.configuration(admin,UUID.randomUUID().toString(),"4".repeat(64),1,policy,AvailabilityCalendar.defaults(),"Synthetic notice adjustment").success());
        Actor fourth=account();var base=request(fourth,Instant.parse("2027-01-12T17:00:00Z"),false);var changed=new BookingService.Request(base.serviceId(),1,2,base.startInstant(),base.displayZone(),base.intake(),base.privacyNoticeVersion(),null,false,null,SchedulingRules.Override.none());
        assertEquals("DAILY_CAPACITY_REACHED",after.create(fourth,UUID.randomUUID().toString(),"5".repeat(64),changed).code());
        assertTrue(after.cancel(admin,ids.get(1),2,UUID.randomUUID().toString(),"6".repeat(64),"PROVIDER","Synthetic provider cancellation").success());assertTrue(after.create(fourth,UUID.randomUUID().toString(),"7".repeat(64),changed).success());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM booking_occupancy WHERE booking_id=? AND released_at IS NULL AND upper(occupied)=?",Integer.class,ids.getFirst(),Timestamp.from(Instant.parse("2027-01-12T10:00:00Z"))));
    }
    @Test void cutoffAndRefundEligibilityAreInclusiveToTheMillisecond(){
        Instant created=Instant.parse("2027-01-10T09:00:00Z");var before=at(created);Actor actor=account();List<UUID> ids=new ArrayList<>();
        for(int day=12;day<=14;day++){Instant target=Instant.parse("2027-01-"+day+"T14:30:00Z");var b=before.create(actor,UUID.randomUUID().toString(),"a".repeat(64),request(actor,target,false));assertTrue(b.success());ids.add(b.bookingId());assertTrue(before.approve(admin,b.bookingId(),1,UUID.randomUUID().toString(),"b".repeat(64),Meeting.parse("https://zoom.us/j/"+(30000000000L+day),Set.of("zoom.us"))).success());assertTrue(before.payment(admin,b.bookingId(),2,UUID.randomUUID().toString(),"c".repeat(64),Money.eur(1500),"BANK","CUTOFF-"+day,created,false,null).success());}
        for(int index=0;index<3;index++){UUID id=ids.get(index);Instant target=Instant.parse("2027-01-"+(12+index)+"T14:30:00Z");var clock=at(target.minusSeconds(86400).plusMillis(index-1));var result=clock.cancel(actor,id,2,UUID.randomUUID().toString(),"d".repeat(64),"CUSTOMER","");assertEquals(index<2,result.success());assertEquals(index<2?PaymentLedger.Status.REFUND_DUE:PaymentLedger.Status.PAID_EXTERNAL,clock.get(actor,id).payment().status());}
    }
    @Test void contactProofChangesDeliveryOnlyAndNeverTransfersOwnershipOrQuota()throws Exception{
        java.nio.file.Path key=java.nio.file.Files.createTempFile("purpuragenda-contact-",".key");java.nio.file.Files.write(key,new byte[32]);
        try{Actor actor=account();var created=create(actor,start(3,15));var original=service.get(actor,created.bookingId()).booking();var secrets=new com.purpuragenda.adapters.out.security.Secrets(key,"test");var identities=new com.purpuragenda.adapters.out.security.PostgresIdentity(jdbc,new DataSourceTransactionManager(source),secrets,"http://localhost:8080");
            identities.contactChallenge(created.bookingId(),"new-contact@example.test","browser","ip");String encrypted=jdbc.queryForObject("SELECT encrypted_envelope FROM notification_job WHERE template='AUTH'",String.class);String token=Json.read(secrets.decrypt(encrypted),Map.class).get("url").toString().split("contactToken=")[1];
            var result=service.confirmContact(actor,created.bookingId(),1,UUID.randomUUID().toString(),"f".repeat(64),com.purpuragenda.adapters.out.security.Secrets.hash(token),com.purpuragenda.adapters.out.security.Secrets.hash("browser"));assertTrue(result.success());Booking updated=service.get(actor,created.bookingId()).booking();assertEquals("new-contact@example.test",updated.deliveryEmail());assertEquals(original.customerId(),updated.customerId());assertEquals(original.snapshot(),updated.snapshot());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM request_charge WHERE booking_id=?",Integer.class,created.bookingId()));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM customer_identity",Integer.class));
            assertEquals("INVALID_TOKEN",service.confirmContact(actor,created.bookingId(),2,UUID.randomUUID().toString(),"e".repeat(64),com.purpuragenda.adapters.out.security.Secrets.hash(token),com.purpuragenda.adapters.out.security.Secrets.hash("browser")).code());
        }finally{java.nio.file.Files.deleteIfExists(key);}
    }
    @Test void deliveryLeaseIsSerializedRenewableAndRejectsStaleAcknowledgement()throws Exception{
        Actor actor=account();var created=create(actor,start(3,15));UUID first=UUID.randomUUID(),second=UUID.randomUUID();for(UUID id:List.of(first,second))jdbc.update("INSERT INTO notification_job(id,booking_id,audience,template,version,due_at) VALUES(?,?,'CUSTOMER','REQUESTED',1,clock_timestamp())",id,created.bookingId());
        java.nio.file.Path key=java.nio.file.Files.createTempFile("purpuragenda-delivery-",".key");java.nio.file.Files.write(key,new byte[32]);try{
            var queue=new com.purpuragenda.adapters.out.email.PostgresNotifications(jdbc,new DataSourceTransactionManager(source),uow,new com.purpuragenda.adapters.out.security.Secrets(key,"test"),new com.purpuragenda.adapters.out.calendar.IcalendarWriter(),"http://localhost","owner@example.test","CAPTURE");
            var old=queue.claim().orElseThrow();assertTrue(queue.claim().isEmpty());assertTrue(queue.renew(old));assertFalse(queue.prepare(old).orElseThrow().text().contains("Synthetic city"));
            jdbc.update("UPDATE notification_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",old.id());var fresh=queue.claim().orElseThrow();assertEquals(old.id(),fresh.id());assertNotEquals(old.leaseToken(),fresh.leaseToken());assertFalse(queue.renew(old));queue.delivered(old);assertEquals("LEASED",jdbc.queryForObject("SELECT state FROM notification_job WHERE id=?",String.class,fresh.id()));queue.failed(fresh);assertEquals("QUEUED",jdbc.queryForObject("SELECT state FROM notification_job WHERE id=?",String.class,fresh.id()));
            assertEquals(Booking.State.PENDING_CONFIRMATION,service.get(actor,created.bookingId()).booking().state());
        }finally{java.nio.file.Files.deleteIfExists(key);}
    }
    private static <T>List<T> race(int count,java.util.function.IntFunction<T> action)throws Exception{
        try(var pool=Executors.newFixedThreadPool(count)){var barrier=new CyclicBarrier(count);List<Future<T>> tasks=new ArrayList<>();for(int i=0;i<count;i++){int index=i;tasks.add(pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return action.apply(index);}));}List<T> results=new ArrayList<>();for(var task:tasks)results.add(task.get(45,TimeUnit.SECONDS));return results;}
    }
}
