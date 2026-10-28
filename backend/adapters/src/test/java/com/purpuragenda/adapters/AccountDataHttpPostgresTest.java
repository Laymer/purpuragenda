package com.purpuragenda.adapters;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.adapters.in.rest.*;
import com.purpuragenda.adapters.out.postgres.PostgresAccountData;
import com.purpuragenda.application.AccountData;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real email enrollment, password login and persisted authority through Origin/CSRF guarded HTTP. */
@Timeout(240)
@ResourceLock("postgres-migration-fixture")
class AccountDataHttpPostgresTest {
    static final String BASE="/api/v1/account",BODY="{\"firstName\":\"Synthetic\",\"lastName\":\"Customer\",\"telephone\":\"+32400123456\"}";
    final AccountHttpPostgresTest h=new AccountHttpPostgresTest();@TempDir Path directory;
    @BeforeAll static void start() { AccountHttpPostgresTest.start(); }
    @AfterAll static void stop() { AccountHttpPostgresTest.stop(); }
    @BeforeEach void setup() throws Exception {
        h.directory=directory;h.setup();var data=new AccountData(new PostgresAccountData(h.a.e.c.f.source));
        h.mvc=MockMvcBuilders.standaloneSetup(new SessionController(h.access,h.cookies),new AccountController(h.login,h.passwords,h.cookies,Duration.ZERO),new AccountDataController(data,h.access,h.cookies),new AccountProfileController(data))
                .addFilters(new ApiGuardFilter(AccountHttpPostgresTest.ORIGIN,h.access,h.cookies,new ClientAddress(List.of()))).build();
    }
    @AfterEach void cleanup() { h.cleanup(); }
    MockHttpServletRequestBuilder read(AccountHttpPostgresTest.Browser browser,String path) { return get(BASE+path).cookie(new Cookie(h.cookies.name(),browser.cookie())); }
    MockHttpServletRequestBuilder write(AccountHttpPostgresTest.Browser browser,String key,String version,String body) { return h.json(browser,BASE+"/profile",body).header("Idempotency-Key",key).header("If-Match",version); }
    MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception { return h.mvc.perform(request).andReturn().getResponse(); }
    @Test void explicitProfileVersionReplayAndConflictsPreserveImmutableIdentity() throws Exception {
        var browser=h.login();var empty=send(read(browser,"/profile"));assertEquals(200,empty.getStatus());assertEquals("\"1\"",empty.getHeader("ETag"));assertEquals(0,h.count("customer_profile"));
        var first=send(write(browser,"save","\"1\"",BODY));assertEquals(200,first.getStatus());assertEquals("{\"version\":2}",first.getContentAsString());assertEquals("no-store",first.getHeader("Cache-Control"));
        assertEquals(first.getContentAsString(),send(write(browser,"save","\"1\"",BODY)).getContentAsString());assertEquals(1,h.count("audit_event WHERE type='ACCOUNT_PROFILE_CHANGED'"));
        h.problem(send(write(browser,"save","\"1\"",BODY.replace("Synthetic","Different"))),409,"IDEMPOTENCY_KEY_REUSED");h.problem(send(write(browser,"stale","\"1\"",BODY)),412,"STALE_VERSION");
        var profile=send(read(browser,"/profile").header("If-None-Match","\"2\""));assertEquals(200,profile.getStatus());assertTrue(profile.getContentAsString().contains("Synthetic"));assertTrue(profile.getContentAsString().contains(AccountHttpPostgresTest.aEmail()));
        assertEquals(AccountHttpPostgresTest.aEmail(),h.a.value("SELECT login_email FROM customer_account"));assertEquals(0,h.count("booking"));
        assertEquals(200,send(write(browser,"clear","\"2\"","{\"firstName\":\"\",\"lastName\":\"\",\"telephone\":\"\"}")).getStatus());
        h.a.epoch();h.problem(send(read(browser,"/profile")),401,"SESSION_REQUIRED");h.problem(send(write(browser,"save","\"1\"",BODY)),401,"SESSION_REQUIRED");
    }
    @Test void historyIsAccountOwnedPaginatedPrivateAndReadOnly() throws Exception {
        var browser=h.login();var session=h.a.sessions.inspect(browser.cookie().toCharArray()).orElseThrow();var binding=new SessionBinding(session.reference().id(),session.reference().browserHash());var c=h.a.e.c;
        UUID first=c.command.create(binding,"one",c.request(3,9)).entityId(),second=c.command.create(binding,"two",c.request(4,9)).entityId();var rival=c.account("other@example.test");c.command.create(rival.binding(),"other",c.request(5,9));
        String seen=h.a.value("SELECT last_seen_at FROM app_session WHERE id=?",session.reference().id());int receipts=h.count("idempotency_record");
        var page=send(read(browser,"/history").param("limit","1"));assertEquals(200,page.getStatus());assertEquals("no-store",page.getHeader("Cache-Control"));var json=JsonMapper.builder().build().readTree(page.getContentAsString());assertEquals(second.toString(),json.get("bookings").get(0).get("id").asText());
        var next=json.get("next");var last=send(read(browser,"/history").param("limit","1").param("beforeStart",next.get("beforeStart").asText()).param("beforeId",next.get("beforeId").asText()));var lastJson=JsonMapper.builder().build().readTree(last.getContentAsString());assertEquals(first.toString(),lastJson.get("bookings").get(0).get("id").asText());assertTrue(lastJson.get("next").isNull());
        for(String privateField:List.of("birthDate","telephone","deliveryEmail","meetingUrl","message","accountId"))assertFalse(page.getContentAsString().contains(privateField));
        assertEquals(seen,h.a.value("SELECT last_seen_at FROM app_session WHERE id=?",session.reference().id()));assertEquals(receipts,h.count("idempotency_record"));
        assertEquals(200,send(read(browser,"/profile")).getStatus());assertEquals(seen,h.a.value("SELECT last_seen_at FROM app_session WHERE id=?",session.reference().id()));
    }
    @Test void originCsrfAndStrictPreconditionsRejectBeforeWriting() throws Exception {
        var browser=h.login();
        h.problem(send(write(browser,"key","\"1\"",BODY).with(r->{r.removeHeader("Origin");return r;})),403,"ORIGIN_REJECTED");
        h.problem(send(write(browser,"key","\"1\"",BODY).with(r->{r.removeHeader("X-CSRF-Token");return r;})),403,"CSRF_REJECTED");
        h.problem(send(write(browser,"key","\"1\"",BODY).with(r->{r.removeHeader("If-Match");return r;})),428,"VERSION_REQUIRED");
        h.problem(send(write(browser,"key","W/\"1\"",BODY)),400,"INVALID_REQUEST");
        h.problem(send(write(browser,"key","\"1\"",BODY).with(r->{r.removeHeader("Idempotency-Key");return r;})),400,"INVALID_REQUEST");
        h.problem(send(write(browser,"key","\"1\"",BODY+"{}")),400,"INVALID_REQUEST");h.problem(send(read(browser,"/history").param("limit","1","2")),400,"INVALID_REQUEST");assertEquals(0,h.count("customer_profile"));
    }
    @Test void anonymousAndGuestSessionsNeverGainProfileOrHistory() throws Exception {
        var anonymous=h.boot();h.problem(send(read(anonymous,"/history")),403,"ACCOUNT_ACCESS_REQUIRED");h.problem(send(read(anonymous,"/profile")),403,"ACCOUNT_ACCESS_REQUIRED");h.problem(send(write(anonymous,"key","\"1\"",BODY)),403,"ACCOUNT_ACCESS_REQUIRED");
        var c=h.a.e.c;var browser=guest();
        h.problem(send(read(browser,"/history")),403,"ACCOUNT_ACCESS_REQUIRED");h.problem(send(read(browser,"/profile")),403,"ACCOUNT_ACCESS_REQUIRED");h.problem(send(write(browser,"key","\"1\"",BODY)),403,"ACCOUNT_ACCESS_REQUIRED");
        h.problem(send(get(BASE+"/history")),401,"SESSION_REQUIRED");h.problem(send(get(BASE+"/profile")),401,"SESSION_REQUIRED");
    }
    AccountHttpPostgresTest.Browser guest() {
        var c=h.a.e.c;
        try(var previous=c.sessions.anonymous()) {
            var reference=previous.session().reference();
            c.f.delivery.issue(new com.purpuragenda.application.port.out.AuthDeliveryStore.Issue("guest@example.test",com.purpuragenda.application.port.out.AuthDeliveryStore.Purpose.CREATE,null,reference.browserHash()));
            var lease=c.f.queue.claimAuth().orElseThrow();var message=c.f.delivery.prepare(lease).orElseThrow();assertTrue(c.f.queue.sent(lease));
            String token=message.text().split("#token=",2)[1].split("&",2)[0];
            var proof=new com.purpuragenda.application.port.out.GuestAccessStore.Redemption(token,com.purpuragenda.application.port.out.GuestAccessStore.Kind.CREATE,reference.browserHash());
            try(var issued=c.sessions.exchangeGuest(reference,proof).orElseThrow()) { return new AccountHttpPostgresTest.Browser(new String(issued.token()),new String(issued.csrf())); }
        }
    }
    @Test void corruptPersistedProfilesProduceSafeUnavailableProblems() throws Exception {
        var browser=h.login();assertEquals(200,send(write(browser,"save","\"1\"",BODY)).getStatus());h.a.e.c.jdbc.update("UPDATE customer_profile SET document='{}'::jsonb");
        h.problem(send(read(browser,"/profile")),503,"TEMPORARILY_UNAVAILABLE");h.problem(send(write(browser,"next","\"2\"",BODY)),503,"TEMPORARILY_UNAVAILABLE");
    }
}
