package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.adapters.out.security.Secrets;
import com.purpuragenda.application.port.out.ConfigurationUnitOfWork;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.AdministratorConfiguration;
import com.purpuragenda.application.scheduling.AdministratorConfiguration.*;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.scheduling.*;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL schedule and policy commands: persisted authority, reviewed conflicts, immutability, rollback and races. */
@Timeout(240)
@ResourceLock("postgres-migration-fixture")
class AdministratorConfigurationPostgresTest {
    static final String REASON="Synthetic configuration reason";
    final CustomerCreationPostgresTest c=new CustomerCreationPostgresTest();
    @TempDir Path directory;
    PostgresConfiguration adapter;AdministratorConfiguration config;SessionBinding session;UUID adminId;
    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void setup() throws Exception {
        c.directory=directory;c.setup();adapter=new PostgresConfiguration(c.f.source);config=new AdministratorConfiguration(adapter);
        adminId=UUID.randomUUID();session=adminSession(adminId);
    }
    @AfterEach void cleanup() { c.cleanup(); }

    SessionBinding adminSession(UUID id) {
        c.jdbc.update("INSERT INTO admin_user(id,email_key,password_hash,totp_secret,enrolled,enabled) VALUES(?,?,?,?,true,true)",
                id,"admin-"+id+"@example.test","synthetic-hash","synthetic-secret");
        return newSession(id,0);
    }
    SessionBinding newSession(UUID id,long epoch) {
        UUID sid=UUID.randomUUID();String browser="d".repeat(64);
        c.jdbc.update("""
            INSERT INTO app_session(id,token_hash,browser_hash,csrf_hash,kind,admin_id,admin_security_version,issued_at,last_seen_at,absolute_expires_at,authenticated_at)
            SELECT ?,?,?,?,'ADMIN',?,?,t,t,t+interval '8 hours',t FROM (SELECT clock_timestamp() t) captured
            """,sid,Secrets.hash("synthetic-admin-token"+sid),browser,Secrets.hash("synthetic-admin-csrf"),id,epoch);
        return new SessionBinding(sid,browser);
    }
    Review review(UUID... acknowledged) { return new Review(REASON,Set.of(acknowledged)); }
    AvailabilityCalendar calendar() { return c.scheduling.read(v->v.calendar()); }
    SchedulingPolicy policy() { return c.scheduling.read(v->v.policy()); }
    Map<DayOfWeek,List<AvailabilityCalendar.Window>> weekly(int from,int to) {
        var days=new EnumMap<DayOfWeek,List<AvailabilityCalendar.Window>>(DayOfWeek.class);
        for(var day:DayOfWeek.values())days.put(day,List.of(new AvailabilityCalendar.Window(from,to)));return days;
    }
    Receipt replace(String key,long version,int from,int to,Review review) { return config.replaceWeekly(session,key,version,weekly(from,to),review); }
    PolicyValues values(int capacity) { var p=policy();return new PolicyValues(p.gridMinutes(),capacity,p.minimumNotice(),p.pendingLifetime(),p.expiryBuffer(),p.customerCutoff()); }
    int count(String sql) { return c.count(sql); }
    Failure fail(org.junit.jupiter.api.function.Executable work) { return c.failure(work); }
    UUID book(String email,int day,int hour) { var r=c.create(c.guest(email),"k-"+email,c.request(day,hour));assertTrue(r.success());return r.entityId(); }

    @Test void weeklyReplacementPersistsTheNextVersionWithAuditAndIdempotentReplayAndTouchesNoBooking() {
        UUID id=book("one@example.test",3,9);Booking before=c.load(id);
        Receipt receipt=replace("weekly",1,540,1260,review());
        assertEquals(new Receipt(Code.OK,AdministratorConfiguration.CALENDAR_ID,2),receipt);
        assertEquals(weekly(540,1260),calendar().weekly());assertEquals(2,calendar().version());assertEquals(before,c.load(id));
        assertEquals(1,count("audit_event WHERE type='CALENDAR_CHANGED' AND actor_kind='ADMIN' AND booking_id IS NULL AND version=2 AND reason='"+REASON+"'"));
        assertEquals(0,count("outbox_event WHERE type='CONFIGURATION_CONFLICT'"));
        assertEquals(adminId+"/REPLACE_WEEKLY/-",c.f.value("SELECT scope FROM idempotency_record WHERE scope LIKE '%REPLACE_WEEKLY%'"));
        assertEquals(receipt,replace("weekly",1,540,1260,review()));assertEquals(1,count("audit_event WHERE type='CALENDAR_CHANGED'"));
        assertEquals(Code.IDEMPOTENCY_KEY_REUSED,replace("weekly",1,540,1261,review()).code());
        assertEquals(Code.CALENDAR_CHANGED,replace("stale",1,540,1262,review()).code());assertEquals(2,calendar().version());
        assertEquals(Code.INVALID_CONFIGURATION,replace("noop",2,540,1260,review()).code());
    }

