package com.purpuragenda.adapters;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.adapters.in.rest.*;
import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.adapters.out.security.AdminCrypto;
import com.purpuragenda.application.*;

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

@Timeout(240) @ResourceLock("postgres-migration-fixture")
class AdministratorErasureHttpPostgresTest {
    static final JsonMapper JSON=JsonMapper.builder().build();@TempDir Path directory;
    AdministratorBookingHttpPostgresTest reads;AdministratorLoginPostgresTest login;AdminMfaDatabaseTest f;MockMvc mvc;UUID customer,booking;
    static final String UNUSED="{\"reason\":\"Synthetic correction reason\",\"values\":{\"firstName\":\"Corrected\",\"lastName\":\"Synthetic\",\"telephone\":\"+32400123456\"}}";
    @BeforeAll static void start(){AdminMfaDatabaseTest.start();}@AfterAll static void stop(){AdminMfaDatabaseTest.stop();}
    @BeforeEach void setup() throws Exception {
        reads=new AdministratorBookingHttpPostgresTest();reads.directory=directory;reads.setup();login=reads.login;f=reads.f;booking=reads.book("CONFIRMED");customer=f.jdbc.queryForObject("SELECT customer_id FROM booking WHERE id=?",UUID.class,booking);f.jdbc.update("INSERT INTO customer_profile(customer_id,document) VALUES(?,'{}'::jsonb)",customer);
        var journal=new EncryptedErasureJournal(directory.resolve("journal"),f.secrets);var store=new PostgresCustomerErasure(f.source,f.secrets,journal,Set.of("zoom.us"));store.recover(false);
        mvc=MockMvcBuilders.standaloneSetup(new SessionController(login.access,login.cookies),new AdministratorLoginController(new AdministratorLogin(new RequestThrottle(login.limits),f.mfa,login.sessions,new AdminCrypto()),login.cookies,Duration.ZERO),new AdministratorErasureController(store,login.access,login.cookies))
            .addFilters(new ApiGuardFilter(AdministratorLoginPostgresTest.ORIGIN,login.access,login.cookies,new ClientAddress(List.of())),new PrivacyBarrierFilter(store::blocked)).build();login.mvc=mvc;reads.mvc=mvc;
    }
    @AfterEach void cleanup(){reads.cleanup();}
    MockHttpServletResponse send(MockHttpServletRequestBuilder r) throws Exception{return mvc.perform(r).andReturn().getResponse();}
    String customerPath(){return "/api/v1/admin/customers/"+customer;}
    String path(String id){return "/api/v1/admin/erasures/"+id;}
    String body(){return "{\"reason\":\"Synthetic erasure reason\",\"scopes\":[{\"kind\":\"INTAKE\",\"id\":\""+booking+"\"}]}";}
    String prepare(AdministratorLoginPostgresTest.Browser admin) throws Exception {var response=send(login.json(admin,customerPath()+"/erasures",body()).header("Idempotency-Key","prepare"));assertEquals(200,response.getStatus());return JSON.readTree(response.getContentAsString()).path("erasureId").asText();}
    @Test void actualMfaPreviewAndExactConfirmationArePrivateAndReplayable() throws Exception {
        var admin=reads.administrator();var overview=send(reads.privateGet(admin,customerPath()+"/privacy-review"));assertEquals(200,overview.getStatus());assertEquals("no-store",overview.getHeader("Cache-Control"));assertTrue(JSON.readTree(overview.getContentAsString()).path("observedAt").isTextual());
        String id=prepare(admin);var preview=send(reads.privateGet(admin,path(id)));assertEquals(200,preview.getStatus());assertEquals("no-store",preview.getHeader("Cache-Control"));var p=JSON.readTree(preview.getContentAsString());assertTrue(p.path("expiresAt").isTextual());assertEquals(1,p.path("selected").size());
        String body="{\"digest\":\""+p.path("digest").asText()+"\",\"confirmed\":true}";
        login.problem(send(login.json(admin,path(id)+"/confirm",body).header("Idempotency-Key","confirm")),428,"VERSION_REQUIRED");
        for(int i=0;i<2;i++)assertEquals(200,send(login.json(admin,path(id)+"/confirm",body).header("Idempotency-Key","confirm").header("If-Match","\"1\"")).getStatus());
        assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM erasure_tombstone",Integer.class));assertTrue(f.jdbc.queryForObject("SELECT (document->>'intakeErased')::boolean FROM booking_snapshot WHERE booking_id=?",Boolean.class,booking));
    }
    @Test void staleMfaAndRevocationPrecedeEveryErasurePath() throws Exception {
        var admin=reads.administrator();String id=prepare(admin);f.jdbc.update("UPDATE app_session SET issued_at=issued_at-interval '11 minutes',absolute_expires_at=absolute_expires_at-interval '11 minutes',authenticated_at=authenticated_at-interval '11 minutes' WHERE id=?",admin.reference().id());
        for(String url:List.of(customerPath()+"/privacy-review",path(id)))login.problem(send(reads.privateGet(admin,url)),403,"AUTHENTICATION_REQUIRED");
        login.problem(send(login.json(admin,customerPath()+"/erasures",body()).header("Idempotency-Key","prepare")),403,"AUTHENTICATION_REQUIRED");
        login.problem(send(login.json(admin,path(id)+"/confirm","{\"digest\":\""+"a".repeat(64)+"\",\"confirmed\":true}").header("Idempotency-Key","confirm").header("If-Match","\"1\"")),403,"AUTHENTICATION_REQUIRED");
        login.sessions.revoke(admin.reference());login.problem(send(reads.privateGet(admin,path(id))),401,"SESSION_REQUIRED");
    }
    @Test void strictShapesQueriesAndExplicitConfirmationFailClosed() throws Exception {
        var admin=reads.administrator();String id=prepare(admin);
        for(String value:List.of("{}",body().replace("INTAKE","FINANCIAL"),body().replace("\"scopes\":[","\"scopes\":false,\"extra\":[")))login.problem(send(login.json(admin,customerPath()+"/erasures",value).header("Idempotency-Key","bad")),400,"INVALID_REQUEST");
        login.problem(send(reads.privateGet(admin,path(id)).param("extra","1")),400,"INVALID_REQUEST");
        for(String confirmed:List.of("false","null","\"true\""))login.problem(send(login.json(admin,path(id)+"/confirm","{\"digest\":\""+"a".repeat(64)+"\",\"confirmed\":"+confirmed+"}").header("Idempotency-Key","bad").header("If-Match","\"1\"")),400,"INVALID_REQUEST");
    }
    @Test void anonymousAndMissingCsrfCannotProbeOrChangeRecords() throws Exception {
        var anonymous=login.boot();login.problem(send(get(customerPath()+"/privacy-review")),401,"SESSION_REQUIRED");login.problem(send(reads.privateGet(anonymous,customerPath()+"/privacy-review")),403,"ADMIN_ACCESS_REQUIRED");
        var admin=reads.administrator();login.problem(send(login.json(admin,customerPath()+"/erasures",body()).header("Idempotency-Key","prepare").with(r->{r.removeHeader("X-CSRF-Token");return r;})),403,"CSRF_REJECTED");assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM privacy_erasure",Integer.class));
    }
    @Test void interruptedApprovalBlocksExistingSessionReadsButAllowsAuthenticatedRetry() throws Exception {
        var admin=reads.administrator();String id=prepare(admin);var p=JSON.readTree(send(reads.privateGet(admin,path(id))).getContentAsString());
        Path head=directory.resolve("journal/head.enc");String saved=java.nio.file.Files.readString(head);java.nio.file.Files.delete(head);
        String body="{\"digest\":\""+p.path("digest").asText()+"\",\"confirmed\":true}";
        assertEquals(503,send(login.json(admin,path(id)+"/confirm",body).header("Idempotency-Key","confirm").header("If-Match","\"1\"")).getStatus());
        for(String url:List.of(path(id),customerPath()+"/privacy-review","/api/v1/admin/bookings/"+booking,"/api/v1/account/profile"))login.problem(send(reads.privateGet(admin,url)),503,"MAINTENANCE");
        java.nio.file.Files.writeString(head,saved);
        assertEquals(200,send(login.json(admin,path(id)+"/confirm",body).header("Idempotency-Key","confirm").header("If-Match","\"1\"")).getStatus());
        assertEquals(200,send(reads.privateGet(admin,customerPath()+"/privacy-review")).getStatus());
    }
}
