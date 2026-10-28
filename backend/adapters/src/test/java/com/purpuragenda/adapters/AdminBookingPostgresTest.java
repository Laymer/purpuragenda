package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.adapters.out.security.Secrets;
import com.purpuragenda.application.port.out.AdminBookingUnitOfWork;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.AdminBooking;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.scheduling.SchedulingPolicy;
import com.purpuragenda.domain.scheduling.SchedulingRules;
import com.purpuragenda.domain.scheduling.SchedulingRules.Rule;
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

/** Real PostgreSQL administrator creation: persisted authority, overrides, atomic effects, rollback and independent-connection races. */
@Timeout(240)
@ResourceLock("postgres-migration-fixture")
class AdminBookingPostgresTest {
    static final String ZOOM="https://zoom.us/j/123456789",REASON="Synthetic administrator reason";
    final CustomerCreationPostgresTest c=new CustomerCreationPostgresTest();
    @TempDir Path directory;
    PostgresAdminBooking adapter;AdminBooking admin;SessionBinding session;UUID adminId;
    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void setup() throws Exception {
        c.directory=directory;c.setup();adapter=new PostgresAdminBooking(c.f.source);admin=new AdminBooking(adapter,"local-v1",Set.of("zoom.us"));
        adminId=UUID.randomUUID();session=adminSession(adminId,true);
    }
    @AfterEach void cleanup() { c.cleanup(); }

    /** Database fixture for an authenticated administrator; the P16/P17 exchange is tested separately. */
    SessionBinding adminSession(UUID id,boolean enabled) {
        c.jdbc.update("INSERT INTO admin_user(id,email_key,password_hash,totp_secret,enrolled,enabled) VALUES(?,?,?,?,true,?)",
                id,"admin-"+id+"@example.test","synthetic-hash","synthetic-secret",enabled);
        return session(id,0);
    }
    SessionBinding session(UUID id,long epoch) {
        UUID sid=UUID.randomUUID();String browser="d".repeat(64);
        c.jdbc.update("""
            INSERT INTO app_session(id,token_hash,browser_hash,csrf_hash,kind,admin_id,admin_security_version,issued_at,last_seen_at,absolute_expires_at,authenticated_at)
            SELECT ?,?,?,?,'ADMIN',?,?,t,t,t+interval '8 hours',t FROM (SELECT clock_timestamp() t) captured
            """,sid,Secrets.hash("synthetic-admin-token"+sid),browser,Secrets.hash("synthetic-admin-csrf"),id,epoch);
        return new SessionBinding(sid,browser);
    }
    long calendarVersion() { return c.scheduling.read(v->v.calendar().version()); }
    Instant day(int offset,int hour,int minute) {
        return c.now().atZone(SchedulingPolicy.BUSINESS_ZONE).toLocalDate().plusDays(offset).atTime(hour,minute).atZone(SchedulingPolicy.BUSINESS_ZONE).toInstant();
    }
    AdminBooking.Request request(Instant start,String email,String meeting,SchedulingRules.Override override) {
        return new AdminBooking.Request(UUID.fromString(MigrationTest.SERVICE),1,1,calendarVersion(),start,email,c.request(3,9).intake(),"Europe/Brussels","local-v1",meeting,override);
    }
    AdminBooking.Request request(int dayOffset,int hour) { return request(day(dayOffset,hour,0),"customer@example.test",null,SchedulingRules.Override.none()); }
    SchedulingRules.Override override(Rule... rules) { return new SchedulingRules.Override(Set.of(rules),REASON,Set.of()); }
    Receipt create(String key,AdminBooking.Request request) { return admin.create(session,key,request); }
    int count(String sql) { return c.count(sql); }
    Failure fail(org.junit.jupiter.api.function.Executable work) { return c.failure(work); }

