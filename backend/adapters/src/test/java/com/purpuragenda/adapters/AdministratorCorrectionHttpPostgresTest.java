package com.purpuragenda.adapters;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.adapters.in.rest.*;
import com.purpuragenda.adapters.out.postgres.PostgresCustomerCorrections;
import com.purpuragenda.adapters.out.security.AdminCrypto;
import com.purpuragenda.application.*;
import com.purpuragenda.application.privacy.AdministratorCorrections;
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
class AdministratorCorrectionHttpPostgresTest {
    static final JsonMapper JSON=JsonMapper.builder().build();@TempDir Path directory;
    AdministratorBookingHttpPostgresTest reads;AdministratorLoginPostgresTest login;AdminMfaDatabaseTest f;MockMvc mvc;UUID customer,booking;
    static final String BODY="{\"reason\":\"Synthetic correction reason\",\"values\":{\"firstName\":\"Corrected\",\"lastName\":\"Synthetic\",\"telephone\":\"+32400123456\"}}";
    @BeforeAll static void start(){AdminMfaDatabaseTest.start();}@AfterAll static void stop(){AdminMfaDatabaseTest.stop();}
    @BeforeEach void setup() throws Exception {
        reads=new AdministratorBookingHttpPostgresTest();reads.directory=directory;reads.setup();login=reads.login;f=reads.f;booking=reads.book("CONFIRMED");customer=f.jdbc.queryForObject("SELECT customer_id FROM booking WHERE id=?",UUID.class,booking);f.jdbc.update("INSERT INTO customer_profile(customer_id,document) VALUES(?,'{}'::jsonb)",customer);
        mvc=MockMvcBuilders.standaloneSetup(new SessionController(login.access,login.cookies),new AdministratorLoginController(new AdministratorLogin(new RequestThrottle(login.limits),f.mfa,login.sessions,new AdminCrypto()),login.cookies,Duration.ZERO),new AdministratorCorrectionController(new AdministratorCorrections(new PostgresCustomerCorrections(f.source,f.secrets,Set.of("zoom.us"))),login.access,login.cookies))
            .addFilters(new ApiGuardFilter(AdministratorLoginPostgresTest.ORIGIN,login.access,login.cookies,new ClientAddress(List.of()))).build();login.mvc=mvc;reads.mvc=mvc;
    }
    @AfterEach void cleanup(){reads.cleanup();}
    MockHttpServletResponse send(MockHttpServletRequestBuilder r) throws Exception{return mvc.perform(r).andReturn().getResponse();}
    String target(){return "/api/v1/admin/customers/"+customer+"/corrections/profile/"+customer;}
    String path(String id){return "/api/v1/admin/corrections/"+id;}
    String prepare(AdministratorLoginPostgresTest.Browser admin) throws Exception {var response=send(login.json(admin,target(),BODY).header("Idempotency-Key","prepare"));assertEquals(200,response.getStatus());return JSON.readTree(response.getContentAsString()).path("correctionId").asText();}
    @Test void actualMfaTargetPreviewAndVersionedConfirmationArePrivate() throws Exception {
        var admin=reads.administrator();var target=send(reads.privateGet(admin,target()).header("If-None-Match","*"));assertEquals(200,target.getStatus());assertEquals("no-store",target.getHeader("Cache-Control"));assertEquals(4,JSON.readTree(target.getContentAsString()).size());
        String id=prepare(admin);var preview=send(reads.privateGet(admin,path(id)));assertEquals(200,preview.getStatus());assertEquals("no-store",preview.getHeader("Cache-Control"));var p=JSON.readTree(preview.getContentAsString());assertEquals(8,p.size());assertEquals("Corrected",p.path("after").path("firstName").asText());assertEquals("",p.path("before").path("values").path("firstName").asText());
        String body="{\"digest\":\""+p.path("digest").asText()+"\",\"confirmed\":true}";
        login.problem(send(login.json(admin,path(id)+"/confirm",body).header("Idempotency-Key","confirm")),428,"VERSION_REQUIRED");
        login.problem(send(login.json(admin,path(id)+"/confirm",body).header("Idempotency-Key","wrong").header("If-Match","\"2\"")),412,"STALE_VERSION");
        var done=send(login.json(admin,path(id)+"/confirm",body).header("Idempotency-Key","confirm").header("If-Match","\"1\""));assertEquals(200,done.getStatus());assertEquals(2,JSON.readTree(done.getContentAsString()).path("version").asInt());assertEquals(200,send(login.json(admin,path(id)+"/confirm",body).header("Idempotency-Key","confirm").header("If-Match","\"1\"" )).getStatus());
        assertEquals("Corrected",JSON.readTree(send(reads.privateGet(admin,target())).getContentAsString()).path("values").path("firstName").asText());login.problem(send(reads.privateGet(admin,path(id))),404,"CORRECTION_OR_TARGET_NOT_FOUND");
    }
    @Test void staleMfaAndRevocationPrecedeEveryCorrectionPath() throws Exception {
        var admin=reads.administrator();String id=prepare(admin);f.jdbc.update("UPDATE app_session SET issued_at=issued_at-interval '11 minutes',absolute_expires_at=absolute_expires_at-interval '11 minutes',authenticated_at=authenticated_at-interval '11 minutes' WHERE id=?",admin.reference().id());
        for(String url:List.of(target(),path(id)))login.problem(send(reads.privateGet(admin,url)),403,"AUTHENTICATION_REQUIRED");login.problem(send(login.json(admin,target(),BODY).header("Idempotency-Key","prepare")),403,"AUTHENTICATION_REQUIRED");login.problem(send(login.json(admin,path(id)+"/confirm","{\"digest\":\""+"a".repeat(64)+"\",\"confirmed\":true}").header("Idempotency-Key","confirm").header("If-Match","\"1\"")),403,"AUTHENTICATION_REQUIRED");
        login.sessions.revoke(admin.reference());login.problem(send(reads.privateGet(admin,target())),401,"SESSION_REQUIRED");
    }
    @Test void strictBodiesShapesQueriesKeysAndExplicitConfirmationFailClosed() throws Exception {
        var admin=reads.administrator();String id=prepare(admin);
        for(String body:List.of("{}",BODY.replace("\"values\":{","\"values\":{\"extra\":\"x\","),BODY.replace("\"values\":{\"firstName\":\"Corrected\",\"lastName\":\"Synthetic\",\"telephone\":\"+32400123456\"}","\"values\":false"),BODY.replace("\"Corrected\"","false")))login.problem(send(login.json(admin,target(),body).header("Idempotency-Key","bad")),400,"INVALID_REQUEST");
        login.problem(send(login.json(admin,target(),BODY)),400,"INVALID_REQUEST");login.problem(send(login.json(admin,target(),BODY).header("Idempotency-Key","bad").header("If-Match","\"1\"")),400,"INVALID_REQUEST");login.problem(send(reads.privateGet(admin,target()).param("extra","1")),400,"INVALID_REQUEST");login.problem(send(reads.privateGet(admin,target().replace("profile","bad"))),400,"INVALID_REQUEST");
        for(String confirmed:List.of("false","\"true\""))login.problem(send(login.json(admin,path(id)+"/confirm","{\"digest\":\""+"a".repeat(64)+"\",\"confirmed\":"+confirmed+"}").header("Idempotency-Key","bad").header("If-Match","\"1\"")),400,"INVALID_REQUEST");login.problem(send(reads.privateGet(admin,path("bad"))),400,"INVALID_REQUEST");login.problem(send(reads.privateGet(admin,path(UUID.randomUUID().toString()))),404,"CORRECTION_OR_TARGET_NOT_FOUND");
    }
    @Test void anonymousMissingCookiesAndMissingCsrfCannotProbeOrChangeRecords() throws Exception {
        var anonymous=login.boot();login.problem(send(get(target())),401,"SESSION_REQUIRED");login.problem(send(reads.privateGet(anonymous,target())),403,"ADMIN_ACCESS_REQUIRED");login.problem(send(login.json(anonymous,target(),BODY).header("Idempotency-Key","prepare")),403,"ADMIN_ACCESS_REQUIRED");
        var admin=reads.administrator();login.problem(send(login.json(admin,target(),BODY).header("Idempotency-Key","prepare").with(r->{r.removeHeader("X-CSRF-Token");return r;})),403,"CSRF_REJECTED");assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM privacy_correction",Integer.class));
    }
}
