package com.purpuragenda.adapters;

import com.purpuragenda.adapters.in.rest.*;
import com.purpuragenda.adapters.out.calendar.ICalendarWriter;
import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.calendar.CalendarDownloads;
import com.purpuragenda.application.port.out.CustomerBookingQuery;
import jakarta.servlet.http.Cookie;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@Timeout(240) @org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class CalendarCustomerHttpPostgresTest {
    final CustomerBookingHttpPostgresTest h=new CustomerBookingHttpPostgresTest();@TempDir Path directory;
    @BeforeAll static void start(){MigrationTest.start();}
    @AfterAll static void stop(){MigrationTest.stop();}
    @BeforeEach void setup() throws Exception {h.directory=directory;h.setup();}
    @AfterEach void cleanup(){h.cleanup();}
    static CalendarDownloadController controller(DataSource source,SessionAccess access,SessionCookies cookies) {
        return new CalendarDownloadController(new CalendarDownloads(new PostgresCustomerBookingQuery(source,Set.of("zoom.us")),
                new PostgresAdministratorBookingQuery(source,Set.of("zoom.us")),new ICalendarWriter()),access,cookies);
    }
    static String path(String id){return "/api/v1/bookings/"+id+"/calendar";}
    MockHttpServletRequestBuilder read(GuestAuthPostgresTest.Browser browser,String id){return get(path(id)).cookie(new Cookie("__Host-purpuragenda",browser.cookie()));}
    static String calendar(MockHttpServletResponse response) throws Exception {
        assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));
        assertEquals("attachment; filename=\"consultation.ics\"",response.getHeader("Content-Disposition"));
        assertEquals("text/calendar;charset=UTF-8",response.getContentType());assertEquals("no-referrer",response.getHeader("Referrer-Policy"));
        assertEquals("nosniff",response.getHeader("X-Content-Type-Options"));assertNull(response.getHeader("ETag"));assertNull(response.getHeader("Last-Modified"));assertNull(response.getHeader("Set-Cookie"));
        return new String(response.getContentAsByteArray(),StandardCharsets.UTF_8).replace("\r\n ","");
    }
    @Test void realProofDownloadIsPrivateReadOnlyAndIgnoresCacheAndRangeValidators() throws Exception {
        var browser=h.guest("calendar@example.test");String id=h.booking(h.create(browser,"calendar",h.body(3)));
        String seen=h.c.f.value("SELECT last_seen_at FROM app_session WHERE browser_hash=?",browser.browserHash());int events=h.c.count("outbox_event"),receipts=h.c.count("idempotency_record");
        String text=calendar(h.g.send(read(browser,id).header("If-None-Match","*").header("If-Modified-Since","Wed, 01 Jan 2099 00:00:00 GMT").header("Range","bytes=0-10")));
        assertTrue(text.contains("UID:"+id+"-customer@purpuragenda\r\n"));assertTrue(text.contains("STATUS:TENTATIVE\r\n"));
        assertTrue(text.contains("SEQUENCE:0\r\n"));assertTrue(text.contains("manual updates or removal"));
        for(String forbidden:List.of("Synthetic","birth","calendar@example.test","ATTENDEE","METHOD:","RSVP","https://"))assertFalse(text.contains(forbidden));
        var head=h.g.send(head(path(id)).cookie(new Cookie("__Host-purpuragenda",browser.cookie())));assertEquals(200,head.getStatus());assertEquals(0,head.getContentAsByteArray().length);assertTrue(head.getContentLength()>0);
        assertEquals(seen,h.c.f.value("SELECT last_seen_at FROM app_session WHERE browser_hash=?",browser.browserHash()));
        assertEquals(events,h.c.count("outbox_event"));assertEquals(receipts,h.c.count("idempotency_record"));
        assertEquals(1,h.c.load(UUID.fromString(id)).version());
    }
    @Test void exactGrantUnknownAndOtherIdentityRemainIndistinguishableAndRevocationWins() throws Exception {
        var browser=h.guest("one-calendar@example.test");String id=h.booking(h.create(browser,"mine",h.body(3)));
        var same=h.c.guest("one-calendar@example.test");var rival=h.c.guest("rival-calendar@example.test");
        for(String target:List.of(h.c.create(same,"same",h.c.request(4,9)).entityId().toString(),h.c.create(rival,"rival",h.c.request(5,9)).entityId().toString(),UUID.randomUUID().toString()))
            assertEquals(404,h.g.send(read(browser,target)).getStatus());
        assertEquals(401,h.g.send(get(path(id))).getStatus());assertEquals(400,h.g.send(read(browser,"bad")).getStatus());
        assertEquals(400,h.g.send(read(browser,id).queryParam("audience","OWNER")).getStatus());
        assertEquals(400,h.g.send(read(browser,id).queryParam("token","synthetic")).getStatus());
        h.c.jdbc.update("UPDATE access_grant SET revoked_at=clock_timestamp() WHERE booking_id=?",UUID.fromString(id));
        assertEquals(401,h.g.send(read(browser,id)).getStatus());
    }
    @Test void directManagementProofDownloadsOnlyItsTarget() throws Exception {
        var owner=h.c.guest("managed-calendar@example.test");String id=h.c.create(owner,"managed",h.c.request(3,9)).entityId().toString();
        var browser=h.g.boot();String body=h.json.createObjectNode().put("email","managed-calendar@example.test").put("reference",h.c.load(UUID.fromString(id)).reference()).toString();
        assertEquals(202,h.g.send(h.g.json(browser,"/api/v1/guest/management-proofs",body)).getStatus());
        var redemption=h.g.send(h.g.json(browser,"/api/v1/guest/redemptions",h.g.redeemBody(h.g.proofToken(),"MANAGE")));assertEquals(200,redemption.getStatus());
        String cookie=redemption.getHeader("Set-Cookie").split(";",2)[0].split("=",2)[1];
        assertTrue(calendar(h.g.send(get(path(id)).cookie(new Cookie("__Host-purpuragenda",cookie)))).contains("-customer@purpuragenda"));
    }
    @Test void committedRevocationBetweenCookieInspectionAndSnapshotDeniesDownload() throws Exception {
        var browser=h.guest("race-calendar@example.test");String id=h.booking(h.create(browser,"race",h.body(3)));
        var query=new PostgresCustomerBookingQuery(h.c.f.source,Set.of("zoom.us"));
        CustomerBookingQuery revoked=(binding,target)->{h.c.sessions.revoke(new com.purpuragenda.application.port.out.SessionStore.SessionReference(binding.sessionId(),binding.browserHash()));return query.find(binding,target);};
        var controller=new CalendarDownloadController(new CalendarDownloads(revoked,new PostgresAdministratorBookingQuery(h.c.f.source,Set.of("zoom.us")),new ICalendarWriter()),new SessionAccess(h.c.sessions),SessionCookies.operational());
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();var response=mvc.perform(read(browser,id)).andReturn().getResponse();
        assertEquals(404,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(response.getHeader("Content-Disposition"));
        var next=mvc.perform(read(browser,id)).andReturn().getResponse();
        assertEquals(401,next.getStatus());assertEquals("no-store",next.getHeader("Cache-Control"));assertNull(next.getHeader("Content-Disposition"));
    }
}