    @Test void pendingCreationCommitsSnapshotsOccupancyAuditAndOutboxWithoutAnySubmissionCharge() {
        var request=request(3,9);Instant captured=c.now();var reads=new AtomicInteger();
        var fixed=new AdminBooking(new PostgresAdminBooking(c.clock(captured,reads)),"local-v1",Set.of("zoom.us"));
        Receipt receipt=fixed.create(session,"create",request);assertTrue(receipt.success());assertEquals(1,reads.get());
        Booking value=c.load(receipt.entityId());
        assertEquals(Booking.Origin.ADMINISTRATOR,value.origin());assertEquals(Booking.State.PENDING_CONFIRMATION,value.state());
        assertEquals(captured,value.createdAt());assertEquals(request.intake(),value.snapshot().intake());assertEquals("customer@example.test",value.deliveryEmail());
        assertEquals(1,value.snapshot().policy().version());assertEquals(captured.plus(Duration.ofDays(1)),value.expiresAt());
        for(String table:List.of("booking","booking_snapshot","booking_occupancy","audit_event","outbox_event","idempotency_record","customer_identity"))assertEquals(1,count(table));
        for(String table:List.of("request_charge","customer_request_day","booking_override","access_grant","customer_account","payment_entry"))assertEquals(0,count(table));
        assertEquals(adminId+"/ADMIN_CREATE_BOOKING/-",c.f.value("SELECT scope FROM idempotency_record"));
        assertEquals("ADMIN",c.f.value("SELECT actor_kind FROM audit_event"));assertEquals(adminId.toString(),c.f.value("SELECT actor_id::text FROM audit_event"));
        assertEquals("ADMIN_CREATED",c.f.value("SELECT type FROM outbox_event"));assertEquals("f",c.f.value("SELECT (metadata->>'overridden')::boolean FROM audit_event"));
        assertEquals(receipt,create("create",request));assertEquals(1,count("outbox_event"));assertEquals(1,count("booking"));
        assertEquals(Code.IDEMPOTENCY_KEY_REUSED,create("create",request(3,10)).code());assertEquals(1,count("booking"));
    }

    @Test void immediateConfirmationStoresTheManualBasisMeetingAndRevisionZero() {
        Receipt receipt=create("confirm",request(day(3,9,0),"customer@example.test",ZOOM,SchedulingRules.Override.none()));
        Booking value=c.load(receipt.entityId());assertEquals(Booking.State.CONFIRMED,value.state());
        assertEquals(Booking.ConfirmationBasis.MANUAL_APPROVAL,value.confirmationBasis());assertEquals("123456789",value.meeting().meetingId());
        assertNull(value.expiresAt());assertEquals(0,value.calendarRevision());assertEquals(1,count("booking_occupancy WHERE released_at IS NULL"));
        assertEquals(0,count("request_charge"));
    }

    @Test void reasonedOverrideIsAuditedAndStoredImmutablyWhileFailureWithoutItRollsBackTheIdentity() {
        Instant closed=day(3,3,0);
        Receipt refused=create("closed",request(closed,"new@example.test",null,SchedulingRules.Override.none()));
        assertEquals(Code.START_NOT_AVAILABLE,refused.code());
        assertEquals(0,count("customer_identity"));assertEquals(0,count("booking"));assertEquals(1,count("idempotency_record"));
        assertEquals(refused,create("closed",request(closed,"new@example.test",null,SchedulingRules.Override.none())));
        Receipt ok=create("closed-override",request(closed,"new@example.test",null,override(Rule.START_RULES)));assertTrue(ok.success());
        assertEquals(1,count("booking_override WHERE booking_id='"+ok.entityId()+"' AND booking_version=1 AND policy_version=1 AND reason='"+REASON+"'"));
        assertEquals("[\"START_RULES\"]",c.f.value("SELECT rules::text FROM booking_override"));assertEquals("[]",c.f.value("SELECT blackout_ids::text FROM booking_override"));
        assertEquals(REASON,c.f.value("SELECT reason FROM audit_event"));assertEquals("t",c.f.value("SELECT (metadata->>'overridden')::boolean FROM audit_event"));
        assertEquals(1,count("customer_identity"));assertEquals(0,count("request_charge"));
        assertThrows(Exception.class,()->c.jdbc.update("UPDATE booking_override SET reason='tampered reason'"));
    }

