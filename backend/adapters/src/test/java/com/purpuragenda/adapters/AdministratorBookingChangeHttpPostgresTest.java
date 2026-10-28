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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;

/** Real MFA precedes versioned administrator changes; replay, stale versions, unknown bookings and effects use PostgreSQL. */
@Timeout(240)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class AdministratorBookingChangeHttpPostgresTest {
    static final String REASON="Synthetic administrative reason";
    static final JsonMapper JSON=JsonMapper.builder().build();
    @TempDir Path directory;
    final AdministratorBookingCommandHttpPostgresTest h=new AdministratorBookingCommandHttpPostgresTest();
    AdministratorLoginPostgresTest login;AdminMfaDatabaseTest f;
    @BeforeAll static void start() { AdminMfaDatabaseTest.start(); }
    @AfterAll static void stop() { AdminMfaDatabaseTest.stop(); }
    @BeforeEach void setup() throws Exception {
        h.directory=directory;h.setup();login=h.login;f=h.f;
        h.mvc=MockMvcBuilders.standaloneSetup(new SessionController(login.access,login.cookies),new AdministratorLoginController(
                new AdministratorLogin(new RequestThrottle(login.limits),f.mfa,login.sessions,new AdminCrypto()),login.cookies,Duration.ZERO),
                new AdministratorBookingCommandController(new AdminBooking(new PostgresAdminBooking(f.source,Set.of("zoom.us")),"local-v1",Set.of("zoom.us"))),
                new AdministratorBookingChangeController(new AdministratorBookingChanges(new PostgresAdminChanges(f.source,Set.of("zoom.us")))))
                .addFilters(new ApiGuardFilter(AdministratorLoginPostgresTest.ORIGIN,login.access,login.cookies,new ClientAddress(List.of()))).build();
        login.mvc=h.mvc;h.reads.mvc=h.mvc;
    }
    @AfterEach void cleanup() { h.cleanup(); }
    MockHttpServletResponse change(AdministratorLoginPostgresTest.Browser admin,UUID id,String action,String key,ObjectNode body,String version) throws Exception {
        var request=login.json(admin,"/api/v1/admin/bookings/"+id+action,body.toString()).header("Idempotency-Key",key);
        if(version!=null)request.header("If-Match",version);return h.mvc.perform(request).andReturn().getResponse();
    }
    ObjectNode reason() { return JSON.createObjectNode().put("reason",REASON); }
    long version(MockHttpServletResponse response) throws Exception {
        assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));
        long value=JSON.readTree(response.getContentAsString()).get("version").asLong();assertEquals("\""+value+"\"",response.getHeader("ETag"));return value;
    }

    @Test void rejectionIsVersionedIdempotentAndTerminal() throws Exception {
        var admin=h.reads.administrator();UUID id=h.created(h.send(admin,"pending",h.body(h.at(3,10),null)));
        assertEquals(2,version(change(admin,id,"/reject","reject",reason(),"\"1\"")));
        assertEquals(2,version(change(admin,id,"/reject","reject",reason(),"\"1\"")));
        login.problem(change(admin,id,"/reject","reject-again",reason(),"\"1\""),412,"STALE_VERSION");
        login.problem(change(admin,id,"/cancel","cancel",reason().put("classification","PROVIDER"),"\"2\""),409,"INVALID_TRANSITION");
        login.problem(change(admin,UUID.randomUUID(),"/reject","missing",reason(),"\"1\""),404,"BOOKING_NOT_FOUND");
        login.problem(change(admin,id,"/reject","unversioned",reason(),null),428,"VERSION_REQUIRED");
        assertEquals("REJECTED",h.row(id).split("/")[0]);assertEquals(1,h.count("outbox_event WHERE type='REJECTED'"));
    }

    @Test void cancellationReschedulingAndOutcomesApplyTheirRulesThroughHttp() throws Exception {
        var admin=h.reads.administrator();
        UUID confirmed=h.created(h.send(admin,"confirmed",h.body(h.at(3,10),AdministratorBookingCommandHttpPostgresTest.MEETING)));
        login.problem(change(admin,confirmed,"/outcome","early",JSON.createObjectNode().put("outcome","COMPLETED").putNull("reason"),"\"1\""),409,"SESSION_NOT_STARTED");
        assertEquals(2,version(change(admin,confirmed,"/cancel","cancel",reason().put("classification","PROVIDER"),"\"1\"")));
        assertEquals(1,h.count("audit_event WHERE type='CANCELLED' AND metadata->>'classification'='PROVIDER'"));
        UUID pending=h.created(h.send(admin,"pending",h.body(h.at(4,10),null)));
        var move=reason().put("policyVersion",1).put("calendarVersion",1).put("startInstant",h.at(4,7).toString());
        var override=move.putObject("override");override.putArray("rules");override.putNull("reason");override.putArray("blackoutIds");
        login.problem(change(admin,pending,"/reschedule","early",move,"\"1\""),409,"START_NOT_AVAILABLE");
        override.putArray("rules").add("START_RULES");override.put("reason","Synthetic owner exception reason");
        assertEquals(2,version(change(admin,pending,"/reschedule","early-override",move,"\"1\"")));
        assertEquals(1,h.count("booking_override WHERE booking_version=2"));assertEquals(1,h.count("outbox_event WHERE type='RESCHEDULED'"));
    }

    @Test void guardsAndStaleFactorsNeverChangeBookings() throws Exception {
        var admin=h.reads.administrator();UUID id=h.created(h.send(admin,"pending",h.body(h.at(3,10),null)));var anonymous=login.boot();
        login.problem(change(anonymous,id,"/reject","anonymous",reason(),"\"1\""),403,"ADMIN_ACCESS_REQUIRED");
        f.jdbc.update("UPDATE app_session SET issued_at=issued_at-interval '602 seconds',absolute_expires_at=absolute_expires_at-interval '602 seconds',authenticated_at=issued_at-interval '601 seconds' WHERE id=?",admin.reference().id());
        login.problem(change(admin,id,"/reject","not-fresh",reason(),"\"1\""),403,"AUTHENTICATION_REQUIRED");
        f.jdbc.update("UPDATE admin_user SET security_version=security_version+1");
        login.problem(change(admin,id,"/reject","revoked",reason(),"\"1\""),401,"SESSION_REQUIRED");
        assertEquals("PENDING_CONFIRMATION",h.row(id).split("/")[0]);assertEquals(0,h.count("outbox_event WHERE type='REJECTED'"));
    }
}
