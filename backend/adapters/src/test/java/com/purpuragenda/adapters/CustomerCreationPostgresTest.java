package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.adapters.out.security.*;
import com.purpuragenda.application.port.out.AuthDeliveryStore.Issue;
import com.purpuragenda.application.port.out.AuthDeliveryStore.Purpose;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.*;
import com.purpuragenda.application.port.out.GuestAccessStore;
import com.purpuragenda.application.port.out.SessionStore;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.CustomerBooking;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.customer.Intake;
import com.purpuragenda.domain.scheduling.SchedulingPolicy;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(180)
@ResourceLock("postgres-migration-fixture")
class CustomerCreationPostgresTest {
    final GuestAccessDatabaseTest f=new GuestAccessDatabaseTest();
    @TempDir Path directory;
    JdbcTemplate jdbc;PostgresSessions sessions;PostgresCustomerBooking adapter;CustomerBooking command;PostgresScheduling scheduling;DataSource schedulingSource;
    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void setup() throws Exception {
        f.directory=directory;f.setup();jdbc=new JdbcTemplate(f.source);sessions=new PostgresSessions(f.source);
        schedulingSource=f.source;adapter=new PostgresCustomerBooking(f.source);command=new CustomerBooking(adapter,"local-v1");scheduling=new PostgresScheduling(f.source);
    }
    @AfterEach void cleanup() { f.cleanup(); }
    SessionStore.Snapshot guest(String email) { return guest(email,null); }
    SessionStore.Snapshot guest(String email,UUID booking) {
        try(var previous=sessions.anonymous()) {
            var reference=previous.session().reference();
            f.delivery.issue(new Issue(email,booking==null?Purpose.CREATE:Purpose.MANAGE,booking,booking==null?reference.browserHash():null));
            var lease=f.queue.claimAuth().orElseThrow();var message=f.delivery.prepare(lease).orElseThrow();assertTrue(f.queue.sent(lease));
            String token=message.text().split("#token=",2)[1].split("&",2)[0];
            var proof=new GuestAccessStore.Redemption(token,booking==null?GuestAccessStore.Kind.CREATE:GuestAccessStore.Kind.MANAGE,reference.browserHash());
            try(var issued=sessions.exchangeGuest(reference,proof).orElseThrow()) { return issued.session(); }
        }
    }
    SessionBinding binding(SessionStore.Snapshot session) { return new SessionBinding(session.reference().id(),session.reference().browserHash()); }
    Instant now() { return jdbc.queryForObject("SELECT clock_timestamp()",(r,n)->r.getTimestamp(1).toInstant()); }
    CustomerBooking.Request request(int day,int hour) {
        Instant start=now().atZone(SchedulingPolicy.BUSINESS_ZONE).toLocalDate().plusDays(day).atTime(hour,0).atZone(SchedulingPolicy.BUSINESS_ZONE).toInstant();
        return new CustomerBooking.Request(UUID.fromString(MigrationTest.SERVICE),1,1,start,
                new Intake("Synthetic","Customer","+32400123456",LocalDate.of(1990,1,1),LocalTime.NOON,"Synthetic city","Synthetic intake"),"America/New_York","local-v1");
    }
    Receipt create(SessionStore.Snapshot session,String key,CustomerBooking.Request request) { return command.create(binding(session),key,request); }
    Booking load(UUID id) { return scheduling.read(v->v.booking(id).orElseThrow()); }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
    CommandKey internal() { return new CommandKey(new Scope(UUID.randomUUID(),"SYNTHETIC",null),UUID.randomUUID().toString(),CommandKey.fingerprint(List.of("synthetic"))); }
    Failure failure(org.junit.jupiter.api.function.Executable work) {
        Failure failure=assertThrows(Failure.class,work);assertNull(failure.getCause());assertEquals(0,failure.getSuppressed().length);return failure;
    }
    @Test void verifiedGuestCommitsSnapshotsOccupancyChargeGrantBindingAndEffectsAtOneCapturedTime() {
        var session=guest("synthetic@example.test");var request=request(2,9);var clocks=new AtomicInteger();Instant captured=now();
        var result=new CustomerBooking(new PostgresCustomerBooking(clock(captured,clocks)),"local-v1").create(binding(session),"create",request);
        assertTrue(result.success());assertEquals(1,clocks.get());Booking booking=load(result.entityId());
        assertEquals(session.customerId(),booking.customerId());assertEquals(captured,booking.createdAt());assertEquals(request.intake(),booking.snapshot().intake());
        for(String table:List.of("booking","booking_snapshot","booking_occupancy","request_charge","customer_request_day","audit_event","outbox_event","idempotency_record"))assertEquals(1,count(table));
        assertEquals(0,count("payment_entry"));assertEquals(0,count("customer_account"));assertEquals(2,count("access_grant"));
        assertEquals("GUEST",f.value("SELECT actor_kind FROM audit_event"));assertEquals("REQUESTED",f.value("SELECT type FROM outbox_event"));
        assertEquals("t",f.value("SELECT g.booking_id=b.id AND g.consumed_at=b.created_at AND g.expires_at=g.issued_at+interval '30 minutes' FROM access_grant g JOIN booking b ON b.id=g.booking_id WHERE g.id=?",session.grantId()));
        assertEquals("t",f.value("SELECT c.booking_id=p.booking_id AND c.customer_id=p.customer_id AND c.browser_hash=p.browser_hash AND c.issued_at=p.consumed_at AND c.expires_at=p.issued_at+interval '2 hours' FROM access_grant c JOIN access_grant p ON p.id=c.parent_grant_id"));
        assertEquals(session.grantId()+"/CREATE_BOOKING/-",f.value("SELECT scope FROM idempotency_record"));
    }
    @Test void consumedCreationReplaysOnlyItsBoundSuccessAndCannotCreateAnotherBooking() {
        var session=guest("synthetic@example.test");var request=request(2,9);Receipt original=create(session,"original",request);
        assertEquals(original,create(session,"original",request));
        assertEquals(Code.IDEMPOTENCY_KEY_REUSED,create(session,"original",request(3,9)).code());
        assertEquals(Failure.Kind.FORBIDDEN,failure(()->create(session,"another",request(3,9))).kind());
        assertEquals(1,count("booking"));assertEquals(1,count("request_charge"));assertEquals(1,count("outbox_event"));
    }
    @ParameterizedTest @ValueSource(strings={"session","parent","child"})
    void revokedAuthorityIsCheckedBeforeSuccessfulReceiptReplay(String target) {
        var session=guest("synthetic@example.test");var request=request(2,9);assertTrue(create(session,"original",request).success());
        if(target.equals("session"))sessions.revoke(session.reference());
        else jdbc.update("UPDATE access_grant SET revoked_at=clock_timestamp() WHERE "+(target.equals("parent")?"id=?":"parent_grant_id=?"),session.grantId());
        Failure denied=failure(()->create(session,"original",request));
        assertEquals(target.equals("session")?Failure.Kind.AUTHENTICATION_REQUIRED:Failure.Kind.FORBIDDEN,denied.kind());
        assertEquals(1,count("booking"));assertEquals(1,count("idempotency_record"));
    }
    @Test void unknownWrongBrowserAnonymousAndManagementReferencesCannotCreate() {
        var session=guest("synthetic@example.test");var request=request(2,9);
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,failure(()->command.create(new SessionBinding(UUID.randomUUID(),session.reference().browserHash()),"key",request)).kind());
        assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,failure(()->command.create(new SessionBinding(session.reference().id(),"0".repeat(64)),"key",request)).kind());
        try(var anonymous=sessions.anonymous()) { assertEquals(Failure.Kind.FORBIDDEN,failure(()->create(anonymous.session(),"key",request)).kind()); }
        var created=create(session,"actual",request);var management=guest("synthetic@example.test",created.entityId());
        assertEquals(Failure.Kind.FORBIDDEN,failure(()->create(management,"key",request(3,9))).kind());assertEquals(1,count("booking"));
    }
    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void creationGrantExpiryIsStrictAtMicrosecondEquality(long micros) {
        var session=guest("synthetic@example.test");var request=request(2,9);
        Instant expires=jdbc.queryForObject("SELECT expires_at FROM access_grant WHERE id=?",(r,n)->r.getTimestamp(1).toInstant(),session.grantId());
        var reads=new AtomicInteger();var fixed=new CustomerBooking(new PostgresCustomerBooking(clock(expires.plusNanos(micros*1000),reads)),"local-v1");
        if(micros<0)assertTrue(fixed.create(binding(session),"boundary",request).success());
        else assertEquals(Failure.Kind.FORBIDDEN,failure(()->fixed.create(binding(session),"boundary",request)).kind());
        assertEquals(1,reads.get());assertEquals(micros<0?1:0,count("booking"));
    }
    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void idleSessionExpiryIsRecheckedBeforeBoundReplay(long micros) {
        var session=guest("synthetic@example.test");var request=request(2,9);Receipt created=create(session,"boundary",request);
        Instant expires=jdbc.queryForObject("SELECT last_seen_at+interval '30 minutes' FROM app_session WHERE id=?",(r,n)->r.getTimestamp(1).toInstant(),session.reference().id());
        var reads=new AtomicInteger();var fixed=new CustomerBooking(new PostgresCustomerBooking(clock(expires.plusNanos(micros*1000),reads)),"local-v1");
        if(micros<0)assertEquals(created,fixed.create(binding(session),"boundary",request));
        else assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,failure(()->fixed.create(binding(session),"boundary",request)).kind());
        assertEquals(1,reads.get());assertEquals(1,count("booking"));
    }
    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void derivedManagementExpiryCannotExtendCreationAuthority(long micros) {
        var session=guest("synthetic@example.test");var request=request(2,9);Receipt created=create(session,"boundary",request);
        Instant expires=jdbc.queryForObject("SELECT expires_at FROM access_grant WHERE parent_grant_id=?",(r,n)->r.getTimestamp(1).toInstant(),session.grantId());
        jdbc.update("UPDATE app_session SET last_seen_at=? WHERE id=?",Timestamp.from(expires.minusSeconds(1)),session.reference().id());
        var reads=new AtomicInteger();var fixed=new CustomerBooking(new PostgresCustomerBooking(clock(expires.plusNanos(micros*1000),reads)),"local-v1");
        if(micros<0)assertEquals(created,fixed.create(binding(session),"boundary",request));
        else assertEquals(Failure.Kind.FORBIDDEN,failure(()->fixed.create(binding(session),"boundary",request)).kind());
        assertEquals(1,reads.get());assertEquals(1,count("booking"));
    }
    @Test void independentSessionTouchNeverMovesLastSeenBackwardsOrInvalidatesTheCapturedCommand() {
        var session=guest("synthetic@example.test");var request=request(2,9);Instant captured=now(),touched=captured.plusSeconds(1);
        jdbc.update("UPDATE app_session SET last_seen_at=? WHERE id=?",Timestamp.from(touched),session.reference().id());
        var reads=new AtomicInteger();assertTrue(new CustomerBooking(new PostgresCustomerBooking(clock(captured,reads)),"local-v1").create(binding(session),"touch",request).success());
        assertEquals(touched,jdbc.queryForObject("SELECT last_seen_at FROM app_session WHERE id=?",(r,n)->r.getTimestamp(1).toInstant(),session.reference().id()));assertEquals(1,reads.get());
    }
    @ParameterizedTest @ValueSource(strings={"session","grant"})
    void revocationWhileWaitingForAuthorityLocksCannotAuthorizeCreation(String target) throws Exception {
        var session=guest("synthetic@example.test");var request=request(2,9);String table=target.equals("session")?"app_session":"access_grant";
        UUID id=target.equals("session")?session.reference().id():session.grantId();String name="creation-lock-"+UUID.randomUUID();f.source.setApplicationName(name);
        try(var blocker=f.database.connection(MigrationTest.APP);var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);MigrationTest.execute(blocker,"SELECT id FROM "+table+" WHERE id=? FOR UPDATE",id);
            var future=pool.submit(()->failure(()->create(session,"revoked",request)));boolean waiting=false;long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(System.nanoTime()<limit) {
                if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock'",Integer.class,name)>0){waiting=true;break;}
                Thread.sleep(10);
            }
            assertTrue(waiting);MigrationTest.execute(blocker,"UPDATE "+table+" SET revoked_at=clock_timestamp() WHERE id=?",id);blocker.commit();
            assertEquals(target.equals("session")?Failure.Kind.AUTHENTICATION_REQUIRED:Failure.Kind.FORBIDDEN,future.get(10,TimeUnit.SECONDS).kind());
        }
        assertEquals(0,count("booking"));assertEquals(0,count("idempotency_record"));assertNull(f.value("SELECT consumed_at FROM access_grant"));
    }
    @Test void existingAccountAuthorityUsesStableAccountScopeWithoutConsumingAGrant() {
        // Database fixture for an already authenticated account; P18 separately owns enrollment/session exchange.
        UUID customer=UUID.randomUUID(),account=UUID.randomUUID(),id=UUID.randomUUID();String browser="a".repeat(64);
        jdbc.update("INSERT INTO customer_identity VALUES(?,'account@example.test',clock_timestamp())",customer);
        jdbc.update("INSERT INTO customer_account VALUES(?,?,clock_timestamp())",account,customer);
        jdbc.update("""
            INSERT INTO app_session(id,token_hash,browser_hash,csrf_hash,kind,customer_id,account_id,issued_at,last_seen_at,absolute_expires_at,authenticated_at)
            SELECT ?,?,?,?,'ACCOUNT',?,?,t,t,t+interval '12 hours',t FROM (SELECT clock_timestamp() t) captured
            """,id,Secrets.hash("synthetic-account-token"),browser,Secrets.hash("synthetic-account-csrf"),customer,account);
        var reference=new SessionBinding(id,browser);Receipt first=command.create(reference,"first",request(2,9));
        assertTrue(first.success());assertTrue(command.create(reference,"second",request(3,9)).success());
        assertEquals(customer,load(first.entityId()).customerId());assertEquals(0,count("access_grant"));assertEquals(2,count("request_charge"));
        assertEquals(account+"/CREATE_BOOKING/-",f.value("SELECT scope FROM idempotency_record LIMIT 1"));assertEquals("ACCOUNT",f.value("SELECT actor_kind FROM audit_event LIMIT 1"));
    }
    @ParameterizedTest @ValueSource(strings={"access_grant","request_charge","audit_event","outbox_event"})
    void storageFailureRollsBackBookingChargeAuthorityAndEveryEffect(String table) {
        var session=guest("synthetic@example.test");var request=request(2,9);
        try(var c=f.database.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(c,"CREATE FUNCTION reject_creation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'SYNTHETIC_FAILURE'; END $$");
            MigrationTest.execute(c,"CREATE TRIGGER reject_creation BEFORE INSERT ON "+table+" FOR EACH ROW EXECUTE FUNCTION reject_creation()");
        } catch(SQLException failure) { throw MigrationTest.safe(failure); }
        assertEquals(Failure.Kind.STORAGE_FAILURE,failure(()->create(session,"rollback",request)).kind());
        for(String name:List.of("booking","booking_snapshot","booking_occupancy","request_charge","customer_request_day","audit_event","outbox_event","idempotency_record"))assertEquals(0,count(name));
        assertEquals(1,count("access_grant"));assertNull(f.value("SELECT consumed_at FROM access_grant"));
    }
    @Test void expectedConflictRetainsGrantAndCommitsExpiryEffectsWithoutANewCharge() {
        UUID due=f.database.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());
        var session=guest("synthetic@example.test");var original=request(2,9);
        var stale=new CustomerBooking.Request(original.serviceId(),2,1,original.start(),original.intake(),original.displayZone(),original.noticeVersion());
        assertEquals(Code.SERVICE_CHANGED,create(session,"stale",stale).code());assertEquals(Booking.State.EXPIRED,load(due).state());
        assertEquals(1,count("audit_event"));assertEquals(1,count("outbox_event"));assertEquals(0,count("request_charge"));assertNull(f.value("SELECT consumed_at FROM access_grant"));
        assertEquals(Code.SERVICE_CHANGED,create(session,"stale",stale).code());assertEquals(1,count("outbox_event"));
        assertTrue(create(session,"fresh-review",original).success());assertEquals(1,count("request_charge"));
    }
    @Test void unknownFailureAlsoRollsBackEarlierExpiryAndDoesNotLeaveAReceipt() {
        UUID due=f.database.booking(MigrationTest.START,"PENDING_CONFIRMATION",MigrationTest.snapshot());var session=guest("synthetic@example.test");
        var request=new Command(binding(session),"unknown",CommandKey.fingerprint(List.of("synthetic")));
        assertThrows(IllegalStateException.class,()->adapter.create(request,tx->com.purpuragenda.application.scheduling.SchedulingCommands.within(tx,()->{throw new IllegalStateException("SYNTHETIC_FAILURE");})));
        assertEquals(Booking.State.PENDING_CONFIRMATION,load(due).state());assertEquals(0,count("outbox_event"));assertEquals(0,count("idempotency_record"));
    }
    @Test void twentySameKeysAcrossTwoAdapterInstancesCommitOneBookingAndOneReceipt() throws Exception {
        var session=guest("synthetic@example.test");var request=request(2,9);
        var results=race(Collections.nCopies(20,session),i->request,true);assertEquals(1,new HashSet<>(results).size());assertTrue(results.getFirst().success());
        assertEquals(1,count("booking"));assertEquals(1,count("request_charge"));assertEquals(1,count("idempotency_record"));
    }
    @Test void twentyIndependentAuthoritiesCannotOverlapEvenAcrossTwoInstances() throws Exception {
        var request=request(2,9);var results=race(guests(false),i->request,false);
        assertEquals(1,results.stream().filter(Receipt::success).count());assertEquals(19,results.stream().filter(r->r.code()==Code.SLOT_UNAVAILABLE).count());
        assertEquals(1,count("booking_occupancy"));assertEquals(1,count("request_charge"));assertEquals(20,count("idempotency_record"));
    }
    @Test void twentyDistinctCustomersCannotExceedTheFinalDailyCapacity() throws Exception {
        var requests=new ArrayList<CustomerBooking.Request>();for(int hour=9;hour<=19;hour+=2)requests.add(request(2,hour));
        var results=race(guests(false),i->requests.get(i%requests.size()),false);
        assertEquals(3,results.stream().filter(Receipt::success).count());assertEquals(3,count("booking"));assertEquals(3,count("request_charge"));
    }
    @Test void twentyFreshGrantsForOneStableIdentityCannotExceedActiveAllowance() throws Exception {
        var requests=new ArrayList<CustomerBooking.Request>();for(int day=2;day<22;day++)requests.add(request(day,9));
        var results=race(guests(true),requests::get,false);assertEquals(3,results.stream().filter(Receipt::success).count());
        assertEquals(17,results.stream().filter(r->r.code()==Code.ACTIVE_LIMIT_REACHED).count());assertEquals(1,count("customer_identity"));assertEquals(3,count("request_charge"));
    }
    @Test void twentyRequestsCannotReuseChargesAfterCancelledBookingsReleaseActiveAllowance() throws Exception {
        var authorities=guests(true);var seeds=List.of(guest("same@example.test"),guest("same@example.test"));
        // Prepare every proof before freezing time: a live-clock midnight between seeds and the race is a new quota day.
        schedulingSource=clock(now(),new AtomicInteger());command=new CustomerBooking(new PostgresCustomerBooking(schedulingSource),"local-v1");scheduling=new PostgresScheduling(schedulingSource);
        for(int day=2;day<4;day++) {
            var created=create(seeds.get(day-2),"seed",request(day,9));assertTrue(created.success());
            scheduling.execute(internal(),tx->tx.attempt(()->{Booking b=tx.booking(created.entityId()).orElseThrow().cancel(tx.now(),false);tx.save(b);return new Receipt(Code.OK,b.id(),b.version());}));
        }
        var requests=new ArrayList<CustomerBooking.Request>();for(int day=2;day<22;day++)requests.add(request(day,9));
        var results=race(authorities,requests::get,false);assertEquals(1,results.stream().filter(Receipt::success).count());
        assertEquals(19,results.stream().filter(r->r.code()==Code.REQUEST_LIMIT_REACHED).count());assertEquals(3,count("request_charge"));assertEquals(1,count("booking WHERE state='PENDING_CONFIRMATION'"));
    }
    List<SessionStore.Snapshot> guests(boolean same) { var result=new ArrayList<SessionStore.Snapshot>();for(int i=0;i<20;i++)result.add(guest(same?"same@example.test":"synthetic"+i+"@example.test"));return result; }
    List<Receipt> race(List<SessionStore.Snapshot> authorities,IntFunction<CustomerBooking.Request> requests,boolean sameKey) throws Exception {
        var other=new CustomerBooking(new PostgresCustomerBooking(schedulingSource),"local-v1");var ready=new CountDownLatch(20);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<Future<Receipt>>();
            for(int i=0;i<20;i++) { int index=i;futures.add(pool.submit(()->{ready.countDown();release.await();return (index%2==0?command:other).create(binding(authorities.get(index)),sameKey?"same":"request-"+index,requests.apply(index));})); }
            assertTrue(ready.await(5,TimeUnit.SECONDS));release.countDown();var results=new ArrayList<Receipt>();for(var future:futures)results.add(future.get(40,TimeUnit.SECONDS));return results;
        }
    }
    DataSource clock(Instant captured,AtomicInteger reads) {
        return new DelegatingDataSource(f.source) {
            @Override public Connection getConnection() throws SQLException {
                var connection=super.getConnection();
                return GuestAccessDatabaseTest.proxy(Connection.class,(p,m,a)->{
                    Object result=GuestAccessDatabaseTest.invoke(connection,m,a);if(!m.getName().equals("createStatement"))return result;
                    return GuestAccessDatabaseTest.proxy(Statement.class,(sp,sm,sa)->{
                        Object rows=GuestAccessDatabaseTest.invoke(result,sm,sa);
                        if(!sm.getName().equals("executeQuery") || !"SELECT clock_timestamp()".equals(sa[0]))return rows;
                        reads.incrementAndGet();return GuestAccessDatabaseTest.proxy(ResultSet.class,(rp,rm,ra)->rm.getName().equals("getTimestamp") && Integer.valueOf(1).equals(ra[0])?Timestamp.from(captured):GuestAccessDatabaseTest.invoke(rows,rm,ra));
                    });
                });
            }
        };
    }
}
