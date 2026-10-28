package com.purpuragenda.adapters;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.adapters.in.rest.*;
import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.adapters.out.security.AdminCrypto;
import com.purpuragenda.application.*;
import com.purpuragenda.application.scheduling.AdministratorNotes;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@Timeout(240)
@ResourceLock("postgres-migration-fixture")
class AdministratorHistoryHttpPostgresTest {
    static final JsonMapper JSON=JsonMapper.builder().build();static final String BODY="{\"text\":\"Synthetic private note\"}";
    @TempDir Path directory;
    AdministratorBookingHttpPostgresTest reads;AdministratorLoginPostgresTest login;AdminMfaDatabaseTest f;MockMvc mvc;UUID booking;String path;
    @BeforeAll static void start(){AdminMfaDatabaseTest.start();}
    @AfterAll static void stop(){AdminMfaDatabaseTest.stop();}
    @BeforeEach void setup() throws Exception {
        reads=new AdministratorBookingHttpPostgresTest();reads.directory=directory;reads.setup();login=reads.login;f=reads.f;booking=reads.book("CONFIRMED");path="/api/v1/admin/bookings/"+booking+"/notes";
        mvc=MockMvcBuilders.standaloneSetup(new SessionController(login.access,login.cookies),new AdministratorLoginController(new AdministratorLogin(new RequestThrottle(login.limits),f.mfa,login.sessions,new AdminCrypto()),login.cookies,Duration.ZERO),
                new AdministratorNoteController(new AdministratorNotes(new PostgresNotes(f.source,Set.of("zoom.us")))),new AdministratorHistoryController(new PostgresAdministratorHistory(f.source),login.access,login.cookies))
                .addFilters(new ApiGuardFilter(AdministratorLoginPostgresTest.ORIGIN,login.access,login.cookies,new ClientAddress(List.of()))).build();login.mvc=mvc;reads.mvc=mvc;
    }
    @AfterEach void cleanup(){reads.cleanup();}
    MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception{return mvc.perform(request).andReturn().getResponse();}
    MockHttpServletRequestBuilder note(AdministratorLoginPostgresTest.Browser browser){return login.json(browser,path,BODY).header("Idempotency-Key","synthetic-note");}
    @Test void realMfaAppendReplaysAndReadsPrivateNotesWithoutChangingBookingOrNotifying() throws Exception {
        var admin=reads.administrator();long version=f.jdbc.queryForObject("SELECT version FROM booking WHERE id=?",Long.class,booking);int events=f.count("outbox_event");
        var first=send(note(admin));assertEquals(201,first.getStatus());assertEquals("no-store",first.getHeader("Cache-Control"));assertNull(first.getHeader("ETag"));assertEquals(first.getContentAsString(),send(note(admin)).getContentAsString());
        String seen=f.jdbc.queryForObject("SELECT last_seen_at::text FROM app_session WHERE id=?",String.class,admin.reference().id());
        var notes=send(reads.privateGet(admin,path).header("If-None-Match","*"));assertEquals(200,notes.getStatus());assertEquals("Synthetic private note",JSON.readTree(notes.getContentAsString()).at("/entries/0/text").asText());
        var audit=send(reads.privateGet(admin,"/api/v1/admin/audit").param("bookingId",booking.toString()));assertEquals(200,audit.getStatus());assertFalse(audit.getContentAsString().contains("Synthetic private note"));assertEquals("NOTE_ADDED",JSON.readTree(audit.getContentAsString()).at("/entries/0/type").asText());
        assertEquals(seen,f.jdbc.queryForObject("SELECT last_seen_at::text FROM app_session WHERE id=?",String.class,admin.reference().id()));assertEquals(version,f.jdbc.queryForObject("SELECT version FROM booking WHERE id=?",Long.class,booking));assertEquals(events,f.count("outbox_event"));assertEquals(1,f.count("booking_note"));
    }
    @Test void staleFactorStillReadsButCannotAppendOrReplayAndRevocationDeniesBoth() throws Exception {
        var admin=reads.administrator();assertEquals(201,send(note(admin)).getStatus());
        f.jdbc.update("UPDATE app_session SET issued_at=issued_at-interval '602 seconds',absolute_expires_at=absolute_expires_at-interval '602 seconds',authenticated_at=issued_at-interval '601 seconds' WHERE id=?",admin.reference().id());
        assertEquals(200,send(reads.privateGet(admin,path)).getStatus());login.problem(send(note(admin)),403,"AUTHENTICATION_REQUIRED");
        login.sessions.revoke(admin.reference());login.problem(send(reads.privateGet(admin,path)),401,"SESSION_REQUIRED");login.problem(send(note(admin)),401,"SESSION_REQUIRED");assertEquals(1,f.count("booking_note"));
    }
    @Test void exactOriginCsrfAndAdministratorRoleAreRequired() throws Exception {
        var admin=reads.administrator();login.problem(send(note(admin).header("Origin","https://untrusted.example.test")),403,"ORIGIN_REJECTED");
        login.problem(send(post(path).header("Origin",AdministratorLoginPostgresTest.ORIGIN).cookie(new jakarta.servlet.http.Cookie("__Host-purpuragenda",admin.cookie())).contentType("application/json").content(BODY)),403,"CSRF_REJECTED");
        var anonymous=login.boot();login.problem(send(note(anonymous)),403,"ADMIN_ACCESS_REQUIRED");login.problem(send(reads.privateGet(anonymous,path)),403,"ADMIN_ACCESS_REQUIRED");assertEquals(0,f.count("booking_note"));
    }
    @Test void strictBodiesQueriesAndIndependentReceiptConditionsAreEnforced() throws Exception {
        var admin=reads.administrator();login.problem(send(login.json(admin,path,BODY)),400,"INVALID_REQUEST");login.problem(send(note(admin).header("If-Match","\"1\"")),400,"INVALID_REQUEST");
        for(String body:List.of(BODY+"{}","{\"text\":\"one\",\"text\":\"two\"}","{\"text\":\""+"x".repeat(17000)+"\"}"))login.problem(send(login.json(admin,path,body).header("Idempotency-Key","bad")),400,"INVALID_REQUEST");
        login.problem(send(reads.privateGet(admin,path).param("limit","1","2")),400,"INVALID_REQUEST");login.problem(send(reads.privateGet(admin,"/api/v1/admin/audit").param("unexpected","1")),400,"INVALID_REQUEST");
        assertEquals(201,send(note(admin)).getStatus());login.problem(send(login.json(admin,path,"{\"text\":\"Synthetic different\"}").header("Idempotency-Key","synthetic-note")),409,"IDEMPOTENCY_KEY_REUSED");assertEquals(1,f.count("booking_note"));
    }
    @Test void unknownBookingsAreSafeAndUnsupportedMethodsCannotMutate() throws Exception {
        var admin=reads.administrator();String missing="/api/v1/admin/bookings/"+UUID.randomUUID()+"/notes";
        login.problem(send(reads.privateGet(admin,missing)),404,"BOOKING_NOT_FOUND");login.problem(send(login.json(admin,missing,BODY).header("Idempotency-Key","missing")),404,"BOOKING_NOT_FOUND");
        assertEquals(405,send(login.json(admin,"/api/v1/admin/audit","{}")).getStatus());assertEquals(0,f.count("booking_note"));
    }
}