    @Test void namedHardBlackoutOverridesNeedEveryIntersectingIdentifierAndAreRecorded() {
        UUID one=UUID.randomUUID(),two=UUID.randomUUID();Instant start=day(3,9,0);
        c.jdbc.update("""
            UPDATE business_settings SET calendar=jsonb_set(calendar,'{blackouts}',jsonb_build_array(
              jsonb_build_object('id',?::text,'interval',jsonb_build_object('start',?::text,'end',?::text),'reason','Synthetic closure reason'),
              jsonb_build_object('id',?::text,'interval',jsonb_build_object('start',?::text,'end',?::text),'reason','Synthetic closure reason')))
            """,one,start.plusSeconds(600).toString(),start.plusSeconds(660).toString(),two,start.plusSeconds(3600).toString(),start.plusSeconds(3660).toString());
        assertEquals(Code.HARD_BLACKOUT,create("none",request(start,"customer@example.test",null,SchedulingRules.Override.none())).code());
        assertEquals(Code.BLACKOUT_OVERRIDE_REQUIRED,create("rule-only",request(start,"customer@example.test",null,override(Rule.HARD_BLACKOUT))).code());
        assertEquals(Code.BLACKOUT_OVERRIDE_REQUIRED,create("partial",request(start,"customer@example.test",null,
                new SchedulingRules.Override(Set.of(Rule.HARD_BLACKOUT),REASON,Set.of(one)))).code());
        assertEquals(0,count("booking"));
        Receipt ok=create("full",request(start,"customer@example.test",null,new SchedulingRules.Override(Set.of(Rule.HARD_BLACKOUT),REASON,Set.of(one,two))));
        assertTrue(ok.success());
        assertEquals("t",c.f.value("SELECT blackout_ids @> to_jsonb(?::text) AND blackout_ids @> to_jsonb(?::text) FROM booking_override",one.toString(),two.toString()));
    }

    @Test void unusedOverridesAreRejectedAndDuplicateMeetingIdsAreStableBusinessReceipts() {
        assertEquals(Code.OVERRIDE_UNUSED,create("unused",request(day(3,9,0),"customer@example.test",null,override(Rule.NOTICE))).code());
        assertEquals(0,count("booking"));assertEquals(0,count("booking_override"));assertEquals(0,count("customer_identity WHERE email_key='customer@example.test'"));
        assertTrue(create("first",request(day(3,9,0),"customer@example.test",ZOOM,SchedulingRules.Override.none())).success());
        Receipt duplicate=create("second",request(day(4,9,0),"other@example.test",ZOOM,SchedulingRules.Override.none()));
        assertEquals(Code.MEETING_IN_USE,duplicate.code());assertEquals(1,count("booking"));assertEquals(0,count("customer_identity WHERE email_key='other@example.test'"));
        assertEquals(duplicate,create("second",request(day(4,9,0),"other@example.test",ZOOM,SchedulingRules.Override.none())));
    }

    @Test void reservationOverlapIsNeverOverridableAndAdjacencyIsAllowed() {
        var customer=c.guest("synthetic@example.test");Instant start=c.request(3,9).start();assertTrue(c.create(customer,"existing",c.request(3,9)).success());
        Rule[] every=Rule.values();
        for(int minutes:new int[]{-119,0,119}) {
            assertEquals(Code.SLOT_UNAVAILABLE,create("overlap"+minutes,request(start.plus(Duration.ofMinutes(minutes)),"admin-customer@example.test",null,override(every))).code());
        }
        assertEquals(1,count("booking"));assertEquals(0,count("customer_identity WHERE email_key='admin-customer@example.test'"));
        assertTrue(create("before",request(start.minus(Duration.ofMinutes(120)),"admin-customer@example.test",null,override(Rule.START_RULES))).success());
        assertTrue(create("after",request(start.plus(Duration.ofMinutes(120)),"admin-customer@example.test",null,SchedulingRules.Override.none())).success());
        assertEquals(3,count("booking_occupancy WHERE released_at IS NULL"));
    }