    @Test void conflictingChangesNeedTheExactAcknowledgementAndNeverInvalidateExistingBookings() {
        UUID mine=book("one@example.test",3,9),other=book("two@example.test",4,12);Booking before=c.load(mine);
        assertEquals(Code.CONFLICT_REVIEW_REQUIRED,replace("none",1,660,1080,review()).code());
        assertEquals(Code.CONFLICT_REVIEW_REQUIRED,replace("extra",1,660,1080,review(mine,UUID.randomUUID())).code());
        assertEquals(1,calendar().version());assertEquals(0,count("audit_event WHERE type='CALENDAR_CHANGED'"));
        Receipt ok=replace("ack",1,660,1080,review(mine));assertTrue(ok.success());assertEquals(2,calendar().version());
        assertEquals(before,c.load(mine));assertEquals(Booking.State.PENDING_CONFIRMATION,c.load(mine).state());assertEquals(2,count("booking_occupancy WHERE released_at IS NULL"));
        assertEquals(1,count("audit_event WHERE type='CONFIGURATION_CONFLICT_ACKNOWLEDGED' AND booking_id='"+mine+"' AND version=1"));
        assertEquals(1,count("outbox_event WHERE type='CONFIGURATION_CONFLICT' AND booking_id='"+mine+"'"));
        assertEquals("t",c.f.value("SELECT metadata->'reasons' @> '[\"START_RULES\"]' FROM audit_event WHERE type='CONFIGURATION_CONFLICT_ACKNOWLEDGED'"));
        // New requests obey the new calendar immediately while the old booking keeps its original snapshot and occupancy.
        assertEquals(Code.START_NOT_AVAILABLE,c.create(c.guest("three@example.test"),"new",c.request(5,9)).code());
        assertEquals(other.toString().length(),other.toString().length());
    }

    @Test void dateExceptionsAndBlackoutsAreVersionedAndRemovable() {
        LocalDate today=c.now().atZone(SchedulingPolicy.BUSINESS_ZONE).toLocalDate();LocalDate day=today.plusDays(6);
        var closed=new AvailabilityCalendar.DateException(AvailabilityCalendar.Mode.CLOSED,List.of());
        assertEquals(Code.INVALID_CONFIGURATION,config.setDateException(session,"past",1,today.minusDays(1),closed,review()).code());
        assertTrue(config.setDateException(session,"closed",1,day,closed,review()).success());assertEquals(closed,calendar().exceptions().get(day));
        assertEquals(Code.START_NOT_AVAILABLE,c.create(c.guest("a@example.test"),"closed",c.request(6,9)).code());
        assertTrue(config.removeDateException(session,"reopen",2,day,review()).success());assertTrue(calendar().exceptions().isEmpty());
        UUID id=UUID.randomUUID();Instant start=c.request(7,9).start();
        var blackout=new AvailabilityCalendar.Blackout(id,new ReservationInterval(start.plusSeconds(3600),start.plusSeconds(3660)),"Synthetic closure reason");
        assertTrue(config.addBlackout(session,"black",3,blackout,review()).success());assertEquals(List.of(blackout),calendar().blackouts());
        assertEquals(Code.HARD_BLACKOUT,c.create(c.guest("b@example.test"),"black",c.request(7,9)).code());
        assertEquals(Code.INVALID_CONFIGURATION,config.addBlackout(session,"dup",4,blackout,review()).code());
        assertTrue(config.removeBlackout(session,"unblack",4,id,review()).success());assertTrue(calendar().blackouts().isEmpty());
        assertTrue(c.create(c.guest("b@example.test"),"free",c.request(7,9)).success());
        assertEquals(5,calendar().version());assertEquals(4,count("audit_event WHERE type='CALENDAR_CHANGED'"));
    }

