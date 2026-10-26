package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.*;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.RateLimitStore.Charge;
import com.purpuragenda.application.port.out.RateLimitStore.Decision;
import com.purpuragenda.application.port.out.RateLimitStore.Reservation;
import com.purpuragenda.application.port.out.SessionStore.*;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class GuestAuthHttpTest {
    static final String ORIGIN="https://bookings.example.com";
    static final String TOKEN="a".repeat(43),CSRF="c".repeat(43),BROWSER="b".repeat(64),PROOF="p".repeat(43);
    static final Instant NOW=Instant.parse("2027-01-04T08:00:00Z");

    static final class Sessions implements SessionStore {
        final Snapshot live=new Snapshot(new SessionReference(UUID.randomUUID(),BROWSER),Kind.ANONYMOUS,null,null,null,null,null,NOW,NOW,NOW.plusSeconds(7200),null);
        Optional<Issued> guest=Optional.empty();final List<Redemption> redeemed=new ArrayList<>();boolean unavailable;
        public Issued anonymous() { throw new AssertionError(); }
        public Optional<CsrfRotation> rotateCsrf(char[] token) { throw new AssertionError(); }
        public Optional<Snapshot> inspect(char[] token) { throw new AssertionError(); }
        public Optional<Snapshot> resolve(char[] token) { return new String(token).equals(TOKEN)?Optional.of(live):Optional.empty(); }
        public boolean csrf(SessionReference reference,char[] csrf) { return new String(csrf).equals(CSRF); }
        public Optional<Issued> exchangeAccount(SessionReference previous,String requestHash,char[] proofToken) { return Optional.empty(); }
        public Optional<Issued> exchangeAdmin(SessionReference previous,String requestHash,char[] proofToken) { throw new AssertionError(); }
        public Optional<Issued> exchangeGuest(SessionReference previous,GuestAccessStore.Redemption proof) {
            if(unavailable) throw new IllegalStateException("synthetic-private-detail");
            redeemed.add(new Redemption(previous,proof));assertEquals(live.reference(),previous);return guest;
        }
        public boolean revoke(SessionReference reference) { throw new AssertionError(); }
    }
    record Redemption(SessionReference previous,GuestAccessStore.Redemption proof) { }

    final List<String> subjects=new ArrayList<>();
    final List<AuthDeliveryStore.Issue> issued=new ArrayList<>();
    Decision decision=Decision.allow(List.of(new Reservation(RateLimitStore.Kind.EMAIL_IP,"a".repeat(64),Instant.EPOCH)));
    Optional<UUID> found=Optional.empty();boolean deliveryFails;
    Sessions sessions;MockMvc mvc;
    final RateLimitStore limits=new RateLimitStore() {
        public Decision admit(List<Charge> charges) { charges.forEach(c->subjects.add(c.kind()+":"+c.subject()));return decision; }
        public boolean release(Reservation reservation) { return false; }
    };
    final AuthDeliveryStore delivery=new AuthDeliveryStore() {
        public Receipt issue(Issue issue) { if(deliveryFails)throw new IllegalStateException("synthetic-private-detail");issued.add(issue);return new Receipt(UUID.randomUUID(),UUID.randomUUID(),Instant.EPOCH); }
        public int eraseExpired() { return 0; }
        public Optional<MailDelivery.Message> prepare(NotificationQueue.Lease lease) { throw new AssertionError(); }
    };

    @BeforeEach void setup() { build(Duration.ZERO); }
    void build(Duration latency) {
        sessions=new Sessions();var access=new SessionAccess(sessions);var cookies=SessionCookies.operational();
        var proofs=new GuestProofs(new RequestThrottle(limits),delivery,new GuestRecovery(request->found,delivery));
        mvc=MockMvcBuilders.standaloneSetup(new GuestAuthController(proofs,access,cookies,latency))
                .addFilters(new ApiGuardFilter(ORIGIN,access,cookies,new ClientAddress(List.of("10.0.0.2")))).build();
    }
    MockHttpServletRequestBuilder json(String path,String body) {
        return post(URI.create(path)).header("Origin",ORIGIN).header("X-CSRF-Token",CSRF).cookie(new Cookie("__Host-purpuragenda",TOKEN))
                .contentType(MediaType.APPLICATION_JSON).content(body).with(r -> { r.setRemoteAddr("198.51.100.4"); return r; });
    }
    MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception { return mvc.perform(request).andReturn().getResponse(); }
    void problem(MockHttpServletResponse response,int status,String code) throws Exception {
        assertEquals(status,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));
        String body=response.getContentAsString();
        assertTrue(body.contains("\"code\":\""+code+"\""),"Expected safe problem code");
        assertFalse(body.contains("synthetic-private-detail") || body.contains("guest@example.test") || body.contains(PROOF));
        assertNull(response.getHeader("Set-Cookie"));
    }
    static final String CREATE="/api/v1/guest/create-proofs",MANAGE="/api/v1/guest/management-proofs",REDEEM="/api/v1/guest/redemptions";

    @Test void createProofIsAcceptedBoundToTheSessionBrowserAndChargedToTheSocketPeer() throws Exception {
        var response=send(json(CREATE,"{\"email\":\"Guest@Example.TEST\"}"));
        assertEquals(202,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));
        assertEquals("{\"accepted\":true}",response.getContentAsString());
        assertEquals(BROWSER,issued.getFirst().browserHash());assertEquals(AuthDeliveryStore.Purpose.CREATE,issued.getFirst().purpose());
        assertTrue(subjects.contains("EMAIL_IP:198.51.100.4/32"));assertTrue(subjects.contains("EMAIL_ADDRESS:guest@example.test"));
        // The trusted proxy header is honoured only from the allowlisted peer.
        subjects.clear();
        send(json(CREATE,"{\"email\":\"guest@example.test\"}").with(r -> { r.setRemoteAddr("10.0.0.2"); return r; }).header(ClientAddress.HEADER,"203.0.113.9"));
        assertTrue(subjects.contains("EMAIL_IP:203.0.113.9/32"));
        subjects.clear();
        send(json(CREATE,"{\"email\":\"guest@example.test\"}").header(ClientAddress.HEADER,"203.0.113.77").header("X-Forwarded-For","203.0.113.78"));
        assertTrue(subjects.contains("EMAIL_IP:198.51.100.4/32"));assertEquals(1,subjects.stream().filter(s->s.startsWith("EMAIL_IP")).count());
    }
    @Test void knownAndUnknownManagementRequestsProduceByteIdenticalResponses() throws Exception {
        String body="{\"reference\":\"SYNTHETIC1\",\"email\":\"guest@example.test\"}";
        var unknown=send(json(MANAGE,body));found=Optional.of(UUID.randomUUID());var known=send(json(MANAGE,body));
        assertEquals(202,unknown.getStatus());assertEquals(unknown.getStatus(),known.getStatus());
        assertEquals(unknown.getContentAsString(),known.getContentAsString());assertEquals(unknown.getHeaderNames(),known.getHeaderNames());
        assertEquals(1,issued.size());assertEquals(AuthDeliveryStore.Purpose.MANAGE,issued.getFirst().purpose());
    }
    @Test void minimumLatencyHidesTheCostOfKnownContacts() throws Exception {
        build(Duration.ofMillis(200));
        String body="{\"reference\":\"SYNTHETIC1\",\"email\":\"guest@example.test\"}";
        for(boolean known:new boolean[]{false,true}) {
            found=known?Optional.of(UUID.randomUUID()):Optional.empty();
            long start=System.nanoTime();assertEquals(202,send(json(MANAGE,body)).getStatus());
            assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()>=190,"known="+known);
        }
        var access=new SessionAccess(sessions);var proofs=new GuestProofs(new RequestThrottle(limits),delivery,new GuestRecovery(request->found,delivery));
        assertThrows(IllegalArgumentException.class,()->new GuestAuthController(proofs,access,SessionCookies.local(),Duration.ofSeconds(6)));
        assertThrows(IllegalArgumentException.class,()->new GuestAuthController(proofs,access,SessionCookies.local(),Duration.ofMillis(-1)));
        assertThrows(NullPointerException.class,()->new GuestAuthController(null,access,SessionCookies.local(),Duration.ZERO));
    }
    @Test void throttleDenialIs429WithRetryAfterAndNeverTouchesDelivery() throws Exception {
        decision=Decision.deny(57);
        for(String path:new String[]{CREATE,MANAGE}) {
            var response=send(json(path,path.equals(CREATE)?"{\"email\":\"guest@example.test\"}":"{\"reference\":\"SYNTHETIC1\",\"email\":\"guest@example.test\"}"));
            problem(response,429,"RATE_LIMITED");assertEquals("57",response.getHeader("Retry-After"));
        }
        assertTrue(issued.isEmpty());
    }
    @Test void malformedBodiesAreOneSafeProblemShapeAndChargeNothing() throws Exception {
        String[] bad={"","{}","[]","null","{\"email\":1}","{\"email\":\"\"}","{\"email\":\"guest@example.test\",\"extra\":\"x\"}",
                "{\"email\":\"guest@example.test\"} trailing","{\"email\":\"a@example.test\",\"email\":\"b@example.test\"}",
                "{\"email\":\"not-an-email\"}","{\"email\":\""+"x".repeat(3000)+"\"}","{\"email\":"};
        for(String body:bad) problem(send(json(CREATE,body)),400,"INVALID_REQUEST");
        problem(send(json(CREATE,"{\"email\":\"guest@example.test\"}").contentType(MediaType.TEXT_PLAIN)),400,"INVALID_REQUEST");
        problem(send(json(MANAGE,"{\"reference\":\"x\",\"email\":\"guest@example.test\"}")),400,"INVALID_REQUEST");
        assertTrue(subjects.isEmpty());assertTrue(issued.isEmpty());
    }
    @Test void storageFailureIsARetryableGenericProblem() throws Exception {
        deliveryFails=true;
        problem(send(json(CREATE,"{\"email\":\"guest@example.test\"}")),503,"TEMPORARILY_UNAVAILABLE");
        sessions.unavailable=true;
        problem(send(json(REDEEM,"{\"token\":\""+PROOF+"\",\"purpose\":\"CREATE\"}")),503,"TEMPORARILY_UNAVAILABLE");
    }
    @Test void guardRunsFirstOnEverySpellingSoNoRouteDodgesOriginSessionOrCsrf() throws Exception {
        for(String path:new String[]{CREATE,"/api/v1/guest/%63reate-proofs","/api/v1/guest/create-proofs;x=1","/api/v1//guest/create-proofs",MANAGE,REDEEM,"/api/v1/guest/%72edemptions"}) {
            String body="{\"email\":\"guest@example.test\"}";
            assertEquals(403,send(post(URI.create(path)).contentType(MediaType.APPLICATION_JSON).content(body)).getStatus(),path+" origin");
            assertEquals(401,send(post(URI.create(path)).header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON).content(body)).getStatus(),path+" session");
            assertEquals(403,send(post(URI.create(path)).header("Origin",ORIGIN).cookie(new Cookie("__Host-purpuragenda",TOKEN)).contentType(MediaType.APPLICATION_JSON).content(body)).getStatus(),path+" csrf");
            assertEquals(403,send(json(path,body).header("Sec-Fetch-Site","cross-site")).getStatus(),path+" fetch metadata");
        }
        assertTrue(issued.isEmpty());assertTrue(sessions.redeemed.isEmpty());
        // Spelling variants that MVC decodes to the real route are handled and charged exactly like the plain path.
        int before=subjects.size();
        assertEquals(202,send(json("/api/v1/guest/%63reate-proofs","{\"email\":\"guest@example.test\"}")).getStatus());
        assertEquals(202,send(json("/api/v1/guest/create-proofs;x=1","{\"email\":\"guest@example.test\"}")).getStatus());
        assertEquals(before+6,subjects.size());
    }
    @Test void redemptionRotatesTheSessionOnceBoundToTheCookieBrowserAndReturnsAFreshCsrfProof() throws Exception {
        var guest=new Snapshot(new SessionReference(UUID.randomUUID(),BROWSER),Kind.GUEST,UUID.randomUUID(),UUID.randomUUID(),null,null,null,NOW,NOW,NOW.plusSeconds(7200),NOW);
        sessions.guest=Optional.of(new Issued(guest,"g".repeat(43).toCharArray(),"h".repeat(43).toCharArray()));
        var response=send(json(REDEEM,"{\"token\":\""+PROOF+"\",\"purpose\":\"MANAGE\"}"));
        assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));
        assertTrue(response.getHeader("Set-Cookie").startsWith("__Host-purpuragenda="+"g".repeat(43)+"; Path=/; Secure; HttpOnly; SameSite=Lax"));
        String body=response.getContentAsString();
        assertTrue(body.startsWith("{\"role\":\"GUEST\""),"Expected guest session projection");assertTrue(body.contains("\"csrfToken\":\""+"h".repeat(43)+"\""));
        assertFalse(body.contains(guest.grantId().toString()) || body.contains(guest.customerId().toString()));
        var seen=sessions.redeemed.getFirst();
        assertEquals(BROWSER,seen.proof().browserHash());assertEquals(GuestAccessStore.Kind.MANAGE,seen.proof().purpose());assertEquals(PROOF,seen.proof().token());
    }
    @Test void everyRedemptionFailureLooksTheSame() throws Exception {
        String[] bodies={"{\"token\":\""+PROOF+"\",\"purpose\":\"CREATE\"}","{\"token\":\"short\",\"purpose\":\"CREATE\"}","{\"token\":\""+PROOF+"\",\"purpose\":\"ACCOUNT\"}",
                "{\"token\":\""+PROOF+"\"}","[]","{\"token\":\""+PROOF+"\",\"purpose\":\"create\"}"};
        var shapes=new HashSet<String>();
        for(String body:bodies) {
            var response=send(json(REDEEM,body));problem(response,400,"PROOF_INVALID");
            shapes.add(response.getContentAsString().replaceAll("\"correlationId\":\"[^\"]+\"",""));
        }
        assertEquals(1,shapes.size());
    }
}