    @Test void databaseExclusionIndependentlyRejectsAnOverlapEvenWhenTheApplicationCheckIsBypassed() {
        var customer=c.guest("synthetic@example.test");assertTrue(c.create(customer,"existing",c.request(3,9)).success());
        Instant start=c.request(3,9).start().plus(Duration.ofMinutes(60));
        Receipt result=adapter.create(new AdminBookingUnitOfWork.Command(session,"bypass",com.purpuragenda.application.port.out.SchedulingUnitOfWork.CommandKey.fingerprint(List.of("synthetic"))),tx->tx.attempt(()->{
            var snapshot=new Booking.Snapshot(tx.services().getFirst(),tx.policy(),c.request(3,9).intake(),"bypass@example.test","UTC","local-v1");
            var customerId=tx.customer("bypass@example.test");
            Booking booking=Booking.pending(UUID.randomUUID(),"B-"+UUID.randomUUID(),customerId.id(),snapshot,Booking.Origin.ADMINISTRATOR,tx.now(),start);
            tx.save(booking);tx.createdEffects(booking,SchedulingRules.Override.none());return new Receipt(Code.OK,booking.id(),1);
        }));
        assertEquals(Code.SLOT_UNAVAILABLE,result.code());assertEquals(1,count("booking"));assertEquals(0,count("audit_event WHERE type='ADMIN_CREATED'"));
    }

    @Test void administratorBookingsConsumeCapacityAndActiveAllowanceButNeverTheSubmissionQuota() {
        // Active allowance is three per customer; capacity three per day. Override rules are named individually.
        for(int i=0;i<3;i++)assertTrue(create("a"+i,request(3+i,9)).success());
        assertEquals(Code.ACTIVE_LIMIT_REACHED,create("a3",request(6,9)).code());
        assertTrue(create("a3-override",request(day(6,9,0),"customer@example.test",null,override(Rule.ACTIVE_LIMIT))).success());
        assertEquals(0,count("request_charge"));assertEquals(0,count("customer_request_day"));assertEquals(1,count("customer_identity"));
        // A different customer on the same day consumes shared daily capacity (three per day).
        for(int i=0;i<2;i++)assertTrue(create("d"+i,request(day(8,9+3*i,0),"day"+i+"@example.test",null,SchedulingRules.Override.none())).success());
        assertTrue(c.create(c.guest("synthetic@example.test"),"customer",c.request(8,18)).success());
        assertEquals(Code.DAILY_CAPACITY_REACHED,create("d2",request(day(8,15,0),"day3@example.test",null,SchedulingRules.Override.none())).code());
        assertTrue(create("d2-override",request(day(8,15,0),"day3@example.test",null,override(Rule.DAILY_CAPACITY))).success());
        assertEquals(1,count("request_charge"));
    }

    @Test void reviewedVersionsAreCheckedAndExpiredHoldsAreReclaimedByExpectedFailureReceipts() {
        UUID due=c.f.database.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        var good=request(3,9);
        var stalePolicy=new AdminBooking.Request(good.serviceId(),1,9,good.calendarVersion(),good.start(),good.customerEmail(),good.intake(),"UTC","local-v1",null,good.override());
        var staleCalendar=new AdminBooking.Request(good.serviceId(),1,1,99,good.start(),good.customerEmail(),good.intake(),"UTC","local-v1",null,good.override());
        var staleService=new AdminBooking.Request(good.serviceId(),9,1,good.calendarVersion(),good.start(),good.customerEmail(),good.intake(),"UTC","local-v1",null,good.override());
        var staleNotice=new AdminBooking.Request(good.serviceId(),1,1,good.calendarVersion(),good.start(),good.customerEmail(),good.intake(),"UTC","old",null,good.override());
        assertEquals(Code.POLICY_CHANGED,create("p",stalePolicy).code());
        assertEquals(Booking.State.EXPIRED,c.load(due).state());assertEquals(1,count("outbox_event WHERE type='EXPIRED'"));
        assertEquals(Code.CALENDAR_CHANGED,create("c",staleCalendar).code());assertEquals(Code.SERVICE_CHANGED,create("s",staleService).code());
        assertEquals(Code.NOTICE_CHANGED,create("n",staleNotice).code());
        assertEquals(1,count("outbox_event WHERE type='EXPIRED'"));assertEquals(0,count("customer_identity WHERE email_key='customer@example.test'"));assertEquals(1,count("booking"));
    }

