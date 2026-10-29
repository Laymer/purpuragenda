package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.email.*;
import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.application.notification.*;
import com.purpuragenda.application.port.out.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated real PostgreSQL; only the authoritative clock return is fixed, never authentication or application writes. */
@Timeout(180)
@ResourceLock("postgres-migration-fixture")
class ReminderDatabaseTest {
    static final Instant START=Instant.parse("2026-11-10T08:00:00Z"), NOW=START.minus(Duration.ofDays(3));
    MigrationTest fixture;DataSource source,fixed;JdbcTemplate jdbc;PostgresOutboxStore outbox;PostgresNotificationQueue queue;
    AtomicReference<Instant> clock;List<MailDelivery.Message> sent;
    @BeforeAll static void start(){MigrationTest.start();}
    @AfterAll static void stop(){MigrationTest.stop();}
    @BeforeEach void setup() {
        fixture=new MigrationTest();fixture.fresh();clock=new AtomicReference<>(NOW);sent=Collections.synchronizedList(new ArrayList<>());
        source=new AbstractDataSource() {
            public Connection getConnection(){return fixture.connection(MigrationTest.APP);}
            public Connection getConnection(String user,String password){throw new UnsupportedOperationException();}
        };
        fixed=new DelegatingDataSource(source) {
            @Override public Connection getConnection() throws SQLException {
                var connection=source.getConnection();
                return GuestAccessDatabaseTest.proxy(Connection.class,(p,m,a)->{
                    Object result=GuestAccessDatabaseTest.invoke(connection,m,a);
                    if(!m.getName().equals("createStatement"))return result;
                    var statement=(Statement)result;
                    return GuestAccessDatabaseTest.proxy(Statement.class,(sp,sm,sa)->{
                        Object rows=GuestAccessDatabaseTest.invoke(statement,sm,sa);
                        if(!sm.getName().equals("executeQuery") || !"SELECT clock_timestamp()".equals(sa[0]))return rows;
                        return GuestAccessDatabaseTest.proxy(ResultSet.class,(rp,rm,ra)->rm.getName().equals("getTimestamp") && Integer.valueOf(1).equals(ra[0])
                                ?Timestamp.from(clock.get()):GuestAccessDatabaseTest.invoke(rows,rm,ra));
                    });
                });
            }
        };
        jdbc=new JdbcTemplate(source);outbox=new PostgresOutboxStore(fixed);queue=new PostgresNotificationQueue(fixed,false);
    }
    @AfterEach void cleanup(){fixture.cleanup();}
    UUID booking(String state){return fixture.booking(START,state,MigrationTest.snapshot());}
    UUID event(UUID booking,String type,long version,boolean expanded) {
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO outbox_event(id,booking_id,type,version,created_at,expanded_at) VALUES(?,?,?,?,?,?)",
                id,booking,type,version,Timestamp.from(clock.get()),expanded?Timestamp.from(clock.get()):null);return id;
    }
    UUID job(UUID booking,String template,int grace,Instant original) {
        UUID id=UUID.randomUUID(),event=event(booking,"APPROVED",1,true);
        jdbc.update("INSERT INTO notification_job(id,event_id,booking_id,audience,template,version,due_at,scheduled_at,grace_seconds) VALUES(?,?,?,'CUSTOMER',?,1,?,?,?)",
                id,event,booking,template,Timestamp.from(clock.get()),Timestamp.from(original),grace);return id;
    }
    int count(String where){return jdbc.queryForObject("SELECT count(*) FROM "+where,Integer.class);}
    BookingNotificationWorker worker(){return worker(message->{sent.add(message);return MailDelivery.Result.SENT;});}
    BookingNotificationWorker worker(MailDelivery mail){return new BookingNotificationWorker(queue,new PostgresBookingNotificationStore(fixed),mail,false);}
    void expand(){assertEquals(OutboxStore.Outcome.EXPANDED,new OutboxExpander(outbox).tick());}
    void suppressImmediate(){jdbc.update("UPDATE notification_job SET state='SUPPRESSED' WHERE template NOT IN ('REMINDER_48H','REMINDER_2H')");}
    void move(UUID booking,Instant next) {
        new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->{
            jdbc.update("UPDATE booking SET start_at=?,session_end=?,reserved_end=?,business_date=(?::timestamptz AT TIME ZONE 'Europe/Brussels')::date,version=version+1 WHERE id=?",
                    Timestamp.from(next),Timestamp.from(next.plusSeconds(1800)),Timestamp.from(next.plusSeconds(7200)),Timestamp.from(next),booking);
            jdbc.update("UPDATE booking_occupancy SET occupied=tstzrange(?,?,'[)') WHERE booking_id=?",Timestamp.from(next),Timestamp.from(next.plusSeconds(7200)),booking);return null;
        });
    }
    String error(UUID id){return jdbc.queryForObject("SELECT safe_error FROM notification_job WHERE id=?",String.class,id);}

    @Test void confirmationSchedulesOriginalDeadlinesAndExpansionRerunCreatesNoDuplicates() {
        UUID id=booking("CONFIRMED");event(id,"APPROVED",1,false);expand();
        assertEquals(4,count("notification_job"));assertEquals(2,count("notification_job WHERE scheduled_at IS NULL AND grace_seconds IS NULL"));
        assertEquals(1,count("notification_job j JOIN booking b ON b.id=j.booking_id WHERE j.template='REMINDER_48H' AND j.scheduled_at=b.start_at-interval '48 hours'"));
        assertTrue(jdbc.queryForObject("SELECT bool_and(scheduled_at=due_at AND audience='CUSTOMER') FROM notification_job WHERE scheduled_at IS NOT NULL",Boolean.class));
        assertEquals(List.of(900,3600),jdbc.queryForList("SELECT grace_seconds FROM notification_job WHERE scheduled_at IS NOT NULL ORDER BY grace_seconds",Integer.class));
        assertEquals(OutboxStore.Outcome.IDLE,new OutboxExpander(outbox).tick());assertEquals(4,count("notification_job"));
    }
    @ParameterizedTest @ValueSource(strings={"APPROVED","CONFIRMED_EXTERNAL_PAYMENT"})
    void delayedPendingCreationFollowedByApprovalNeverSendsDuplicateReminders(String confirmationType) {
        UUID id=booking("CONFIRMED");event(id,"ADMIN_CREATED",1,false);event(id,confirmationType,2,false);
        assertEquals(2,new OutboxExpander(outbox).drain(10));suppressImmediate();clock.set(START.minus(Duration.ofHours(48)));
        for(int i=0;i<3;i++)worker().tick();
        assertEquals(1,sent.size());assertEquals(1,count("notification_job WHERE template='REMINDER_48H' AND version=1 AND safe_error='SUPERSEDED'"));
    }
    @Test void pendingAdministratorCreationDoesNotScheduleReminders() {
        UUID id=booking("PENDING_CONFIRMATION");event(id,"ADMIN_CREATED",1,false);expand();assertEquals(2,count("notification_job"));assertEquals(0,count("notification_job WHERE scheduled_at IS NOT NULL"));
    }
    @ParameterizedTest @CsvSource({"false,1800","true,1800","false,3601"})
    void delayedExpansionHonorsEventEligibilityAndNeverExtendsOriginalGrace(boolean lateConfirmation,int delaySeconds) {
        Instant original=START.minus(Duration.ofHours(48));clock.set(lateConfirmation?original.plusNanos(1000):original.minusSeconds(1));
        UUID id=booking("CONFIRMED");event(id,"APPROVED",1,false);clock.set(original.plusSeconds(delaySeconds));expand();suppressImmediate();
        assertEquals(lateConfirmation?0:1,count("notification_job WHERE template='REMINDER_48H'"));
        assertEquals(lateConfirmation?BookingNotificationWorker.Outcome.IDLE:delaySeconds<3600?BookingNotificationWorker.Outcome.SENT:BookingNotificationWorker.Outcome.DISCARDED,worker().tick());
        assertEquals(!lateConfirmation && delaySeconds<3600?1:0,sent.size());
    }
    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void creationIncludesDueEqualityButSkipsStrictlyPastDue(long micros) {
        clock.set(START.minus(Duration.ofHours(48)).plusNanos(micros*1000));UUID id=booking("CONFIRMED");event(id,"APPROVED",1,false);expand();
        assertEquals(micros<=0?1:0,count("notification_job WHERE template='REMINDER_48H'"));assertEquals(1,count("notification_job WHERE template='REMINDER_2H'"));
    }
    @ParameterizedTest @CsvSource({"48,3600,-1","48,3600,0","48,3600,1","2,900,-1","2,900,0","2,900,1"})
    void graceEqualityIsExpiredForBothReminderDeadlines(int lead,int grace,long micros) {
        Instant original=START.minus(Duration.ofHours(lead));clock.set(original.plusSeconds(grace).plusNanos(micros*1000));
        UUID id=job(booking("CONFIRMED"),"REMINDER_"+lead+"H",grace,original);
        assertEquals(micros<0?BookingNotificationWorker.Outcome.SENT:BookingNotificationWorker.Outcome.DISCARDED,worker().tick());
        assertEquals(micros<0?1:0,sent.size());if(micros>=0)assertEquals("REMINDER_GRACE_EXPIRED",error(id));
    }
    @Test void retryDueTimeCannotExtendTheOriginalGraceDeadline() {
        Instant original=START.minus(Duration.ofHours(2));clock.set(original.plusSeconds(840));UUID id=job(booking("CONFIRMED"),"REMINDER_2H",900,original);
        var worker=worker(message->{sent.add(message);return MailDelivery.Result.RETRY_LATER;});
        assertEquals(BookingNotificationWorker.Outcome.RETRY_LATER,worker.tick());
        assertEquals(original,jdbc.queryForObject("SELECT scheduled_at FROM notification_job WHERE id=?",Timestamp.class,id).toInstant());
        clock.set(original.plusSeconds(900));assertEquals(BookingNotificationWorker.Outcome.DISCARDED,worker.tick());
        assertEquals(1,sent.size());assertEquals("REMINDER_GRACE_EXPIRED",error(id));
    }
    @Test void reschedulingCreatesNewRevisionAndSuppressesOldReminderWithoutSendingOldTime() {
        UUID booking=booking("CONFIRMED");event(booking,"APPROVED",1,false);expand();move(booking,START.plus(Duration.ofDays(1)));event(booking,"RESCHEDULED",2,false);expand();suppressImmediate();
        assertEquals(4,count("notification_job WHERE scheduled_at IS NOT NULL"));clock.set(START.minus(Duration.ofHours(48)));
        assertEquals(BookingNotificationWorker.Outcome.DISCARDED,worker().tick());assertTrue(sent.isEmpty());
        assertEquals(1,count("notification_job WHERE template='REMINDER_48H' AND version=1 AND safe_error='SUPERSEDED'"));
        clock.set(START.plus(Duration.ofDays(1)).minus(Duration.ofHours(48)));assertEquals(BookingNotificationWorker.Outcome.SENT,worker().tick());
        assertTrue(sent.getFirst().text().contains(START.plus(Duration.ofDays(1)).toString()));
    }
    @Test void cancellationSuppressesQueuedReminderAtCurrentStateEvenWithoutALaterEvent() {
        Instant original=START.minus(Duration.ofHours(48));clock.set(original);UUID booking=booking("CONFIRMED"),id=job(booking,"REMINDER_48H",3600,original);
        new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->{jdbc.update("UPDATE booking SET state='CANCELLED' WHERE id=?",booking);jdbc.update("UPDATE booking_occupancy SET released_at=? WHERE booking_id=?",Timestamp.from(clock.get()),booking);return null;});
        assertEquals(BookingNotificationWorker.Outcome.DISCARDED,worker().tick());assertEquals("BOOKING_NOT_CONFIRMED",error(id));assertTrue(sent.isEmpty());
    }
    @Test void twentyIndependentExpandersAndWorkersProduceOnlyOneCurrentReminder() throws Exception {
        UUID id=booking("CONFIRMED");event(id,"APPROVED",1,false);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<Future<OutboxStore.Outcome>>();for(int i=0;i<20;i++)futures.add(executor.submit(()->new OutboxExpander(new PostgresOutboxStore(fixed)).tick()));
            for(var future:futures)future.get(10,TimeUnit.SECONDS);
        }
        assertEquals(4,count("notification_job"));suppressImmediate();clock.set(START.minus(Duration.ofHours(48)));
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<Future<BookingNotificationWorker.Outcome>>();for(int i=0;i<20;i++)futures.add(executor.submit(()->worker().tick()));
            int delivered=0;for(var future:futures)if(future.get(10,TimeUnit.SECONDS)==BookingNotificationWorker.Outcome.SENT)delivered++;assertEquals(1,delivered);
        }
        assertEquals(1,sent.size());assertEquals(1,count("notification_job WHERE template='REMINDER_48H' AND state='SENT'"));
    }
    @Test void aFailedReminderInsertRollsBackBothAudiencesAndExpansionBeforeACompleteRetry() throws Exception {
        UUID id=booking("CONFIRMED");event(id,"APPROVED",1,false);
        try(var connection=fixture.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(connection,"CREATE FUNCTION fail_reminder_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.template='REMINDER_2H' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='SYNTHETIC_FAULT'; END IF; RETURN NEW; END $$; CREATE TRIGGER fail_reminder BEFORE INSERT ON notification_job FOR EACH ROW EXECUTE FUNCTION fail_reminder_insert()");
        }
        var failure=assertThrows(IllegalStateException.class,()->new OutboxExpander(outbox).tick());assertEquals("OUTBOX_EXPANSION_FAILED",failure.getMessage());assertNull(failure.getCause());
        assertEquals(0,count("notification_job"));assertEquals(0,count("outbox_event WHERE expanded_at IS NOT NULL"));
        try(var connection=fixture.connection(MigrationTest.MIGRATOR)){MigrationTest.execute(connection,"DROP TRIGGER fail_reminder ON notification_job");}
        expand();assertEquals(4,count("notification_job"));assertEquals(1,count("outbox_event WHERE expanded_at IS NOT NULL"));
    }
    @Test void currentContactMeetingAndIndependentPaymentFactsDoNotLoseAValidReminder() {
        clock.set(START.minus(Duration.ofHours(48)));UUID booking=booking("CONFIRMED");job(booking,"REMINDER_48H",3600,clock.get());
        jdbc.update("UPDATE booking SET delivery_email='updated@example.test',meeting_id='987654321',meeting_url='https://zoom.us/j/987654321',version=2 WHERE id=?",booking);event(booking,"PAYMENT_RECORDED",2,false);
        assertEquals(BookingNotificationWorker.Outcome.SENT,worker().tick());var message=sent.getFirst();
        assertEquals("updated@example.test",message.recipient());assertTrue(message.text().contains("https://zoom.us/j/987654321"));
        for(String privateField:List.of("Alex","Synthetic city","+32400123456","1990","token"))assertFalse(message.text().contains(privateField));
    }
    @Test void databaseRejectsMalformedWindowsAndProtectsOriginalReminderMetadata() {
        UUID booking=booking("CONFIRMED"),id=job(booking,"REMINDER_48H",3600,START.minus(Duration.ofHours(48)));
        for(String change:List.of("scheduled_at=scheduled_at+interval '1 second'","grace_seconds=900","template='BOOKING_CONFIRMED'","version=2","event_id=NULL","audience='OWNER'"))
            fixture.denied("23514","UPDATE notification_job SET "+change+" WHERE id=?",id);
        UUID event=event(booking,"APPROVED",1,true);
        for(Integer grace:Arrays.asList(null,0,900))fixture.denied("23514","INSERT INTO notification_job(id,event_id,booking_id,audience,template,due_at,scheduled_at,grace_seconds) VALUES(?,?,?,'CUSTOMER','REMINDER_48H',now(),now(),?)",UUID.randomUUID(),event,booking,grace);
        fixture.denied("23514","INSERT INTO notification_job(id,event_id,booking_id,audience,template,due_at,grace_seconds) VALUES(?,?,?,'CUSTOMER','REMINDER_2H',now(),900)",UUID.randomUUID(),event,booking);
        jdbc.update("UPDATE notification_job SET due_at=due_at+interval '1 minute' WHERE id=?",id);assertEquals(3600,jdbc.queryForObject("SELECT grace_seconds FROM notification_job WHERE id=?",Integer.class,id));
    }
    @Test void upgradePreservesImmediateJobsRerunsAndRequiresValidatedReminderSchema() {
        fixture.newSchema();fixture.migrate(16);UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO notification_job(id,audience,template,due_at,state,grace_seconds) VALUES(?,'OWNER','LEGACY_NOTICE',?,'QUEUED',900)",id,Timestamp.from(NOW));
        var ready=new DatabaseReadiness(source,new PostgresScheduling(source));assertEquals(Status.DOWN,ready.health().getStatus());
        fixture.migrate(0);fixture.migrate(0);assertEquals(Status.UP,ready.health().getStatus());
        assertEquals(20,fixture.scalar(MigrationTest.MIGRATOR,"SELECT count(*) FROM databasechangelog"));
        assertEquals(1,count("notification_job WHERE id='"+id+"' AND state='QUEUED' AND scheduled_at IS NULL AND grace_seconds=900"));
        assertEquals(NOW,jdbc.queryForObject("SELECT due_at FROM notification_job WHERE id=?",Timestamp.class,id).toInstant());
    }
    @Test void malformedHistoricalReminderMakesUpgradeFailClosedWithoutPartialColumnsOrDataLoss() {
        fixture.newSchema();fixture.migrate(16);UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO notification_job(id,audience,template,due_at) VALUES(?,'OWNER','REMINDER_48H',?)",id,Timestamp.from(NOW));
        var failure=assertThrows(AssertionError.class,()->fixture.migrate(0));assertEquals("MIGRATION_FAILED",failure.getMessage());assertNull(failure.getCause());
        assertEquals(16,fixture.scalar(MigrationTest.MIGRATOR,"SELECT count(*) FROM databasechangelog"));assertEquals(1,count("notification_job WHERE id='"+id+"'"));
        assertEquals(0,fixture.scalar(MigrationTest.APP,"SELECT count(*) FROM information_schema.columns WHERE table_schema='"+fixture.schema+"' AND table_name='notification_job' AND column_name='scheduled_at'"));
    }
}