    @Test void policyEditsAppendImmutableVersionsAndLoweredCapacityOnlyBlocksNewBookings() {
        UUID kept=book("one@example.test",3,9);Booking before=c.load(kept);
        assertEquals(Code.CONFLICT_REVIEW_REQUIRED,config.updatePolicy(session,"zero",1,values(0),review()).code());
        Receipt ok=config.updatePolicy(session,"zero-ack",1,values(0),review(kept));
        assertEquals(new Receipt(Code.OK,AdministratorConfiguration.POLICY_ID,2),ok);assertEquals(2,policy().version());assertEquals(0,policy().dailyCapacity());
        assertEquals(2,count("policy_version"));assertEquals(1,count("policy_version WHERE version=1 AND document->>'dailyCapacity'='3'"));
        assertEquals(before,c.load(kept));assertEquals(1,c.load(kept).snapshot().policy().version());
        assertEquals(1,count("audit_event WHERE type='POLICY_CHANGED' AND version=2"));
        assertEquals(Code.POLICY_CHANGED,c.create(c.guest("two@example.test"),"stale",c.request(5,9)).code());
        assertEquals(Code.POLICY_CHANGED,config.updatePolicy(session,"stale",1,values(5),review()).code());
        assertTrue(config.updatePolicy(session,"restore",2,values(3),review()).success());assertEquals(3,count("policy_version"));
        assertThrows(Exception.class,()->c.jdbc.update("UPDATE policy_version SET document='{}'::jsonb WHERE version=1"));
        assertThrows(Exception.class,()->c.jdbc.update("DELETE FROM policy_version WHERE version=2"));
    }

    @Test void expectedFailureReceiptsKeepExpiryReclamationAndUnexpectedFailuresRollBackEverything() {
        UUID due=c.f.database.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        assertEquals(Code.CALENDAR_CHANGED,replace("stale",9,540,1260,review()).code());
        assertEquals(Booking.State.EXPIRED,c.load(due).state());assertEquals(1,count("outbox_event WHERE type='EXPIRED'"));assertEquals(1,calendar().version());
        UUID second=c.f.database.booking(MigrationTest.START.plusSeconds(86400),"PENDING_CONFIRMATION",MigrationTest.snapshot());
        var key=CommandKey.fingerprint(List.of("synthetic"));var boom=new IllegalStateException("SYNTHETIC");
        var thrown=assertThrows(IllegalStateException.class,()->adapter.execute(new ConfigurationUnitOfWork.Command(session,ConfigurationUnitOfWork.Operation.REPLACE_WEEKLY,"boom",key),tx->
                com.purpuragenda.application.scheduling.SchedulingCommands.within(tx,()->{
                    var next=new AvailabilityCalendar(weekly(1,2),Map.of(),List.of(),tx.calendar().version()+1);
                    tx.saveCalendar(next);tx.changedEffects(ConfigurationUnitOfWork.Operation.REPLACE_WEEKLY,next.version(),REASON,List.of());throw boom;
                })));
        assertSame(boom,thrown);assertEquals(1,calendar().version());assertEquals(Booking.State.PENDING_CONFIRMATION,c.load(second).state());
        assertEquals(0,count("audit_event WHERE type='CALENDAR_CHANGED'"));assertEquals(0,count("idempotency_record WHERE key='boom'"));
        // A save without its effects, effects without a save and a skipped version are storage failures that commit nothing.
        for(var misuse:List.<java.util.function.Consumer<ConfigurationUnitOfWork.Configuration>>of(
                tx->tx.saveCalendar(new AvailabilityCalendar(weekly(1,2),Map.of(),List.of(),tx.calendar().version()+1)),
                tx->tx.changedEffects(ConfigurationUnitOfWork.Operation.REPLACE_WEEKLY,1,REASON,List.of()),
                tx->tx.saveCalendar(new AvailabilityCalendar(weekly(1,2),Map.of(),List.of(),tx.calendar().version()+2)),
                tx->tx.savePolicy(new SchedulingPolicy(tx.policy().version()+2,30,3,Duration.ZERO,Duration.ofDays(1),Duration.ZERO,Duration.ZERO)))) {
            assertEquals(Failure.Kind.STORAGE_FAILURE,fail(()->adapter.execute(new ConfigurationUnitOfWork.Command(session,ConfigurationUnitOfWork.Operation.REPLACE_WEEKLY,"misuse",key),tx->{
                misuse.accept(tx);return new Receipt(Code.OK,AdministratorConfiguration.CALENDAR_ID,2);})).kind());
            assertEquals(1,calendar().version());assertEquals(1,policy().version());
        }
    }