    @Test void unexpectedFailuresRollBackEverythingIncludingExpiryReclamationAndMissingEffectsAreRejected() {
        UUID due=c.f.database.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        var good=request(3,9);var key=com.purpuragenda.application.port.out.SchedulingUnitOfWork.CommandKey.fingerprint(List.of("synthetic"));
        var boom=new IllegalStateException("SYNTHETIC");
        var thrown=assertThrows(IllegalStateException.class,()->adapter.create(new AdminBookingUnitOfWork.Command(session,"boom",key),tx->
                com.purpuragenda.application.scheduling.SchedulingCommands.within(tx,()->{
                    var snapshot=new Booking.Snapshot(tx.services().getFirst(),tx.policy(),good.intake(),"boom@example.test","UTC","local-v1");
                    var who=tx.customer("boom@example.test");
                    Booking booking=Booking.pending(UUID.randomUUID(),"B-"+UUID.randomUUID(),who.id(),snapshot,Booking.Origin.ADMINISTRATOR,tx.now(),good.start());
                    tx.save(booking);tx.createdEffects(booking,SchedulingRules.Override.none());throw boom;
                })));
        assertSame(boom,thrown);
        assertEquals(Booking.State.PENDING_CONFIRMATION,c.load(due).state());assertEquals(0,count("outbox_event"));assertEquals(0,count("audit_event"));
        assertEquals(0,count("customer_identity WHERE email_key='boom@example.test'"));assertEquals(0,count("idempotency_record"));assertEquals(1,count("booking"));
        // A success receipt without its audit and outbox effects is a storage failure and commits nothing.
        assertEquals(Failure.Kind.STORAGE_FAILURE,fail(()->adapter.create(new AdminBookingUnitOfWork.Command(session,"bare",key),tx->{
            var snapshot=new Booking.Snapshot(tx.services().getFirst(),tx.policy(),good.intake(),"bare@example.test","UTC","local-v1");
            Booking booking=Booking.pending(UUID.randomUUID(),"B-"+UUID.randomUUID(),tx.customer("bare@example.test").id(),snapshot,Booking.Origin.ADMINISTRATOR,tx.now(),good.start());
            tx.save(booking);return new Receipt(Code.OK,booking.id(),1);
        })).kind());
        assertEquals(1,count("booking"));assertEquals(0,count("idempotency_record"));
        // Effects for a booking that is not the stored administrator creation are refused.
        assertEquals(Failure.Kind.STORAGE_FAILURE,fail(()->adapter.create(new AdminBookingUnitOfWork.Command(session,"foreign",key),tx->{
            var snapshot=new Booking.Snapshot(tx.services().getFirst(),tx.policy(),good.intake(),"foreign@example.test","UTC","local-v1");
            Booking booking=Booking.pending(UUID.randomUUID(),"B-"+UUID.randomUUID(),tx.customer("foreign@example.test").id(),snapshot,Booking.Origin.CUSTOMER,tx.now(),good.start());
            tx.save(booking);tx.createdEffects(booking,SchedulingRules.Override.none());return new Receipt(Code.OK,booking.id(),1);
        })).kind());
        assertEquals(1,count("booking"));
    }

