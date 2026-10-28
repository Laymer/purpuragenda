package com.purpuragenda.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.purpuragenda.adapters.in.rest.*;
import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.adapters.out.security.AdminCrypto;
import com.purpuragenda.application.*;
import com.purpuragenda.application.scheduling.*;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Real MFA precedes private schedule/policy HTTP; previews are advisory and commands re-review under the resource lock. */
@Timeout(240)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class AdministratorConfigurationHttpPostgresTest {
    static final String PATH="/api/v1/admin/configuration",REASON="Synthetic configuration reason";
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final ZoneId BRUSSELS=ZoneId.of("Europe/Brussels");
    @TempDir Path directory;
    AdministratorBookingHttpPostgresTest reads;AdministratorLoginPostgresTest login;AdminMfaDatabaseTest f;MockMvc mvc;
    @BeforeAll static void start() { AdminMfaDatabaseTest.start(); }
    @AfterAll static void stop() { AdminMfaDatabaseTest.stop(); }
    @BeforeEach void setup() throws Exception {
        reads=new AdministratorBookingHttpPostgresTest();reads.directory=directory;reads.setup();login=reads.login;f=reads.f;
        var store=new PostgresConfiguration(f.source,Set.of("zoom.us"));
        mvc=MockMvcBuilders.standaloneSetup(new SessionController(login.access,login.cookies),new AdministratorLoginController(
                new AdministratorLogin(new RequestThrottle(login.limits),f.mfa,login.sessions,new AdminCrypto()),login.cookies,Duration.ZERO),
                new AdministratorConfigurationController(new AdministratorConfiguration(store),new AdministratorConfigurationView(store),login.access,login.cookies))
                .addFilters(new ApiGuardFilter(AdministratorLoginPostgresTest.ORIGIN,login.access,login.cookies,new ClientAddress(List.of()))).build();
        login.mvc=mvc;reads.mvc=mvc;
    }
    @AfterEach void cleanup() { reads.cleanup(); }
    LocalDate today() { return f.now().atZone(BRUSSELS).toLocalDate(); }
    MockHttpServletResponse read(AdministratorLoginPostgresTest.Browser browser) throws Exception {
        return mvc.perform(get(PATH).cookie(new Cookie("__Host-purpuragenda",browser.cookie()))).andReturn().getResponse();
    }
    JsonNode current(AdministratorLoginPostgresTest.Browser admin) throws Exception {
        var response=read(admin);assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(response.getHeader("ETag"));
        return JSON.readTree(response.getContentAsString());
    }
    MockHttpServletResponse send(AdministratorLoginPostgresTest.Browser browser,String action,String key,ObjectNode body,String version) throws Exception {
        var request=login.json(browser,PATH+action,body.toString());if(key!=null)request.header("Idempotency-Key",key);if(version!=null)request.header("If-Match",version);
        return mvc.perform(request).andReturn().getResponse();
    }
    ObjectNode review(UUID... acknowledged) {
        var body=JSON.createObjectNode().put("reason",REASON);var ids=body.putArray("acknowledgedBookingIds");for(UUID id:acknowledged)ids.add(id.toString());return body;
    }
    ObjectNode weekly(String start,String end) {
        var weekly=JSON.createObjectNode();for(DayOfWeek day:DayOfWeek.values())weekly.putArray(day.name()).addObject().put("start",start).put("end",end);return weekly;
    }
    ObjectNode policy(int grid,int capacity,String notice,String cutoff) {
        return JSON.createObjectNode().put("gridMinutes",grid).put("dailyCapacity",capacity).put("minimumNotice",notice)
                .put("pendingLifetime","PT24H").put("expiryBuffer","PT1H").put("customerCutoff",cutoff);
    }
    long version(MockHttpServletResponse response,String field) throws Exception {
        assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));
        var body=JSON.readTree(response.getContentAsString());assertEquals(1,body.size());long value=body.get(field).asLong();
        assertEquals("\""+value+"\"",response.getHeader("ETag"));return value;
    }

    @Test void realOwnerReviewsExactConflictsBeforeAWeeklyChangeThatNeverMovesTheBooking() throws Exception {
        var admin=reads.administrator();
        var initial=current(admin);assertEquals(1,initial.get("calendarVersion").asLong());assertEquals(1,initial.get("policyVersion").asLong());
        assertEquals("09:00",initial.at("/weekly/MONDAY/0/start").asText());assertEquals("20:00",initial.at("/weekly/MONDAY/0/end").asText());
        assertEquals("PT24H",initial.at("/policy/minimumNotice").asText());assertEquals(30,initial.at("/policy/gridMinutes").asInt());
        Instant start=today().plusDays(3).atTime(10,0).atZone(BRUSSELS).toInstant();UUID booking=f.fixture.booking(start,"CONFIRMED",MigrationTest.snapshot());
        String before=f.jdbc.queryForObject("SELECT start_at::text||version FROM booking WHERE id=?",String.class,booking);

        var proposal=JSON.createObjectNode();proposal.putObject("proposal").set("weekly",weekly("12:00","20:00"));
        ((ObjectNode)proposal.get("proposal")).putArray("dateExceptions");((ObjectNode)proposal.get("proposal")).putArray("blackouts");
        var preview=send(admin,"/conflicts",null,proposal,null);assertEquals(200,preview.getStatus());assertEquals("no-store",preview.getHeader("Cache-Control"));
        var conflicts=JSON.readTree(preview.getContentAsString());assertEquals(1,conflicts.get("calendarVersion").asLong());
        assertEquals(booking.toString(),conflicts.at("/conflicts/0/bookingId").asText());assertEquals("START_RULES",conflicts.at("/conflicts/0/reasons/0").asText());
        assertEquals(start.toString(),conflicts.at("/conflicts/0/startInstant").asText());assertFalse(conflicts.at("/conflicts/0").has("intake"));

        var change=review();change.set("weekly",weekly("12:00","20:00"));
        login.problem(send(admin,"/weekly","weekly",change,"\"1\""),409,"CONFLICT_REVIEW_REQUIRED");
        var reviewed=review(booking);reviewed.set("weekly",weekly("12:00","20:00"));
        assertEquals(2,version(send(admin,"/weekly","weekly-reviewed",reviewed,"\"1\""),"calendarVersion"));
        assertEquals(2,version(send(admin,"/weekly","weekly-reviewed",reviewed,"\"1\""),"calendarVersion"));
        login.problem(send(admin,"/weekly","weekly-reviewed",change,"\"1\""),409,"IDEMPOTENCY_KEY_REUSED");
        login.problem(send(admin,"/weekly","stale",reviewed,"\"1\""),412,"CALENDAR_CHANGED");
        assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE type='CONFIGURATION_CONFLICT_ACKNOWLEDGED' AND booking_id=?",Integer.class,booking));
        assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE type='CONFIGURATION_CONFLICT'",Integer.class));
        assertEquals(before,f.jdbc.queryForObject("SELECT start_at::text||version FROM booking WHERE id=?",String.class,booking));
        var after=current(admin);assertEquals(2,after.get("calendarVersion").asLong());assertEquals("12:00",after.at("/weekly/SUNDAY/0/start").asText());
    }

    @Test void dateExceptionsBlackoutsAndPolicyRoundTripThroughVersionedCommands() throws Exception {
        var admin=reads.administrator();String date=today().plusDays(10).toString();
        var exception=review();exception.putObject("dateException").put("date",date).put("mode","REPLACE").putArray("windows").addObject().put("start","10:00").put("end","24:00");
        assertEquals(2,version(send(admin,"/date-exceptions","exception",exception,"\"1\""),"calendarVersion"));
        var state=current(admin);assertEquals(date,state.at("/dateExceptions/0/date").asText());assertEquals("24:00",state.at("/dateExceptions/0/windows/0/end").asText());
        assertEquals(3,version(send(admin,"/date-exceptions/"+date+"/removal","remove-exception",review(),"\"2\""),"calendarVersion"));
        login.problem(send(admin,"/date-exceptions/"+date+"/removal","remove-again",review(),"\"3\""),400,"INVALID_CONFIGURATION");

        UUID blackoutId=UUID.randomUUID();Instant from=today().plusDays(20).atTime(9,0).atZone(BRUSSELS).toInstant();
        var blackout=review();blackout.putObject("blackout").put("id",blackoutId.toString()).put("startInstant",from.toString())
                .put("endInstant",from.plus(Duration.ofHours(4)).toString()).put("reason","Synthetic closure reason");
        assertEquals(4,version(send(admin,"/blackouts","blackout",blackout,"\"3\""),"calendarVersion"));
        assertEquals(blackoutId.toString(),current(admin).at("/blackouts/0/id").asText());
        assertEquals(5,version(send(admin,"/blackouts/"+blackoutId+"/removal","remove-blackout",review(),"\"4\""),"calendarVersion"));
        assertEquals(0,current(admin).get("blackouts").size());

        var proposal=JSON.createObjectNode();proposal.set("proposal",policy(60,2,"PT12H","PT0S"));
        var preview=JSON.readTree(send(admin,"/conflicts",null,proposal,null).getContentAsString());assertEquals(0,preview.get("conflicts").size());assertEquals(1,preview.get("policyVersion").asLong());
        var update=review();update.set("policy",policy(60,2,"PT12H","PT0S"));
        assertEquals(2,version(send(admin,"/policy","policy",update,"\"1\""),"policyVersion"));
        var after=current(admin).get("policy");assertEquals(60,after.get("gridMinutes").asInt());assertEquals("PT12H",after.get("minimumNotice").asText());assertEquals("PT0S",after.get("customerCutoff").asText());
        login.problem(send(admin,"/policy","policy-stale",update,"\"1\""),412,"POLICY_CHANGED");
        assertEquals(2,f.jdbc.queryForObject("SELECT count(*) FROM policy_version",Integer.class));
    }

    @Test void guardsAuthorityAndStrictBodiesNeverChangeConfiguration() throws Exception {
        var admin=reads.administrator();var anonymous=login.boot();var change=review();change.set("weekly",weekly("10:00","18:00"));
        login.problem(read(anonymous),403,"ADMIN_ACCESS_REQUIRED");login.problem(send(anonymous,"/weekly","anonymous",change,"\"1\""),403,"ADMIN_ACCESS_REQUIRED");
        assertEquals(401,mvc.perform(get(PATH)).andReturn().getResponse().getStatus());
        login.problem(send(admin,"/weekly","missing-version",change,null),428,"VERSION_REQUIRED");
        login.problem(send(admin,"/weekly",null,change,"\"1\""),400,"INVALID_REQUEST");
        var proposal=JSON.createObjectNode();proposal.set("proposal",policy(30,3,"PT24H","PT24H"));
        login.problem(send(admin,"/conflicts",null,proposal,"\"1\""),400,"INVALID_REQUEST");
        var sixDays=review();var days=weekly("10:00","18:00");days.remove("SUNDAY");sixDays.set("weekly",days);
        var lateStart=review();lateStart.set("weekly",weekly("24:00","24:00"));
        var extra=review();extra.set("weekly",weekly("10:00","18:00"));extra.put("unexpected",true);
        var duration=review();duration.set("policy",policy(30,3,"P1D","PT24H"));
        for(var invalid:List.of(sixDays,lateStart,extra))login.problem(send(admin,"/weekly","invalid",invalid,"\"1\""),400,"INVALID_REQUEST");
        login.problem(send(admin,"/policy","invalid-duration",duration,"\"1\""),400,"INVALID_REQUEST");
        login.problem(send(admin,"/date-exceptions/2026-13-01/removal","invalid-date",review(),"\"1\""),400,"INVALID_REQUEST");

        // Reads need completed MFA only; commands need fresh MFA.
        f.jdbc.update("UPDATE app_session SET issued_at=issued_at-interval '602 seconds',absolute_expires_at=absolute_expires_at-interval '602 seconds',authenticated_at=issued_at-interval '601 seconds' WHERE id=?",admin.reference().id());
        assertEquals(1,current(admin).get("calendarVersion").asLong());
        login.problem(send(admin,"/weekly","not-fresh",change,"\"1\""),403,"AUTHENTICATION_REQUIRED");
        f.jdbc.update("UPDATE admin_user SET security_version=security_version+1");
        login.problem(read(admin),401,"SESSION_REQUIRED");login.problem(send(admin,"/conflicts",null,proposal,null),401,"SESSION_REQUIRED");
        assertEquals(1,f.jdbc.queryForObject("SELECT calendar->>'version' FROM business_settings WHERE id=1",Long.class));
        assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE type IN ('CALENDAR_CHANGED','POLICY_CHANGED')",Integer.class));
    }
}
