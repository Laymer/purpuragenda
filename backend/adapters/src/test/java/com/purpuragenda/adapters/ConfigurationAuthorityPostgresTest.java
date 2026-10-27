package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.postgres.PostgresConfiguration;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.AdministratorConfiguration;
import com.purpuragenda.domain.scheduling.AvailabilityCalendar;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual password/TOTP session authority, concurrent durable activity and one resource-captured configuration time. */
@Timeout(180)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class ConfigurationAuthorityPostgresTest {
    @TempDir Path directory;
    AdministratorLoginPostgresTest login;AdminMfaDatabaseTest f;AdministratorLoginPostgresTest.Browser browser;SessionBinding binding;
    @BeforeAll static void start() { AdministratorLoginPostgresTest.start(); }
    @AfterAll static void stop() { AdministratorLoginPostgresTest.stop(); }
    @BeforeEach void setup() throws Exception {
        login=new AdministratorLoginPostgresTest();login.directory=directory;login.setup();f=login.f;
        var anonymous=login.boot();var response=login.send(anonymous,AdministratorLoginPostgresTest.body(f.freshCode()));assertEquals(200,response.getStatus());
        String cookie=AdministratorLoginPostgresTest.value(response.getHeader("Set-Cookie"));
        browser=new AdministratorLoginPostgresTest.Browser(cookie,AdministratorLoginPostgresTest.csrf(response),login.sessions.inspect(cookie.toCharArray()).orElseThrow().reference());
        binding=new SessionBinding(browser.reference().id(),browser.reference().browserHash());
    }
    @AfterEach void cleanup() { login.cleanup(); }
    Receipt replace(DataSource source,String key) {
        var windows=new EnumMap<DayOfWeek,List<AvailabilityCalendar.Window>>(DayOfWeek.class);
        for(var day:DayOfWeek.values())windows.put(day,List.of(new AvailabilityCalendar.Window(600,1140)));
        return new AdministratorConfiguration(new PostgresConfiguration(source)).replaceWeekly(binding,key,1,windows,new AdministratorConfiguration.Review("Synthetic configuration reason",Set.of()));
    }
    int count(String sql) { return f.jdbc.queryForObject("SELECT count(*) FROM "+sql,Integer.class); }
    void unchanged() {
        assertEquals(1,f.jdbc.queryForObject("SELECT (calendar->>'version')::bigint FROM business_settings WHERE id=1",Long.class));
        assertEquals(0,count("audit_event WHERE type='CALENDAR_CHANGED'"));assertEquals(0,count("idempotency_record WHERE scope LIKE '%REPLACE_WEEKLY%'"));
    }
    @Test void concurrentOrdinaryActivityAfterResourceTimeCaptureDoesNotRejectConfiguration() throws Exception { interleaving(false); }
    @Test void revocationAfterResourceTimeCaptureStillRejectsConfigurationWithoutEffects() throws Exception { interleaving(true); }
    void interleaving(boolean revoke) throws Exception {
        var captured=new CountDownLatch(1);var changed=new CountDownLatch(1);var time=new AtomicReference<Instant>();var captures=new AtomicInteger();
        DataSource source=clock(now->{
            captures.incrementAndGet();time.set(now);captured.countDown();
            try { if(!changed.await(10,TimeUnit.SECONDS))throw new AssertionError("Expected concurrent session change"); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new AssertionError("Interrupted configuration interleaving"); }
            return now;
        });
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var result=pool.submit(()->replace(source,"concurrent"));assertTrue(captured.await(10,TimeUnit.SECONDS));
            try {
                if(revoke)assertTrue(login.sessions.revoke(browser.reference()));else {
                    assertTrue(login.sessions.resolve(browser.cookie().toCharArray()).isPresent());
                    Instant activity=f.jdbc.queryForObject("SELECT last_seen_at FROM app_session WHERE id=?",(row,n)->row.getTimestamp(1).toInstant(),binding.sessionId());
                    assertTrue(activity.isAfter(time.get()),"Session activity must follow the captured command time");
                }
            } finally { changed.countDown(); }
            if(revoke) {
                var failed=assertThrows(ExecutionException.class,()->result.get(20,TimeUnit.SECONDS));assertInstanceOf(Failure.class,failed.getCause());assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,((Failure)failed.getCause()).kind());unchanged();
            } else {
                var receipt=result.get(20,TimeUnit.SECONDS);assertTrue(receipt.success());assertEquals(2,receipt.version());assertEquals(1,count("audit_event WHERE type='CALENDAR_CHANGED'"));
                Instant retained=f.jdbc.queryForObject("SELECT last_seen_at FROM app_session WHERE id=?",(row,n)->row.getTimestamp(1).toInstant(),binding.sessionId());
                assertTrue(retained.isAfter(time.get()),"Configuration must not rewind the concurrently advanced activity");
            }
        }
        assertEquals(1,captures.get(),"Authorization must not recapture command time after activity or revocation");
    }
    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void futureFactorBoundaryRemainsInclusiveAtTheCapturedTime(long micros) {
        Instant captured=f.now().plusSeconds(1),factor=captured.plusNanos(micros*1000),issued=captured.minusSeconds(60);
        place(issued,factor.isAfter(captured)?factor:captured,factor);var calls=new AtomicInteger();DataSource source=clock(now->{calls.incrementAndGet();return captured;});
        if(micros>0) { assertEquals(Failure.Kind.AUTHENTICATION_REQUIRED,assertThrows(Failure.class,()->replace(source,"future-factor")).kind());unchanged(); }
        else assertTrue(replace(source,"future-factor").success());
        assertEquals(1,calls.get());
    }
    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void freshFactorDeadlineRemainsExclusiveAfterActivityCorrection(long micros) {
        Instant captured=f.now().plusSeconds(1),factor=captured.minusSeconds(600).plusNanos(micros*1000);
        place(captured.minusSeconds(660),captured,factor);
        if(micros<=0) { assertEquals(Failure.Kind.FRESH_AUTHENTICATION_REQUIRED,assertThrows(Failure.class,()->replace(clock(now->captured),"fresh-factor")).kind());unchanged(); }
        else assertTrue(replace(clock(now->captured),"fresh-factor").success());
    }
    @Test void twentyRealSessionResolutionsAndIdenticalCommandsCommitOneConfigurationReceipt() throws Exception {
        var ready=new CountDownLatch(20);var start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var results=new ArrayList<Future<Receipt>>();for(int i=0;i<20;i++)results.add(pool.submit(()->{
                ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));assertTrue(login.sessions.resolve(browser.cookie().toCharArray()).isPresent());return replace(f.source,"same-key");
            }));assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();var receipts=new HashSet<Receipt>();for(var result:results)receipts.add(result.get(45,TimeUnit.SECONDS));
            assertEquals(Set.of(new Receipt(Code.OK,AdministratorConfiguration.CALENDAR_ID,2)),receipts);
        }
        assertEquals(1,count("audit_event WHERE type='CALENDAR_CHANGED'"));assertEquals(1,count("idempotency_record WHERE scope LIKE '%REPLACE_WEEKLY%'"));
    }
    void place(Instant issued,Instant activity,Instant factor) {
        f.jdbc.update("UPDATE app_session SET issued_at=?,last_seen_at=?,authenticated_at=?,absolute_expires_at=? WHERE id=?",Timestamp.from(issued),Timestamp.from(activity),Timestamp.from(factor),Timestamp.from(issued.plusSeconds(28800)),binding.sessionId());
    }
    DataSource clock(UnaryOperator<Instant> capture) {
        return new DelegatingDataSource(f.source) {
            @Override public Connection getConnection() throws SQLException {
                var connection=super.getConnection();return GuestAccessDatabaseTest.proxy(Connection.class,(p,m,a)->{
                    Object statement=GuestAccessDatabaseTest.invoke(connection,m,a);if(!m.getName().equals("createStatement"))return statement;
                    return GuestAccessDatabaseTest.proxy(Statement.class,(sp,sm,sa)->{
                        Object rows=GuestAccessDatabaseTest.invoke(statement,sm,sa);
                        if(!sm.getName().equals("executeQuery") || !"SELECT clock_timestamp()".equals(sa[0]))return rows;
                        return GuestAccessDatabaseTest.proxy(ResultSet.class,(rp,rm,ra)->{
                            Object value=GuestAccessDatabaseTest.invoke(rows,rm,ra);return rm.getName().equals("getTimestamp") && Integer.valueOf(1).equals(ra[0])?Timestamp.from(capture.apply(((Timestamp)value).toInstant())):value;
                        });
                    });
                });
            }
        };
    }
}