    @Test void onlyAFreshEnabledEnrolledMatchingEpochAdministratorSessionAuthorizes() {
        var good=request(3,9);
        // Not an administrator session: guests and anonymous browsers are forbidden.
        var guest=c.guest("synthetic@example.test");
        assertEquals(Failure.Kind.FORBIDDEN,fail(()->admin.create(c.binding(guest),"guest",good)).kind());
        try(var anonymous=c.sessions.anonymous()) { assertEquals(Failure.Kind.FORBIDDEN,fail(()->admin.create(c.binding(anonymous.session()),"anon",good)).kind()); }
        // Unknown references, wrong browsers and revoked sessions require authentication.
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,fail(()->admin.create(new SessionBinding(UUID.randomUUID(),session.browserHash()),"x",good)).kind());
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,fail(()->admin.create(new SessionBinding(session.sessionId(),"0".repeat(64)),"x",good)).kind());
        for(String change:List.of("UPDATE admin_user SET enabled=false","UPDATE admin_user SET enrolled=false","UPDATE admin_user SET password_hash='rotated'","UPDATE app_session SET revoked_at=clock_timestamp()")) {
            c.jdbc.update("DELETE FROM idempotency_record");
            setupFresh();c.jdbc.update(change);
            assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,fail(()->admin.create(session,"revoked",good)).kind(),change);
        }
        assertEquals(0,count("booking"));assertEquals(0,count("idempotency_record"));assertEquals(0,count("customer_identity WHERE email_key='customer@example.test'"));
    }
    void setupFresh() {
        c.jdbc.update("DELETE FROM app_session WHERE kind='ADMIN'");c.jdbc.update("DELETE FROM admin_recovery_code");c.jdbc.update("DELETE FROM admin_user");
        adminId=UUID.randomUUID();session=adminSession(adminId,true);
    }

    @Test void revocationAndEpochChangesAreCheckedBeforeSuccessfulReceiptReplay() {
        var good=request(3,9);Receipt first=create("replay",good);assertTrue(first.success());assertEquals(first,create("replay",good));
        c.jdbc.update("UPDATE admin_user SET totp_secret='rotated-secret'");
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,fail(()->create("replay",good)).kind());
        assertEquals(1,count("booking"));assertEquals(1,count("idempotency_record"));assertEquals(1,count("outbox_event"));
    }

    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void freshMultiFactorWindowIsExclusiveAtMicrosecondEquality(long micros) {
        Instant authenticated=c.jdbc.queryForObject("SELECT authenticated_at FROM app_session WHERE id=?",(r,n)->r.getTimestamp(1).toInstant(),session.sessionId());
        var reads=new AtomicInteger();var fixed=new AdminBooking(new PostgresAdminBooking(c.clock(authenticated.plusSeconds(600).plusNanos(micros*1000),reads)),"local-v1",Set.of("zoom.us"));
        var good=request(3,9);
        if(micros<0)assertTrue(fixed.create(session,"edge",good).success());
        else assertEquals(Failure.Kind.FRESH_AUTHENTICATION_REQUIRED,fail(()->fixed.create(session,"edge",good)).kind());
        assertEquals(1,reads.get());assertEquals(micros<0?1:0,count("booking"));
    }

    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void absoluteExpiryIsStrictAndAnIdleSessionIsAlwaysRefused(long micros) {
        Instant issued=c.jdbc.queryForObject("SELECT issued_at FROM app_session WHERE id=?",(r,n)->r.getTimestamp(1).toInstant(),session.sessionId());
        Instant absolute=issued.plus(Duration.ofHours(8));
        c.jdbc.update("UPDATE app_session SET last_seen_at=?,authenticated_at=? WHERE id=?",Timestamp.from(absolute.minusSeconds(1)),Timestamp.from(absolute.minusSeconds(1)),session.sessionId());
        var fixed=new AdminBooking(new PostgresAdminBooking(c.clock(absolute.plusNanos(micros*1000),new AtomicInteger())),"local-v1",Set.of("zoom.us"));
        var good=request(3,9);
        if(micros<0)assertTrue(fixed.create(session,"absolute",good).success());
        else assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,fail(()->fixed.create(session,"absolute",good)).kind());
        // Idle: the ten-minute MFA window is always stricter than the 15-minute idle limit, so an idle session is refused at and around the limit.
        UUID other=session(adminId,0).sessionId();
        c.jdbc.update("UPDATE app_session SET last_seen_at=issued_at+interval '1 second',authenticated_at=issued_at WHERE id=?",other);
        Instant idle=c.jdbc.queryForObject("SELECT last_seen_at+interval '15 minutes' FROM app_session WHERE id=?",(r,n)->r.getTimestamp(1).toInstant(),other);
        var idleUnit=new AdminBooking(new PostgresAdminBooking(c.clock(idle.plusNanos(micros*1000),new AtomicInteger())),"local-v1",Set.of("zoom.us"));
        var binding=new SessionBinding(other,"d".repeat(64));
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,fail(()->idleUnit.create(binding,"idle",request(4,9))).kind());
    }

    @Test void credentialsIssuedAfterTheCapturedTimeCannotAuthorize() {
        Instant issued=c.jdbc.queryForObject("SELECT issued_at FROM app_session WHERE id=?",(r,n)->r.getTimestamp(1).toInstant(),session.sessionId());
        var early=new AdminBooking(new PostgresAdminBooking(c.clock(issued.minusNanos(1000),new AtomicInteger())),"local-v1",Set.of("zoom.us"));
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,fail(()->early.create(session,"early",request(3,9))).kind());
        assertEquals(0,count("booking"));
    }

    @Test void twentyIndependentConnectionsCreateExactlyOneBookingForAContestedRange() throws Exception {
        var other=new AdminBooking(new PostgresAdminBooking(c.f.source),"local-v1",Set.of("zoom.us"));var start=day(3,9,0);
        var results=race(i->(i%2==0?admin:other).create(session,"race-"+i,request(start.plus(Duration.ofMinutes(30L*(i%4))),"race"+i+"@example.test",null,SchedulingRules.Override.none())));
        assertEquals(1,results.stream().filter(Receipt::success).count());assertEquals(19,results.stream().filter(r->r.code()==Code.SLOT_UNAVAILABLE).count());
        assertEquals(1,count("booking"));assertEquals(1,count("booking_occupancy"));assertEquals(1,count("outbox_event"));assertEquals(1,count("customer_identity"));
        assertEquals(20,count("idempotency_record"));
    }

    @Test void twentySameKeyRetriesCreateOneBookingAndOneSetOfEffects() throws Exception {
        var other=new AdminBooking(new PostgresAdminBooking(c.f.source),"local-v1",Set.of("zoom.us"));var request=request(3,9);
        var results=race(i->(i%2==0?admin:other).create(session,"same",request));
        assertEquals(1,new HashSet<>(results).size());assertTrue(results.getFirst().success());
        assertEquals(1,count("booking"));assertEquals(1,count("audit_event"));assertEquals(1,count("outbox_event"));assertEquals(1,count("idempotency_record"));
    }

    @Test void administratorAndCustomerContendingForOneRangeAcrossInstancesLeaveExactlyOneOccupant() throws Exception {
        var customers=new ArrayList<com.purpuragenda.application.port.out.SessionStore.Snapshot>();
        for(int i=0;i<10;i++)customers.add(c.guest("contender"+i+"@example.test"));
        var start=c.request(3,9).start();var other=new AdminBooking(new PostgresAdminBooking(c.f.source),"local-v1",Set.of("zoom.us"));
        var results=race(i->i<10?c.create(customers.get(i),"customer",c.request(3,9)):(i%2==0?admin:other).create(session,"admin-"+i,request(start,"admin"+i+"@example.test",null,SchedulingRules.Override.none())));
        assertEquals(1,results.stream().filter(Receipt::success).count());assertEquals(19,results.stream().filter(r->r.code()==Code.SLOT_UNAVAILABLE).count());
        assertEquals(1,count("booking_occupancy WHERE released_at IS NULL"));assertEquals(1,count("request_charge")+count("booking WHERE origin='ADMINISTRATOR'"));
    }

    @Test void concurrentAdministratorCreationsForOneNewCustomerShareOneIdentityAndHonorTheActiveAllowance() throws Exception {
        var other=new AdminBooking(new PostgresAdminBooking(c.f.source),"local-v1",Set.of("zoom.us"));
        var results=race(i->(i%2==0?admin:other).create(session,"k"+i,request(day(3+i,9,0),"Shared@Example.TEST",null,SchedulingRules.Override.none())));
        assertEquals(3,results.stream().filter(Receipt::success).count());
        assertTrue(results.stream().filter(r->!r.success()).allMatch(r->r.code()==Code.ACTIVE_LIMIT_REACHED||r.code()==Code.OUTSIDE_HORIZON));
        assertEquals(1,count("customer_identity"));assertEquals(0,count("request_charge"));assertEquals(3,count("outbox_event"));
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
