package com.purpuragenda.adapters;

import com.purpuragenda.adapters.in.rest.*;
import com.purpuragenda.adapters.out.postgres.PostgresContactChanges;
import com.purpuragenda.adapters.out.security.*;
import com.purpuragenda.application.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.scheduling.CustomerContactChanges;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@Timeout(240)
@ResourceLock("postgres-migration-fixture")
class ContactHttpPostgresTest {
    final CustomerBookingHttpPostgresTest h=new CustomerBookingHttpPostgresTest();@TempDir Path directory;
    GuestAuthPostgresTest.Browser browser;String id,path;
    @BeforeAll static void start(){MigrationTest.start();}
    @AfterAll static void stop(){MigrationTest.stop();}
    @BeforeEach void setup() throws Exception {h.directory=directory;h.setup();browser=h.guest("original@example.test");id=h.booking(h.create(browser,"create",h.body(3)));path="/api/v1/bookings/"+id;}
    @AfterEach void cleanup(){h.cleanup();}
    String email(String value){return h.json.createObjectNode().put("email",value).toString();}
    String body(String token){return h.json.createObjectNode().put("token",token).toString();}
    String proof(String value) throws Exception {var r=h.g.send(h.g.json(browser,path+"/contact-proofs",email(value)));assertEquals(202,r.getStatus());assertEquals("no-store",r.getHeader("Cache-Control"));assertEquals("{\"accepted\":true}",r.getContentAsString());return h.g.proofToken();}
    MockHttpServletRequestBuilder change(String token,String key,String version){return h.g.json(browser,path+"/contact",body(token)).header("Idempotency-Key",key).header("If-Match",version);}
    void problem(MockHttpServletResponse response,int status,String code) throws Exception {assertEquals(status,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertTrue(response.getContentAsString().contains("\"code\":\""+code+"\""));assertFalse(response.getContentAsString().contains("synthetic"));}
    @Test void realGuestProofConfirmationAndReplayChangeOnlyDeliveryAndQueueSecurityNotices() throws Exception {
        var before=h.c.load(UUID.fromString(id));String token=proof("next@example.test");assertEquals(before,h.c.load(before.id()));
        var response=h.g.send(change(token,"change","\"1\"").header("If-None-Match","\"2\""));assertEquals(200,response.getStatus());assertEquals("\"2\"",response.getHeader("ETag"));assertEquals("no-store",response.getHeader("Cache-Control"));assertFalse(response.getContentAsString().contains(token));
        assertEquals(before.deliveryContact("next@example.test"),h.c.load(before.id()));assertEquals(response.getContentAsString(),h.g.send(change(token,"change","\"1\"")).getContentAsString());
        assertEquals(1,h.c.count("booking_contact_change"));assertEquals(1,h.c.count("outbox_event WHERE type='CONTACT_CHANGED'"));assertEquals(1,h.c.count("request_charge"));assertEquals("\"2\"",h.view(browser,id).getHeader("ETag"));
    }
    @Test void exactOriginCsrfAndPostOnlyGuardsPrecedeAnyContactAction() throws Exception {
        for(String suffix:List.of("/contact-proofs","/contact")) {
            var request=h.g.json(browser,path+suffix,"{}");request.with(r->{r.removeHeader("Origin");return r;});problem(h.g.send(request),403,"ORIGIN_REJECTED");
            request=h.g.json(browser,path+suffix,"{}");request.with(r->{r.removeHeader("X-CSRF-Token");return r;});problem(h.g.send(request),403,"CSRF_REJECTED");
            assertEquals(405,h.g.send(get(path+suffix).cookie(new Cookie("__Host-purpuragenda",browser.cookie()))).getStatus());
        }
        assertEquals(0,h.c.count("email_challenge WHERE purpose='CONTACT'"));assertEquals(0,h.c.count("booking_contact_change"));
    }
    @Test void strictVersionAndIdempotencyFailuresDoNotConsumeTheContactProof() throws Exception {
        String token=proof("next@example.test");problem(h.g.send(change(token,"missing","\"1\"").with(r->{r.removeHeader("If-Match");return r;})),428,"VERSION_REQUIRED");
        problem(h.g.send(change(token,"weak","W/\"1\"")),400,"INVALID_REQUEST");problem(h.g.send(change(token,"stale","\"2\"")),412,"STALE_VERSION");
        problem(h.g.send(change(token,"key","\"1\"").with(r->{r.removeHeader("Idempotency-Key");return r;})),400,"INVALID_REQUEST");
        assertEquals(0,h.c.count("email_challenge WHERE purpose='CONTACT' AND consumed_at IS NOT NULL"));assertEquals(200,h.g.send(change(token,"change","\"1\"")).getStatus());
        problem(h.g.send(change(token,"change","\"2\"")),409,"IDEMPOTENCY_KEY_REUSED");problem(h.g.send(change(token,"used","\"2\"")),400,"PROOF_INVALID");
    }
    @Test void unknownUnownedAndRevokedSessionsCannotRequestConfirmOrReplay() throws Exception {
        String token=proof("next@example.test");var other=h.guest("other@example.test");String theirs=h.booking(h.create(other,"other",h.body(4)));
        for(String target:List.of(theirs,UUID.randomUUID().toString())) {
            problem(h.g.send(h.g.json(browser,"/api/v1/bookings/"+target+"/contact-proofs",email("unused-"+target+"@example.test"))),404,"BOOKING_NOT_FOUND");
            problem(h.g.send(h.g.json(browser,"/api/v1/bookings/"+target+"/contact",body(token)).header("Idempotency-Key","wrong").header("If-Match","\"1\"")),404,"BOOKING_NOT_FOUND");
        }
        assertEquals(200,h.g.send(change(token,"change","\"1\"")).getStatus());h.c.jdbc.update("UPDATE app_session SET revoked_at=clock_timestamp() WHERE kind='GUEST'");
        problem(h.g.send(change(token,"change","\"1\"")),401,"SESSION_REQUIRED");assertEquals(1,h.c.count("booking_contact_change"));
    }
    @Test void proofExpiryAndWrongPurposeAreSafeBadRequestsWithNoPartialChanges() throws Exception {
        String token=proof("next@example.test");h.c.jdbc.update("UPDATE email_challenge SET purpose='MANAGE' WHERE purpose='CONTACT'");problem(h.g.send(change(token,"purpose","\"1\"")),400,"PROOF_INVALID");
        h.c.jdbc.update("UPDATE email_challenge SET purpose='CONTACT',expires_at=clock_timestamp()-interval '1 second' WHERE email_key='next@example.test'");problem(h.g.send(change(token,"expired","\"1\"")),400,"PROOF_INVALID");
        assertEquals(0,h.c.count("booking_contact_change"));assertEquals(1,h.c.load(UUID.fromString(id)).version());
    }
    @Test void sharedEmailCooldownAndStrictBodiesNeverProduceExtraDelivery() throws Exception {
        proof("next@example.test");var limited=h.g.send(h.g.json(browser,path+"/contact-proofs",email("next@example.test")));problem(limited,429,"RATE_LIMITED");assertTrue(Long.parseLong(limited.getHeader("Retry-After"))>0);
        for(String invalid:List.of("{}{}","{\"email\":\"a@example.test\",\"extra\":true}","{\"email\":\"a@example.test\",\"email\":\"b@example.test\"}"))problem(h.g.send(h.g.json(browser,path+"/contact-proofs",invalid)),400,"INVALID_REQUEST");
        problem(h.g.send(h.g.json(browser,path+"/contact-proofs?extra=1",email("fresh@example.test"))),400,"INVALID_REQUEST");assertEquals(1,h.c.count("email_challenge WHERE purpose='CONTACT'"));
    }
    @Test void realAccountLoginRetainsStableOwnershipWhenDeliveryChanges() throws Exception {
        var a=new AccountHttpPostgresTest();a.directory=directory;a.setup();
        try {
            var account=a.login();var c=a.a.e.c;var persisted=c.sessions.inspect(account.cookie().toCharArray()).orElseThrow();var binding=new SessionBinding(persisted.reference().id(),persisted.reference().browserHash());
            UUID booking=c.command.create(binding,"account-booking",c.request(3,9)).entityId();var before=c.load(booking);
            var throttle=new RequestThrottle(new PostgresRateLimits(c.f.source,new RateKey(directory.resolve("account-rate.key"),"account-test-v1")));
            var mvc=MockMvcBuilders.standaloneSetup(new CustomerContactController(new CustomerContactChanges(new PostgresContactChanges(c.f.source,c.f.delivery),throttle)))
                    .addFilters(new ApiGuardFilter(AccountHttpPostgresTest.ORIGIN,a.access,a.cookies,new ClientAddress(List.of()))).build();
            String base="/api/v1/bookings/"+booking;assertEquals(202,mvc.perform(a.json(account,base+"/contact-proofs",email("account-delivery@example.test"))).andReturn().getResponse().getStatus());
            var lease=c.f.queue.claimAuth().orElseThrow();var mail=c.f.delivery.prepare(lease).orElseThrow();assertTrue(c.f.queue.sent(lease));String token=mail.text().split("#token=",2)[1].split("&",2)[0];
            assertEquals(200,mvc.perform(a.json(account,base+"/contact",body(token)).header("Idempotency-Key","account-contact").header("If-Match","\"1\"")).andReturn().getResponse().getStatus());
            assertEquals(before.deliveryContact("account-delivery@example.test"),c.load(booking));assertEquals(AccountHttpPostgresTest.aEmail(),c.f.value("SELECT login_email FROM customer_account"));
        } finally {a.cleanup();}
    }
}
