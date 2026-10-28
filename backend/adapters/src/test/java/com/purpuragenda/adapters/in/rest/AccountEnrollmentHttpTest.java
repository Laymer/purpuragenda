package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.*;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.RateLimitStore.*;
import com.purpuragenda.application.port.out.SessionStore.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class AccountEnrollmentHttpTest {
    static final String BASE="/api/v1/account",TOKEN="a".repeat(43),BODY="{\"token\":\""+TOKEN+"\",\"password\":\"synthetic-account-passphrase\"}";
    Decision decision=Decision.allow(List.of(new Reservation(RateLimitStore.Kind.ACCOUNT_ENROLLMENT_IP,"b".repeat(64),Instant.EPOCH)));boolean success=true;RuntimeException failure;int hashes,issues,redemptions;char[] supplied;AccountEnrollmentStore.Enrollment redeemed;AuthDeliveryStore.Issue issued;
    final RateLimitStore limits=new RateLimitStore() {
        public Decision admit(List<Charge> charges) { return decision; }
        public boolean release(Reservation reservation) { return true; }
    };
    final AuthDeliveryStore delivery=new AuthDeliveryStore() {
        public Receipt issue(Issue value) { issues++;if(failure!=null)throw failure;issued=value;return new Receipt(UUID.randomUUID(),UUID.randomUUID(),Instant.EPOCH); }
        public int eraseExpired() { return 0; }
        public Optional<MailDelivery.Message> prepare(NotificationQueue.Lease lease) { throw new AssertionError(); }
    };
    final PasswordHash passwords=new PasswordHash() {
        public String hash(char[] value) { hashes++;supplied=value;if(failure!=null)throw failure;return "synthetic-encoded-password"; }
        public boolean verify(char[] value,String encoded) { throw new AssertionError(); }
    };
    final AccountEnrollmentStore store=value->{redemptions++;redeemed=value;return success?Optional.of(new AccountEnrollmentStore.Enrolled(UUID.randomUUID(),UUID.randomUUID(),Instant.EPOCH)):Optional.empty();};
    AccountEnrollmentProofs proofs;AccountEnrollmentRequest enrollment;MockMvc mvc;
    @BeforeEach void setup() { proofs=new AccountEnrollmentProofs(new RequestThrottle(limits),delivery);enrollment=new AccountEnrollmentRequest(new AccountEnrollment(store,passwords),new RequestThrottle(limits));mvc=MockMvcBuilders.standaloneSetup(controller(Duration.ZERO)).build(); }
    AccountEnrollmentController controller(Duration latency) { return new AccountEnrollmentController(proofs,enrollment,latency); }
    Snapshot session() { return new Snapshot(new SessionReference(UUID.randomUUID(),"b".repeat(64)),SessionStore.Kind.ANONYMOUS,null,null,null,null,null,Instant.EPOCH,Instant.EPOCH,Instant.EPOCH.plusSeconds(3600),null); }
    MockHttpServletRequestBuilder request(String path,String body) { return post(BASE+path).requestAttr(ApiGuardFilter.SESSION_ATTRIBUTE,session()).requestAttr(ApiGuardFilter.CLIENT_ATTRIBUTE,"203.0.113.9").contentType("application/json").content(body); }
    MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception { return mvc.perform(request).andReturn().getResponse(); }
    void problem(MockHttpServletResponse response,int status,String code) throws Exception { assertEquals(status,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(response.getHeader("Set-Cookie"));assertTrue(response.getContentAsString().contains("\"code\":\""+code+"\""));assertFalse(response.getContentAsString().contains("synthetic")); }
    @Test void explicitIssuanceAndEnrollmentDoNotCreateSessionOrReturnAuthority() throws Exception {
        var proof=send(request("/enrollment-proofs","{\"email\":\"Synthetic@Example.TEST\"}"));assertEquals(202,proof.getStatus());assertEquals("{\"accepted\":true}",proof.getContentAsString());assertEquals("no-store",proof.getHeader("Cache-Control"));assertNull(proof.getHeader("Set-Cookie"));assertEquals(AuthDeliveryStore.Purpose.ACCOUNT,issued.purpose());assertEquals("b".repeat(64),issued.browserHash());
        var response=send(request("/enrollments",BODY));assertEquals(204,response.getStatus());assertEquals("",response.getContentAsString());assertNull(response.getHeader("Set-Cookie"));assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals(TOKEN,redeemed.token());assertEquals("b".repeat(64),redeemed.browserHash());assertArrayEquals(new char[supplied.length],supplied);
        success=false;problem(send(request("/enrollments",BODY)),400,"PROOF_INVALID");
    }
    @Test void denialPrecedesHashingAndKeepsPositiveRetryMetadata() throws Exception {
        decision=Decision.deny(42);
        for(var request:List.of(request("/enrollment-proofs","{\"email\":\"synthetic@example.test\"}"),request("/enrollments",BODY))) { var response=send(request);problem(response,429,"RATE_LIMITED");assertEquals("42",response.getHeader("Retry-After")); }
        assertEquals(0,hashes);assertEquals(0,issues);assertEquals(0,redemptions);
    }
    @Test void strictBodiesQueriesAndMissingGuardCannotInvokeEnrollment() throws Exception {
        for(String path:List.of("/enrollment-proofs","/enrollments")) {
            problem(send(post(BASE+path)),401,"SESSION_REQUIRED");problem(send(request(path,"{}").param("unexpected","1")),400,"INVALID_REQUEST");
            for(String body:List.of("{}","null","[]","{}{}","x".repeat(16385)))problem(send(request(path,body)),400,"INVALID_REQUEST");
        }
        problem(send(request("/enrollment-proofs","{\"email\":\"bad\"}")),400,"INVALID_REQUEST");
        problem(send(request("/enrollment-proofs","{\"email\":\"a@example.test\",\"email\":\"b@example.test\"}")),400,"INVALID_REQUEST");
        problem(send(request("/enrollments",BODY.replace(TOKEN,"bad"))),400,"PROOF_INVALID");problem(send(request("/enrollments",BODY.replace("synthetic-account-passphrase","short"))),400,"INVALID_REQUEST");
        problem(send(request("/enrollments",BODY.replace("\"password\":","\"unexpected\":true,\"password\":"))),400,"INVALID_REQUEST");assertEquals(0,hashes);assertEquals(0,issues);assertEquals(0,redemptions);
    }
    @Test void failuresStayGenericAndCredentialBuffersAreCleared() throws Exception {
        for(var error:List.of(new IllegalStateException("synthetic-provider-detail"),new IllegalArgumentException("synthetic"),new com.purpuragenda.domain.DomainFailure("synthetic"))) {
            failure=error;int status=error instanceof IllegalStateException?503:400;
            problem(send(request("/enrollment-proofs","{\"email\":\"synthetic@example.test\"}")),status,status==503?"TEMPORARILY_UNAVAILABLE":"INVALID_REQUEST");problem(send(request("/enrollments",BODY)),status,status==503?"TEMPORARILY_UNAVAILABLE":"INVALID_REQUEST");assertArrayEquals(new char[supplied.length],supplied);
        }
    }
    @Test void latencyBoundsAndInterruptedPaddingPreserveInterruption() throws Exception {
        assertThrows(IllegalArgumentException.class,()->controller(Duration.ofSeconds(-1)));assertThrows(IllegalArgumentException.class,()->controller(Duration.ofSeconds(6)));
        mvc=MockMvcBuilders.standaloneSetup(controller(Duration.ofMillis(30))).build();
        assertEquals(202,send(request("/enrollment-proofs","{\"email\":\"synthetic@example.test\"}").with(r->{r.removeAttribute(ApiGuardFilter.CLIENT_ATTRIBUTE);return r;})).getStatus());
        Thread.currentThread().interrupt();try { assertEquals(204,send(request("/enrollments",BODY)).getStatus());assertTrue(Thread.currentThread().isInterrupted()); }finally { Thread.interrupted(); }
    }
}
