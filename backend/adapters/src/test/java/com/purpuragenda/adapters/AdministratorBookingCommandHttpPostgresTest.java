package com.purpuragenda.adapters;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.purpuragenda.adapters.in.rest.*;
import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.adapters.out.security.AdminCrypto;
import com.purpuragenda.application.*;
import com.purpuragenda.application.scheduling.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;

/** Real MFA precedes manual creation; overrides are explicit and audited, overlap and stale factors are never bypassed. */
@Timeout(240)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class AdministratorBookingCommandHttpPostgresTest {
    static final String PATH="/api/v1/admin/bookings",MEETING="https://zoom.us/j/123456789";
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final ZoneId BRUSSELS=ZoneId.of("Europe/Brussels");
    @TempDir Path directory;
    AdministratorBookingHttpPostgresTest reads;AdministratorLoginPostgresTest login;AdminMfaDatabaseTest f;MockMvc mvc;
    @BeforeAll static void start() { AdminMfaDatabaseTest.start(); }
    @AfterAll static void stop() { AdminMfaDatabaseTest.stop(); }
    @BeforeEach void setup() throws Exception {
        reads=new AdministratorBookingHttpPostgresTest();reads.directory=directory;reads.setup();login=reads.login;f=reads.f;
        mvc=MockMvcBuilders.standaloneSetup(new SessionController(login.access,login.cookies),new AdministratorLoginController(
                new AdministratorLogin(new RequestThrottle(login.limits),f.mfa,login.sessions,new AdminCrypto()),login.cookies,Duration.ZERO),
                new AdministratorBookingCommandController(new AdminBooking(new PostgresAdminBooking(f.source,Set.of("zoom.us")),"local-v1",Set.of("zoom.us"))))
                .addFilters(new ApiGuardFilter(AdministratorLoginPostgresTest.ORIGIN,login.access,login.cookies,new ClientAddress(List.of()))).build();
        login.mvc=mvc;reads.mvc=mvc;
    }
    @AfterEach void cleanup() { reads.cleanup(); }
    Instant at(int days,int hour) { return f.now().atZone(BRUSSELS).toLocalDate().plusDays(days).atTime(hour,0).atZone(BRUSSELS).toInstant(); }
    ObjectNode body(Instant start,String meeting,String... rules) {
        var body=JSON.createObjectNode().put("serviceId",MigrationTest.SERVICE).put("serviceVersion",1).put("policyVersion",1).put("calendarVersion",1)
                .put("startInstant",start.toString()).put("customerEmail","customer@example.test").put("displayZone","Europe/Brussels").put("noticeVersion","local-v1");
        body.putObject("intake").put("firstName","Synthetic").put("lastName","Customer").put("telephone","+32400123456").put("birthDate","1990-01-01")
                .put("birthTime","12:00").put("birthplace","Synthetic city").put("message","");
        body.put("meetingUrl",meeting);
        var override=body.putObject("override");var named=override.putArray("rules");for(String rule:rules)named.add(rule);
        override.put("reason",rules.length==0?null:"Synthetic owner exception reason");override.putArray("blackoutIds");
        return body;
    }
    MockHttpServletResponse send(AdministratorLoginPostgresTest.Browser browser,String key,ObjectNode body) throws Exception {
        return mvc.perform(login.json(browser,PATH,body.toString()).header("Idempotency-Key",key)).andReturn().getResponse();
    }
    UUID created(MockHttpServletResponse response) throws Exception {
        assertEquals(201,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("\"1\"",response.getHeader("ETag"));
        var id=UUID.fromString(JSON.readTree(response.getContentAsString()).get("bookingId").textValue());assertEquals(PATH+"/"+id,response.getHeader("Location"));return id;
    }
    String row(UUID id) { return f.jdbc.queryForObject("SELECT state||'/'||origin FROM booking WHERE id=?",String.class,id); }
    int count(String table) { return f.jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }

    @Test void realOwnerCreatesPendingAndConfirmedBookingsIdempotentlyWithoutSubmissionCharges() throws Exception {
        var admin=reads.administrator();
        UUID pending=created(send(admin,"pending",body(at(3,10),null)));assertEquals("PENDING_CONFIRMATION/ADMINISTRATOR",row(pending));
        assertEquals(pending,created(send(admin,"pending",body(at(3,10),null))));
        login.problem(send(admin,"pending",body(at(3,14),null)),409,"IDEMPOTENCY_KEY_REUSED");
        UUID confirmed=created(send(admin,"confirmed",body(at(4,10),MEETING)));assertEquals("CONFIRMED/ADMINISTRATOR",row(confirmed));
        login.problem(send(admin,"meeting-reused",body(at(5,10),MEETING)),409,"MEETING_IN_USE");
        login.problem(send(admin,"meeting-invalid",body(at(5,10),"https://example.test/j/123456789")),400,"INVALID_MEETING");
        assertEquals(2,count("booking"));assertEquals(0,count("request_charge"));assertEquals(0,count("booking_override"));
    }
    @Test void overridesMustNameTheViolatedRuleWithAReasonAndNeverBypassOverlap() throws Exception {
        var admin=reads.administrator();Instant early=at(3,7);
        login.problem(send(admin,"early",body(early,null)),409,"START_NOT_AVAILABLE");
        login.problem(send(admin,"unused",body(at(3,10),null,"START_RULES")),409,"OVERRIDE_UNUSED");
        UUID overridden=created(send(admin,"early-override",body(early,null,"START_RULES")));
        assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM booking_override WHERE booking_id=? AND reason='Synthetic owner exception reason'",Integer.class,overridden));
        login.problem(send(admin,"overlap",body(early.plus(Duration.ofHours(1)),null,"START_RULES")),409,"SLOT_UNAVAILABLE");
        assertEquals(1,count("booking"));
    }
    @Test void guardsStrictBodiesAndStaleFactorsNeverCreateBookings() throws Exception {
        var admin=reads.administrator();var anonymous=login.boot();
        login.problem(send(anonymous,"anonymous",body(at(3,10),null)),403,"ADMIN_ACCESS_REQUIRED");
        var noReason=body(at(3,7),null,"START_RULES");((ObjectNode)noReason.get("override")).putNull("reason");
        var reasonWithoutRule=body(at(3,10),null);((ObjectNode)reasonWithoutRule.get("override")).put("reason","Synthetic owner exception reason");
        var unknownRule=body(at(3,7),null,"OVERLAP");var missing=body(at(3,10),null);missing.remove("calendarVersion");
        for(var invalid:List.of(noReason,reasonWithoutRule,unknownRule,missing))login.problem(send(admin,"invalid",invalid),400,"INVALID_REQUEST");
        login.problem(mvc.perform(login.json(admin,PATH,body(at(3,10),null).toString()).header("Idempotency-Key","versioned").header("If-Match","\"1\"")).andReturn().getResponse(),400,"INVALID_REQUEST");
        f.jdbc.update("UPDATE app_session SET issued_at=issued_at-interval '602 seconds',absolute_expires_at=absolute_expires_at-interval '602 seconds',authenticated_at=issued_at-interval '601 seconds' WHERE id=?",admin.reference().id());
        login.problem(send(admin,"not-fresh",body(at(3,10),null)),403,"AUTHENTICATION_REQUIRED");
        f.jdbc.update("UPDATE admin_user SET security_version=security_version+1");
        login.problem(send(admin,"revoked",body(at(3,10),null)),401,"SESSION_REQUIRED");
        assertEquals(0,count("booking"));
    }
}
