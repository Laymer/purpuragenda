package com.purpuragenda.adapters;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.adapters.in.rest.*;
import com.purpuragenda.adapters.out.postgres.PostgresCustomerExports;
import com.purpuragenda.adapters.out.security.AdminCrypto;
import com.purpuragenda.application.*;
import com.purpuragenda.application.privacy.AdministratorExports;
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
class AdministratorExportHttpPostgresTest {
    static final JsonMapper JSON=JsonMapper.builder().build();@TempDir Path directory;
    AdministratorBookingHttpPostgresTest reads;AdministratorLoginPostgresTest login;AdminMfaDatabaseTest f;MockMvc mvc;UUID customer;
    @BeforeAll static void start(){AdminMfaDatabaseTest.start();}@AfterAll static void stop(){AdminMfaDatabaseTest.stop();}
    @BeforeEach void setup() throws Exception {
        reads=new AdministratorBookingHttpPostgresTest();reads.directory=directory;reads.setup();login=reads.login;f=reads.f;UUID booking=reads.book("CONFIRMED");customer=f.jdbc.queryForObject("SELECT customer_id FROM booking WHERE id=?",UUID.class,booking);
        mvc=MockMvcBuilders.standaloneSetup(new SessionController(login.access,login.cookies),new AdministratorLoginController(new AdministratorLogin(new RequestThrottle(login.limits),f.mfa,login.sessions,new AdminCrypto()),login.cookies,Duration.ZERO),
            new AdministratorExportController(new AdministratorExports(new PostgresCustomerExports(f.source,f.secrets,Set.of("zoom.us"))),login.access,login.cookies))
            .addFilters(new ApiGuardFilter(AdministratorLoginPostgresTest.ORIGIN,login.access,login.cookies,new ClientAddress(List.of()))).build();login.mvc=mvc;reads.mvc=mvc;
    }
    @AfterEach void cleanup(){reads.cleanup();}
    MockHttpServletResponse send(MockHttpServletRequestBuilder r) throws Exception{return mvc.perform(r).andReturn().getResponse();}
    String prepare(){return "/api/v1/admin/customers/"+customer+"/exports";}
    String path(String id){return "/api/v1/admin/exports/"+id;}
    String prepare(AdministratorLoginPostgresTest.Browser admin) throws Exception {var r=send(login.json(admin,prepare(),"{\"reason\":\"Synthetic privacy request\"}").header("Idempotency-Key","prepare"));assertEquals(200,r.getStatus());return JSON.readTree(r.getContentAsString()).path("exportId").asText();}
    @Test void actualMfaPreviewReviewAndDownloadArePrivateAndNeverConditional() throws Exception {
        var admin=reads.administrator();String id=prepare(admin);var preview=send(reads.privateGet(admin,path(id)).header("If-None-Match","*"));assertEquals(200,preview.getStatus());assertEquals("no-store",preview.getHeader("Cache-Control"));assertNull(preview.getHeader("ETag"));var doc=JSON.readTree(preview.getContentAsString());assertEquals(6,doc.size());
        login.problem(send(reads.privateGet(admin,path(id)+"/download")),403,"ADMIN_ACCESS_REQUIRED");
        String body="{\"digest\":\""+doc.path("digest").asText()+"\",\"reviewedThirdPartyInformation\":true}";
        assertEquals(200,send(login.json(admin,path(id)+"/release",body).header("Idempotency-Key","release")).getStatus());
        var download=send(reads.privateGet(admin,path(id)+"/download"));assertEquals(200,download.getStatus());assertEquals("no-store",download.getHeader("Cache-Control"));assertEquals("attachment; filename=customer-export.json",download.getHeader("Content-Disposition"));assertEquals("application/octet-stream",download.getContentType());assertEquals(doc.path("document").asText(),download.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(id,prepare(admin));
    }
    @Test void staleMfaAndRevocationPrecedeEveryExportPath() throws Exception {
        var admin=reads.administrator();String id=prepare(admin);f.jdbc.update("UPDATE app_session SET issued_at=issued_at-interval '11 minutes',absolute_expires_at=absolute_expires_at-interval '11 minutes',authenticated_at=authenticated_at-interval '11 minutes' WHERE id=?",admin.reference().id());
        for(String suffix:List.of("","/download"))login.problem(send(reads.privateGet(admin,path(id)+suffix)),403,"AUTHENTICATION_REQUIRED");
        login.problem(send(login.json(admin,prepare(),"{\"reason\":\"Synthetic privacy request\"}").header("Idempotency-Key","prepare")),403,"AUTHENTICATION_REQUIRED");
        login.problem(send(login.json(admin,path(id)+"/release","{\"digest\":\""+"a".repeat(64)+"\",\"reviewedThirdPartyInformation\":true}").header("Idempotency-Key","release")),403,"AUTHENTICATION_REQUIRED");
        login.sessions.revoke(admin.reference());login.problem(send(reads.privateGet(admin,path(id))),401,"SESSION_REQUIRED");
    }
    @Test void strictBodiesQueriesKeysAndExplicitReviewFailClosed() throws Exception {
        var admin=reads.administrator();String id=prepare(admin);
        for(String body:List.of("{}","{\"reason\":\"short\"}","{\"reason\":\"Synthetic privacy request\",\"extra\":true}"))login.problem(send(login.json(admin,prepare(),body).header("Idempotency-Key","bad")),400,"INVALID_REQUEST");
        login.problem(send(login.json(admin,prepare(),"{\"reason\":\"Synthetic privacy request\"}")),400,"INVALID_REQUEST");
        login.problem(send(login.json(admin,prepare(),"{\"reason\":\"Synthetic privacy request\"}").header("Idempotency-Key","bad").header("If-Match","\"1\"")),400,"INVALID_REQUEST");
        login.problem(send(reads.privateGet(admin,path(id)).param("download","true")),400,"INVALID_REQUEST");login.problem(send(reads.privateGet(admin,path("bad"))),400,"INVALID_REQUEST");
        for(String value:List.of("false","\"true\""))login.problem(send(login.json(admin,path(id)+"/release","{\"digest\":\""+"a".repeat(64)+"\",\"reviewedThirdPartyInformation\":"+value+"}").header("Idempotency-Key","bad")),400,"INVALID_REQUEST");
        login.problem(send(reads.privateGet(admin,path(UUID.randomUUID().toString()))),404,"EXPORT_OR_CUSTOMER_NOT_FOUND");
    }
    @Test void anonymousAndMissingCookiesCannotProbeCustomersOrSnapshotsAndCsrfIsRequired() throws Exception {
        var anonymous=login.boot();login.problem(send(get(path(UUID.randomUUID().toString()))),401,"SESSION_REQUIRED");
        login.problem(send(reads.privateGet(anonymous,path(UUID.randomUUID().toString()))),403,"ADMIN_ACCESS_REQUIRED");
        login.problem(send(login.json(anonymous,prepare(),"{\"reason\":\"Synthetic privacy request\"}").header("Idempotency-Key","prepare")),403,"ADMIN_ACCESS_REQUIRED");
        var admin=reads.administrator();login.problem(send(login.json(admin,prepare(),"{\"reason\":\"Synthetic privacy request\"}").header("Idempotency-Key","prepare").with(r->{r.removeHeader("X-CSRF-Token");return r;})),403,"CSRF_REJECTED");
    }
}