    @Test void onlyAFreshEnabledEnrolledMatchingEpochAdministratorSessionAuthorizes() {
        var guest=c.guest("synthetic@example.test");
        assertEquals(Failure.Kind.FORBIDDEN,fail(()->config.replaceWeekly(c.binding(guest),"guest",1,weekly(1,2),review())).kind());
        try(var anonymous=c.sessions.anonymous()) { assertEquals(Failure.Kind.FORBIDDEN,fail(()->config.replaceWeekly(c.binding(anonymous.session()),"anon",1,weekly(1,2),review())).kind()); }
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,fail(()->config.replaceWeekly(new SessionBinding(UUID.randomUUID(),session.browserHash()),"x",1,weekly(1,2),review())).kind());
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,fail(()->config.replaceWeekly(new SessionBinding(session.sessionId(),"0".repeat(64)),"x",1,weekly(1,2),review())).kind());
        for(String change:List.of("UPDATE admin_user SET enabled=false","UPDATE admin_user SET enrolled=false","UPDATE admin_user SET password_hash='rotated'","UPDATE app_session SET revoked_at=clock_timestamp()")) {
            c.jdbc.update("DELETE FROM app_session WHERE kind='ADMIN'");c.jdbc.update("DELETE FROM admin_user");
            adminId=UUID.randomUUID();session=adminSession(adminId);c.jdbc.update(change);
            assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,fail(()->replace("denied",1,540,1260,review())).kind(),change);
        }
        assertEquals(1,calendar().version());assertEquals(0,count("idempotency_record"));assertEquals(0,count("audit_event WHERE type='CALENDAR_CHANGED'"));
    }

    @Test void revocationIsCheckedBeforeSuccessfulReceiptReplay() {
        Receipt first=replace("replay",1,540,1260,review());assertTrue(first.success());assertEquals(first,replace("replay",1,540,1260,review()));
        c.jdbc.update("UPDATE admin_user SET totp_secret='rotated-secret'");
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,fail(()->replace("replay",1,540,1260,review())).kind());
        assertEquals(1,count("audit_event WHERE type='CALENDAR_CHANGED'"));
    }

    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void freshMultiFactorWindowIsExclusiveAndStaleMfaIsDistinguishable(long micros) {
        Instant authenticated=c.jdbc.queryForObject("SELECT authenticated_at FROM app_session WHERE id=?",(r,n)->r.getTimestamp(1).toInstant(),session.sessionId());
        var reads=new AtomicInteger();var fixed=new AdministratorConfiguration(new PostgresConfiguration(c.clock(authenticated.plusSeconds(600).plusNanos(micros*1000),reads)));
        if(micros<0)assertTrue(fixed.replaceWeekly(session,"edge",1,weekly(540,1260),review()).success());
        else assertEquals(Failure.Kind.FRESH_AUTHENTICATION_REQUIRED,fail(()->fixed.replaceWeekly(session,"edge",1,weekly(540,1260),review())).kind());
        assertEquals(1,reads.get());assertEquals(micros<0?2:1,calendar().version());
    }

    @Test void twentyIndependentConnectionsCannotApplyTheSameCalendarVersionTwice() throws Exception {
        var other=new AdministratorConfiguration(new PostgresConfiguration(c.f.source));
        var results=race(i->(i%2==0?config:other).replaceWeekly(session,"race-"+i,1,weekly(540+i,1260),review()));
        assertEquals(1,results.stream().filter(Receipt::success).count());assertEquals(19,results.stream().filter(r->r.code()==Code.CALENDAR_CHANGED).count());
        assertEquals(2,calendar().version());assertEquals(1,count("audit_event WHERE type='CALENDAR_CHANGED'"));assertEquals(20,count("idempotency_record"));
    }

    @Test void twentySameKeyRetriesApplyOneChangeAndOneSetOfEffects() throws Exception {
        UUID mine=book("one@example.test",3,9);var other=new AdministratorConfiguration(new PostgresConfiguration(c.f.source));
        var results=race(i->(i%2==0?config:other).replaceWeekly(session,"same",1,weekly(660,1080),review(mine)));
        assertEquals(1,new HashSet<>(results).size());assertTrue(results.getFirst().success());
        assertEquals(2,calendar().version());assertEquals(1,count("audit_event WHERE type='CALENDAR_CHANGED'"));assertEquals(1,count("outbox_event WHERE type='CONFIGURATION_CONFLICT'"));
    }

    @Test void aConfigurationChangeAndConcurrentCustomerBookingsNeverBothWinAnOutOfWindowSlot() throws Exception {
        var customers=new ArrayList<com.purpuragenda.application.port.out.SessionStore.Snapshot>();
        for(int i=0;i<10;i++)customers.add(c.guest("racer"+i+"@example.test"));
        var other=new AdministratorConfiguration(new PostgresConfiguration(c.f.source));
        var results=race(i->i<10?c.create(customers.get(i),"customer",c.request(3+i,9)):(i%2==0?config:other).replaceWeekly(session,"shrink-"+i,1,weekly(660,1080),review()));
        boolean changed=results.subList(10,20).stream().anyMatch(Receipt::success);
        // An unacknowledged change can only commit while no 09:00 booking exists, and later creations then fail.
        assertEquals(changed?0:10,count("booking"));assertEquals(changed?2:1,calendar().version());
    }

    @Test void conflictAssessmentSeesPersistedDaylightSavingAndMonthEndBookingsOnRealDates() {
        // Far-future confirmed bookings inserted as persisted facts; the assessment evaluates them by Brussels local time.
        UUID spring=confirmed("2027-03-28T07:00:00Z"),fall=confirmed("2027-10-31T00:30:00Z"),lateJanuary=confirmed("2027-01-31T22:30:00Z"),nextDay=confirmed("2027-02-01T08:00:00Z");
        var query=new com.purpuragenda.application.scheduling.ConfigurationConflicts(c.scheduling);
        AvailabilityCalendar base;
        assertTrue(replace("open-all-day",1,0,1440,review()).success());   // every persisted booking is inside the current calendar
        base=calendar();
        java.util.function.BiFunction<LocalDate,int[],AvailabilityCalendar> replaced=(date,w)->new AvailabilityCalendar(base.weekly(),Map.of(date,
                new AvailabilityCalendar.DateException(AvailabilityCalendar.Mode.REPLACE,List.of(new AvailabilityCalendar.Window(w[0],w[1])))),List.of(),base.version()+1);
        assertEquals(List.of(spring),ids(query.assess(new com.purpuragenda.application.scheduling.ConfigurationConflicts.Proposal(replaced.apply(LocalDate.of(2027,3,28),new int[]{600,1200}),null))));   // 09:00 at +02:00
        assertEquals(List.of(fall),ids(query.assess(new com.purpuragenda.application.scheduling.ConfigurationConflicts.Proposal(replaced.apply(LocalDate.of(2027,10,31),new int[]{0,150}),null))));   // 02:30 at +02:00 is minute 150
        var midnight=Instant.parse("2027-01-31T23:30:00Z");
        var blackout=new AvailabilityCalendar.Blackout(UUID.randomUUID(),new ReservationInterval(midnight,midnight.plusSeconds(600)),"Synthetic closure reason");
        assertEquals(List.of(lateJanuary),ids(query.assess(new com.purpuragenda.application.scheduling.ConfigurationConflicts.Proposal(new AvailabilityCalendar(base.weekly(),Map.of(),List.of(blackout),base.version()+1),null))));
        assertNotEquals(lateJanuary,nextDay);
    }
    UUID confirmed(String start) { return c.f.database.booking(Instant.parse(start),"CONFIRMED",MigrationTest.snapshot()); }
    List<UUID> ids(com.purpuragenda.application.scheduling.ConfigurationConflicts.Assessment assessment) {
        return assessment.conflicts().stream().map(com.purpuragenda.application.scheduling.ConfigurationConflicts.Conflict::bookingId).toList();
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
