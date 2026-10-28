package com.purpuragenda.adapters;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.adapters.in.rest.*;
import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.adapters.out.security.AdminCrypto;
import com.purpuragenda.application.*;
import com.purpuragenda.application.scheduling.AdministratorNotificationRetry;
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
class AdministratorOperationsHttpPostgresTest {
    static final JsonMapper JSON=JsonMapper.builder().build();static final String BASE="/api/v1/admin",BODY="{\"reason\":\"Synthetic retry reason\"}";
    @TempDir Path directory;AdministratorBookingHttpPostgresTest reads;AdministratorLoginPostgresTest login;AdminMfaDatabaseTest f;MockMvc mvc;UUID booking,job,receipt;String path;
    @BeforeAll static void start(){AdminMfaDatabaseTest.start();}
    @AfterAll static void stop(){AdminMfaDatabaseTest.stop();}
    @BeforeEach void setup() throws Exception {
        reads=new AdministratorBookingHttpPostgresTest();reads.directory=directory;reads.setup();login=reads.login;f=reads.f;booking=reads.book("CONFIRMED");job=UUID.randomUUID();receipt=UUID.randomUUID();path=BASE+"/notifications/"+job+"/retry";
        f.jdbc.update("INSERT INTO notification_job(id,booking_id,audience,template,version,due_at,state,attempts,safe_error) VALUES(?,?,'CUSTOMER','BOOKING_CONFIRMED',1,clock_timestamp(),'FAILED',7,'SMTP_DELIVERY_FAILED')",job,booking);
        f.jdbc.update("INSERT INTO payment_entry(id,booking_id,kind,amount_minor,currency,method,reference,verified_at) VALUES(?,?,'RECEIPT',1000,'EUR','SYNTHETIC',?,clock_timestamp())",receipt,booking,receipt.toString());f.jdbc.update("INSERT INTO refund_obligation VALUES(?,1000,'LATE_RECEIPT')",receipt);
        mvc=MockMvcBuilders.standaloneSetup(new SessionController(login.access,login.cookies),new AdministratorLoginController(new AdministratorLogin(new RequestThrottle(login.limits),f.mfa,login.sessions,new AdminCrypto()),login.cookies,Duration.ZERO),
                new AdministratorOperationsController(new PostgresAdministratorOperations(f.source),login.access,login.cookies),new AdministratorNotificationRetryController(new AdministratorNotificationRetry(new PostgresNotificationRetry(f.source,Set.of("zoom.us")))))
                .addFilters(new ApiGuardFilter(AdministratorLoginPostgresTest.ORIGIN,login.access,login.cookies,new ClientAddress(List.of()))).build();login.mvc=mvc;reads.mvc=mvc;
    }
    @AfterEach void cleanup(){reads.cleanup();}
    MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception{return mvc.perform(request).andReturn().getResponse();}
    MockHttpServletRequestBuilder retry(AdministratorLoginPostgresTest.Browser browser){return login.json(browser,path,BODY).header("Idempotency-Key","synthetic-retry").header("If-Match","\"7\"");}
    @Test void realMfaReadsOutstandingMoneyAndFailedJobsThenReplaysOneAuditedRetry() throws Exception {
        var admin=reads.administrator();String seen=f.jdbc.queryForObject("SELECT last_seen_at::text FROM app_session WHERE id=?",String.class,admin.reference().id());long version=f.jdbc.queryForObject("SELECT version FROM booking WHERE id=?",Long.class,booking);int events=f.count("outbox_event");
        var money=send(reads.privateGet(admin,BASE+"/reconciliation").param("bookingId",booking.toString()).header("If-None-Match","*"));assertEquals(200,money.getStatus());assertEquals("no-store",money.getHeader("Cache-Control"));assertEquals(1000,JSON.readTree(money.getContentAsString()).at("/entries/0/outstandingMinor").asLong());
        var jobs=send(reads.privateGet(admin,BASE+"/notifications").param("state","FAILED"));assertEquals(200,jobs.getStatus());assertEquals(job.toString(),JSON.readTree(jobs.getContentAsString()).at("/entries/0/id").asText());assertFalse(jobs.getContentAsString().contains("encryptedEnvelope"));assertEquals(seen,f.jdbc.queryForObject("SELECT last_seen_at::text FROM app_session WHERE id=?",String.class,admin.reference().id()));
        var first=send(retry(admin));assertEquals(202,first.getStatus());assertEquals("no-store",first.getHeader("Cache-Control"));assertNull(first.getHeader("ETag"));assertEquals(first.getContentAsString(),send(retry(admin)).getContentAsString());assertEquals("QUEUED",f.jdbc.queryForObject("SELECT state FROM notification_job WHERE id=?",String.class,job));assertEquals(7,f.jdbc.queryForObject("SELECT attempts FROM notification_job WHERE id=?",Integer.class,job));assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE type='NOTIFICATION_RETRY_REQUESTED'",Integer.class));assertEquals(events,f.count("outbox_event"));assertEquals(version,f.jdbc.queryForObject("SELECT version FROM booking WHERE id=?",Long.class,booking));
    }
    @Test void staleFactorMayReadButCannotRetryOrReplayAndRevocationDeniesAll() throws Exception {
        var admin=reads.administrator();assertEquals(202,send(retry(admin)).getStatus());f.jdbc.update("UPDATE app_session SET issued_at=issued_at-interval '602 seconds',absolute_expires_at=absolute_expires_at-interval '602 seconds',authenticated_at=issued_at-interval '601 seconds' WHERE id=?",admin.reference().id());
        assertEquals(200,send(reads.privateGet(admin,BASE+"/notifications")).getStatus());assertEquals(200,send(reads.privateGet(admin,BASE+"/reconciliation")).getStatus());login.problem(send(retry(admin)),403,"AUTHENTICATION_REQUIRED");login.sessions.revoke(admin.reference());login.problem(send(retry(admin)),401,"SESSION_REQUIRED");login.problem(send(reads.privateGet(admin,BASE+"/notifications")),401,"SESSION_REQUIRED");
    }
    @Test void exactOriginCsrfRoleAndStrictAttemptPreconditionsAreRequired() throws Exception {
        var admin=reads.administrator();login.problem(send(retry(admin).header("Origin","https://untrusted.example.test")),403,"ORIGIN_REJECTED");login.problem(send(post(path).header("Origin",AdministratorLoginPostgresTest.ORIGIN).cookie(new jakarta.servlet.http.Cookie("__Host-purpuragenda",admin.cookie())).contentType("application/json").content(BODY)),403,"CSRF_REJECTED");
        var anonymous=login.boot();login.problem(send(retry(anonymous)),403,"ADMIN_ACCESS_REQUIRED");login.problem(send(reads.privateGet(anonymous,BASE+"/reconciliation")),403,"ADMIN_ACCESS_REQUIRED");
        login.problem(send(login.json(admin,path,BODY).header("Idempotency-Key","missing")),428,"VERSION_REQUIRED");login.problem(send(login.json(admin,path,BODY).header("Idempotency-Key","weak").header("If-Match","W/\"7\"")),400,"INVALID_REQUEST");login.problem(send(login.json(admin,path,BODY).header("Idempotency-Key","stale").header("If-Match","\"6\"")),412,"STALE_VERSION");
        login.problem(send(retry(admin).content(BODY+"{}")),400,"INVALID_REQUEST");login.problem(send(reads.privateGet(admin,BASE+"/notifications").param("state","FAILED","SENT")),400,"INVALID_REQUEST");login.problem(send(reads.privateGet(admin,BASE+"/reconciliation").param("extra","x")),400,"INVALID_REQUEST");assertEquals("FAILED",f.jdbc.queryForObject("SELECT state FROM notification_job WHERE id=?",String.class,job));
    }
    @Test void unknownTargetsSuppressionAndConcurrentStateChangesReturnSafeProblems() throws Exception {
        var admin=reads.administrator();login.problem(send(reads.privateGet(admin,BASE+"/notifications").param("bookingId",UUID.randomUUID().toString())),404,"BOOKING_NOT_FOUND");login.problem(send(login.json(admin,BASE+"/notifications/"+UUID.randomUUID()+"/retry",BODY).header("Idempotency-Key","missing").header("If-Match","\"7\"")),404,"NOTIFICATION_NOT_FOUND");
        f.jdbc.update("UPDATE operation_control SET delivery_suppressed=true WHERE id=1");login.problem(send(retry(admin)),409,"DELIVERY_SUPPRESSED");f.jdbc.update("UPDATE operation_control SET delivery_suppressed=false WHERE id=1");f.jdbc.update("UPDATE notification_job SET state='SENT' WHERE id=?",job);login.problem(send(login.json(admin,path,BODY).header("Idempotency-Key","sent").header("If-Match","\"7\"")),409,"NOTIFICATION_NOT_RETRYABLE");
        assertEquals(405,send(login.json(admin,BASE+"/reconciliation","{}")).getStatus());assertEquals(405,send(reads.privateGet(admin,path)).getStatus());
    }
}
