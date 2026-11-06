package com.purpuragenda.adapters;

import com.purpuragenda.application.port.out.AdministratorBookingQuery;
import com.purpuragenda.application.port.out.AdministratorBookingQuery.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@Timeout(240) @org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class CalendarOwnerHttpPostgresTest {
    final AdministratorBookingHttpPostgresTest h=new AdministratorBookingHttpPostgresTest();@TempDir Path directory;
    @BeforeAll static void start(){AdminMfaDatabaseTest.start();}
    @AfterAll static void stop(){AdminMfaDatabaseTest.stop();}
    @BeforeEach void setup() throws Exception {h.directory=directory;h.setup();}
    @AfterEach void cleanup(){h.cleanup();}
    String path(UUID id){return "/api/v1/admin/bookings/"+id+"/calendar";}
    @Test void actualMfaExportsCurrentRevisionMeetingAndCancellationWithoutConditionalResponses() throws Exception {
        var id=h.book("CONFIRMED");var admin=h.administrator();
        h.f.jdbc.update("UPDATE booking SET calendar_revision=7,version=9 WHERE id=?",id);
        String seen=h.f.jdbc.queryForObject("SELECT last_seen_at::text FROM app_session WHERE id=?",String.class,admin.reference().id());
        String first=CalendarCustomerHttpPostgresTest.calendar(h.send(h.privateGet(admin,path(id)).header("If-None-Match","*")));
        assertTrue(first.contains("UID:"+id+"-owner@purpuragenda\r\n"));assertTrue(first.contains("SEQUENCE:7\r\n"));
        assertTrue(first.contains("STATUS:CONFIRMED\r\n"));assertTrue(first.contains("Meeting link: https://zoom.us/j/"));
        h.f.jdbc.update("UPDATE booking SET meeting_url='https://zoom.us/j/987654321',meeting_id='987654321',calendar_revision=8,version=10 WHERE id=?",id);
        String changed=CalendarCustomerHttpPostgresTest.calendar(h.send(h.privateGet(admin,path(id))));assertTrue(changed.contains("SEQUENCE:8\r\n"));assertTrue(changed.contains("https://zoom.us/j/987654321"));
        new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(h.f.source)).executeWithoutResult(tx->{
            h.f.jdbc.update("UPDATE booking SET state='CANCELLED',calendar_revision=9,version=11 WHERE id=?",id);
            h.f.jdbc.update("UPDATE booking_occupancy SET released_at=clock_timestamp() WHERE booking_id=?",id);
        });
        String cancelled=CalendarCustomerHttpPostgresTest.calendar(h.send(h.privateGet(admin,path(id))));
        assertTrue(cancelled.contains("STATUS:CANCELLED\r\n"));assertTrue(cancelled.contains("SEQUENCE:9\r\n"));assertFalse(cancelled.contains("https://"));
        assertEquals(seen,h.f.jdbc.queryForObject("SELECT last_seen_at::text FROM app_session WHERE id=?",String.class,admin.reference().id()));
    }
    @Test void expiredPendingSnapshotAndMissingTargetsNeverReclaimOrWrite() throws Exception {
        UUID id=h.book("PENDING_CONFIRMATION");var admin=h.administrator();int events=h.f.count("outbox_event"),audit=h.f.count("audit_event");
        String calendar=CalendarCustomerHttpPostgresTest.calendar(h.send(h.privateGet(admin,path(id))));assertTrue(calendar.contains("STATUS:CANCELLED\r\n"));
        assertEquals("PENDING_CONFIRMATION",h.f.jdbc.queryForObject("SELECT state FROM booking WHERE id=?",String.class,id));
        assertEquals(events,h.f.count("outbox_event"));assertEquals(audit,h.f.count("audit_event"));
        assertEquals(404,h.send(h.privateGet(admin,path(UUID.randomUUID()))).getStatus());assertEquals(400,h.send(h.privateGet(admin,path(id)).queryParam("audience","CUSTOMER")).getStatus());
    }
    @Test void ownerRouteRequiresMfaAndRechecksEpochAfterCookieInspection() throws Exception {
        UUID id=h.book("CONFIRMED");var anonymous=h.login.boot();
        assertEquals(401,h.send(get(path(id))).getStatus());assertEquals(403,h.send(h.privateGet(anonymous,path(id))).getStatus());
        var admin=h.administrator();
        h.build(new AdministratorBookingQuery() {
            public Page agenda(SessionBinding s,Window w,Cursor c,int n){throw new AssertionError();}
            public Optional<Detail> detail(SessionBinding s,UUID target){h.f.jdbc.update("UPDATE admin_user SET security_version=security_version+1");return h.query.detail(s,target);}
        });
        var denied=h.send(h.privateGet(admin,path(id)));assertEquals(401,denied.getStatus());assertEquals("no-store",denied.getHeader("Cache-Control"));assertNull(denied.getHeader("Content-Disposition"));
    }
}
