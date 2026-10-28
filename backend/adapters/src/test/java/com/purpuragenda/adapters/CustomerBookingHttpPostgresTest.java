package com.purpuragenda.adapters;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.purpuragenda.adapters.in.rest.*;
import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.adapters.out.security.*;
import com.purpuragenda.application.*;
import com.purpuragenda.application.scheduling.*;
import jakarta.servlet.http.Cookie;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real persisted email proof, guarded HTTP, scheduling transactions and customer reads; no HTTP authentication bypass. */
@Timeout(240)
@ResourceLock("postgres-migration-fixture")
class CustomerBookingHttpPostgresTest {
    final CustomerCreationPostgresTest c=new CustomerCreationPostgresTest();
    final GuestAuthPostgresTest g=new GuestAuthPostgresTest();
    final ObjectMapper json=new JsonMapper();
    @TempDir Path directory;
    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void setup() throws Exception {
        c.directory=directory;c.setup();g.f=c.f;g.jdbc=c.jdbc;
        var access=new SessionAccess(c.sessions);var cookies=SessionCookies.operational();
        byte[] bytes=new byte[32];Arrays.fill(bytes,(byte)9);
        var throttle=new RequestThrottle(new PostgresRateLimits(c.f.source,new RateKey(Files.write(directory.resolve("http-rate.key"),bytes),"http-test-v1")));
        var proofs=new GuestProofs(throttle,c.f.delivery,new GuestRecovery(new PostgresGuestRecoveryLookup(c.f.source),c.f.delivery));
        g.mvc=MockMvcBuilders.standaloneSetup(new SessionController(access,cookies),new GuestAuthController(proofs,access,cookies,Duration.ZERO),
                new CustomerAvailabilityController(new CustomerReschedulingAvailability(new PostgresCustomerBookingQuery(c.f.source,Set.of("zoom.us"))),access,cookies),
                new GuestBookingController(new GuestBookingView(new PostgresCustomerBookingQuery(c.f.source,Set.of("zoom.us"))),access,cookies),
                new CustomerContactController(new CustomerContactChanges(new PostgresContactChanges(c.f.source,c.f.delivery),throttle)),
                new CustomerBookingController(c.command,new CustomerBookingChanges(new PostgresBookingChanges(c.f.source)),
                        new CustomerBookingView(new PostgresCustomerBookingQuery(c.f.source,Set.of("zoom.us"))),access,cookies))
                .addFilters(new ApiGuardFilter(GuestAuthPostgresTest.ORIGIN,access,cookies,new ClientAddress(List.of())),new AvailabilityThrottleFilter(throttle)).build();
    }
    @AfterEach void cleanup() { c.cleanup(); }
    GuestAuthPostgresTest.Browser guest(String email) throws Exception {
        var browser=g.boot();assertEquals(202,g.send(g.json(browser,"/api/v1/guest/create-proofs","{\"email\":\""+email+"\"}")).getStatus());
        var response=g.send(g.json(browser,"/api/v1/guest/redemptions",g.redeemBody(g.proofToken(),"CREATE")));assertEquals(200,response.getStatus());
        String cookie=response.getHeader("Set-Cookie").split(";",2)[0].split("=",2)[1];
        return new GuestAuthPostgresTest.Browser(cookie,json.readTree(response.getContentAsString()).get("csrfToken").textValue(),browser.browserHash());
    }
    ObjectNode body(int day) {
        var request=c.request(day,9);ObjectNode body=json.createObjectNode();body.put("serviceId",request.serviceId().toString()).put("serviceVersion",1).put("policyVersion",1)
                .put("startInstant",request.start().toString()).put("displayZone",request.displayZone()).put("noticeVersion","local-v1");
        body.putObject("intake").put("firstName","Synthetic").put("lastName","Customer").put("telephone","+32400123456")
                .put("birthDate","1990-01-01").put("birthTime","12:00:30").put("birthplace","Synthetic city").put("message","");return body;
    }
    MockHttpServletResponse create(GuestAuthPostgresTest.Browser b,String key,ObjectNode body) throws Exception {
        return g.send(g.json(b,"/api/v1/bookings",body.toString()).header("Idempotency-Key",key));
    }
    String booking(MockHttpServletResponse response) throws Exception { assertEquals(201,response.getStatus());return json.readTree(response.getContentAsString()).get("bookingId").textValue(); }
    MockHttpServletResponse view(GuestAuthPostgresTest.Browser b,String id) throws Exception {
        return g.send(get("/api/v1/bookings/"+id).cookie(new Cookie("__Host-purpuragenda",b.cookie())));
    }
    @Test void privateAndPublicAvailabilityConsumeTheSamePersistedBudgetBeforeAnyAdvice() throws Exception {
        var browser=guest("limited-advice@example.test");var request=body(3);String id=booking(create(browser,"limited-advice",request));
        String day=Instant.parse(request.get("startInstant").textValue()).atZone(ZoneId.of("Europe/Brussels")).toLocalDate().toString();
        String path="/api/v1/bookings/"+id+"/availability";
        assertEquals(200,g.send(get(path).param("from",day).param("to",day).param("displayZone","UTC").cookie(new Cookie("__Host-purpuragenda",browser.cookie()))).getStatus());
        for(int i=1;i<120;i++)assertEquals(400,g.send(get("/api/v1/bookings/malformed/availability")).getStatus());
        for(String target:List.of(path,"/api/v1/availability")) {
            var denied=g.send(get(target).cookie(new Cookie("__Host-purpuragenda",browser.cookie())));
            assertEquals(429,denied.getStatus());assertEquals("no-store",denied.getHeader("Cache-Control"));assertTrue(Long.parseLong(denied.getHeader("Retry-After"))>0);
        }
        assertEquals(1,c.count("rate_limit_bucket WHERE kind='AVAILABILITY_IP'"));
        assertEquals("120",c.f.value("SELECT count FROM rate_limit_bucket WHERE kind='AVAILABILITY_IP'"));
        assertEquals(1,c.count("request_charge"));assertEquals(1,c.load(UUID.fromString(id)).version());
    }
    @Test void reschedulingAdviceUsesRealProofScopeOriginalServiceAndReadOnlySchedulingFacts() throws Exception {
        var browser=guest("advice@example.test");var request=body(3);String id=booking(create(browser,"advice",request));
        var day=Instant.parse(request.get("startInstant").textValue()).atZone(ZoneId.of("Europe/Brussels")).toLocalDate().toString();
        c.jdbc.update("UPDATE service SET active=false WHERE id=?",UUID.fromString(request.get("serviceId").textValue()));
        String seen=c.f.value("SELECT last_seen_at FROM app_session WHERE kind='GUEST'");int events=c.count("outbox_event"),charges=c.count("request_charge");
        var path="/api/v1/bookings/"+id+"/availability";
        var response=g.send(get(path).param("from",day).param("to",day).param("displayZone","Europe/Brussels")
                .header("If-None-Match","*").cookie(new Cookie("__Host-purpuragenda",browser.cookie())));
        assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(response.getHeader("ETag"));
        var slots=json.readTree(response.getContentAsString()).get("slots");assertTrue(slots.size()>0);
        boolean own=false;for(var slot:slots)if(slot.get("startInstant").textValue().equals(request.get("startInstant").textValue()))own=true;
        assertTrue(own);assertFalse(response.getContentAsString().contains("Synthetic city"));
        assertEquals(seen,c.f.value("SELECT last_seen_at FROM app_session WHERE kind='GUEST'"));assertEquals(events,c.count("outbox_event"));assertEquals(charges,c.count("request_charge"));
        var other=g.boot();assertEquals(404,g.send(get(path).param("from",day).param("to",day).param("displayZone","UTC").cookie(new Cookie("__Host-purpuragenda",other.cookie()))).getStatus());
        assertEquals(401,g.send(get(path).param("from",day).param("to",day).param("displayZone","UTC")).getStatus());
        assertEquals(400,g.send(get(path).param("from",day,day).param("to",day).param("displayZone","UTC").cookie(new Cookie("__Host-purpuragenda",browser.cookie()))).getStatus());
    }
    @Test void verifiedCreationReplaysAndReadUsesExplicitPrivateContractWithoutTouchingSession() throws Exception {
        var browser=guest("synthetic@example.test");var request=body(3);var created=create(browser,"create",request);String id=booking(created);
        assertEquals("\"1\"",created.getHeader("ETag"));assertEquals("/api/v1/bookings/"+id,created.getHeader("Location"));assertEquals("no-store",created.getHeader("Cache-Control"));
        assertEquals(id,booking(create(browser,"create",request)));assertEquals(1,c.count("booking"));assertEquals(1,c.count("request_charge"));
        String seen=c.f.value("SELECT last_seen_at FROM app_session WHERE kind='GUEST'");
        var response=view(browser,id);assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("\"1\"",response.getHeader("ETag"));
        JsonNode result=json.readTree(response.getContentAsString());assertEquals("PENDING_CONFIRMATION",result.get("state").textValue());
        assertEquals("UNPAID",result.get("payment").get("status").textValue());assertEquals(2,result.get("allowedActions").size());assertTrue(result.get("meetingUrl").isNull());
        assertFalse(result.has("intake") || result.has("customerId") || response.getContentAsString().contains("Synthetic city"));
        assertEquals(seen,c.f.value("SELECT last_seen_at FROM app_session WHERE kind='GUEST'"));
        var conflict=create(browser,"create",body(4));assertEquals(409,conflict.getStatus());assertTrue(conflict.getContentAsString().contains("IDEMPOTENCY_KEY_REUSED"));
        assertEquals(403,create(browser,"new-booking",body(4)).getStatus());
    }
    @Test void conditionalHeadersCannotHideIndependentMoneyAtAnUnchangedBookingVersion() throws Exception {
        var browser=guest("conditional@example.test");String id=booking(create(browser,"conditional",body(3)));
        assertEquals(0,json.readTree(view(browser,id).getContentAsString()).at("/payment/receivedMinor").asLong());
        var scope=new com.purpuragenda.application.port.out.SchedulingUnitOfWork.Scope(UUID.randomUUID(),"SYNTHETIC_RECEIPT",UUID.fromString(id));
        var key=new com.purpuragenda.application.port.out.SchedulingUnitOfWork.CommandKey(scope,"conditional", "a".repeat(64));
        new PostgresScheduling(c.f.source).execute(key,tx->{
            c.jdbc.update("INSERT INTO payment_entry(id,booking_id,kind,amount_minor,currency,method,reference,verified_at) VALUES(?,?,'RECEIPT',100,'EUR','SYNTHETIC','conditional',?)",
                    UUID.randomUUID(),UUID.fromString(id),java.sql.Timestamp.from(tx.now()));
            return new com.purpuragenda.application.port.out.SchedulingUnitOfWork.Receipt(
                    com.purpuragenda.application.port.out.SchedulingUnitOfWork.Code.OK,null,0);
        });
        String seen=c.f.value("SELECT last_seen_at FROM app_session WHERE kind='GUEST'");
        for(String tag:List.of("\"1\"","*","W/\"1\"")) {
            var response=g.send(get("/api/v1/bookings/"+id).cookie(new Cookie("__Host-purpuragenda",browser.cookie()))
                    .header("If-None-Match",tag).header("If-Modified-Since","Wed, 01 Jan 2099 00:00:00 GMT"));
            assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("\"1\"",response.getHeader("ETag"));
            var body=json.readTree(response.getContentAsString());assertEquals(1,body.get("version").asLong());
            assertEquals(100,body.at("/payment/receivedMinor").asLong());
        }
        assertEquals(seen,c.f.value("SELECT last_seen_at FROM app_session WHERE kind='GUEST'"));
    }
    @Test void unknownUnownedAndSameIdentityOutsideTheGrantAreIndistinguishable() throws Exception {
        var a=guest("one@example.test");String mine=booking(create(a,"a",body(3)));
        var b=guest("two@example.test");String theirs=booking(create(b,"b",body(4)));
        var again=c.guest("one@example.test");String sameIdentity=c.create(again,"c",c.request(5,9)).entityId().toString();
        for(String id:List.of(theirs,sameIdentity,UUID.randomUUID().toString())) {
            var denied=view(a,id);assertEquals(404,denied.getStatus());assertTrue(denied.getContentAsString().contains("BOOKING_NOT_FOUND"));
            assertEquals(404,g.send(g.json(a,"/api/v1/bookings/"+id+"/cancel","{}").header("If-Match","\"1\"").header("Idempotency-Key","deny")).getStatus());
        }
        assertEquals(401,g.send(get("/api/v1/bookings/"+mine)).getStatus());assertEquals(400,view(a,"1-1-1-1-1").getStatus());
        c.jdbc.update("UPDATE access_grant SET revoked_at=clock_timestamp() WHERE kind='CREATE' AND booking_id=?",UUID.fromString(mine));
        // Revoked authority invalidates the session before target lookup, uniformly for every booking.
        for(String id:List.of(mine,theirs,UUID.randomUUID().toString()))assertEquals(401,view(a,id).getStatus());
    }
    @Test void reschedulingAndCancellationUseStrongVersionsAndPreserveIdempotentReceipts() throws Exception {
        var a=guest("synthetic@example.test");String id=booking(create(a,"create",body(3)));String path="/api/v1/bookings/"+id;
        String move="{\"policyVersion\":1,\"startInstant\":\""+c.request(4,9).start()+"\"}";
        assertEquals(428,g.send(g.json(a,path+"/cancel","{}").header("Idempotency-Key","missing")).getStatus());
        assertEquals(400,g.send(g.json(a,path+"/cancel","{}").header("Idempotency-Key","weak").header("If-Match","W/\"1\"")).getStatus());
        String expiry=c.f.value("SELECT expires_at FROM booking WHERE id=?",UUID.fromString(id));
        for(int i=0;i<2;i++)assertEquals("\"2\"",g.send(g.json(a,path+"/reschedule",move).header("Idempotency-Key","move").header("If-Match","\"1\"")).getHeader("ETag"));
        assertEquals(expiry,c.f.value("SELECT expires_at FROM booking WHERE id=?",UUID.fromString(id)));assertEquals(1,c.count("request_charge"));
        var stale=g.send(g.json(a,path+"/cancel","{}").header("Idempotency-Key","stale").header("If-Match","\"1\""));assertEquals(412,stale.getStatus());
        assertTrue(stale.getContentAsString().contains("STALE_VERSION"));
        for(int i=0;i<2;i++) {
            var cancelled=g.send(g.json(a,path+"/cancel","{}").header("Idempotency-Key","cancel").header("If-Match","\"2\""));assertEquals(200,cancelled.getStatus());assertEquals("\"3\"",cancelled.getHeader("ETag"));
        }
        var state=json.readTree(view(a,id).getContentAsString());assertEquals("CANCELLED",state.get("state").textValue());assertEquals(0,state.get("allowedActions").size());
        assertEquals(1,c.count("outbox_event WHERE type='RESCHEDULED'"));assertEquals(1,c.count("outbox_event WHERE type='CANCELLED'"));assertEquals(0,c.count("payment_entry"));
    }
    @Test void guardsAndMalformedInputCannotConsumeAuthorityOrChargeQuota() throws Exception {
        var a=guest("synthetic@example.test");String path="/api/v1/bookings";
        assertEquals(403,g.send(post(path).contentType("application/json").content(body(3).toString())).getStatus());
        assertEquals(401,g.send(post(path).header("Origin",GuestAuthPostgresTest.ORIGIN).contentType("application/json").content(body(3).toString())).getStatus());
        assertEquals(403,g.send(post(path).header("Origin",GuestAuthPostgresTest.ORIGIN).cookie(new Cookie("__Host-purpuragenda",a.cookie())).contentType("application/json").content(body(3).toString())).getStatus());
        assertEquals(400,g.send(g.json(a,path,body(3).toString())).getStatus());
        for(ObjectNode invalid:List.of(body(3).put("serviceVersion","1"),body(3).put("customerId",UUID.randomUUID().toString()),body(3).put("startInstant","not-time"),body(3).put("intake",false)))
            assertEquals(400,create(a,"invalid",invalid).getStatus());
        var birth=body(3);((ObjectNode)birth.get("intake")).put("birthDate","9999-01-01");assertEquals(400,create(a,"future-birth",birth).getStatus());
        assertEquals(0,c.count("booking"));assertEquals(0,c.count("request_charge"));assertEquals(1,c.count("access_grant WHERE consumed_at IS NULL"));
        assertEquals(201,create(a,"valid",body(3)).getStatus());
    }
    @Test void twentyHttpRequestsForOneSlotCommitOneBookingAndNineteenConflictReceipts() throws Exception {
        var browsers=new ArrayList<GuestAuthPostgresTest.Browser>();for(int i=0;i<20;i++)browsers.add(guest("race"+i+"@example.test"));
        var ready=new CountDownLatch(20);var release=new CountDownLatch(1);var statuses=new ArrayList<Future<Integer>>();String request=body(3).toString();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            for(var browser:browsers)statuses.add(executor.submit(()->{ready.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return g.send(g.json(browser,"/api/v1/bookings",request).header("Idempotency-Key","race")).getStatus();}));
            assertTrue(ready.await(10,TimeUnit.SECONDS));release.countDown();int created=0,conflict=0;
            for(var result:statuses){int status=result.get(60,TimeUnit.SECONDS);if(status==201)created++;else if(status==409)conflict++;else fail("Unexpected race response status: "+status);}
            assertEquals(1,created);assertEquals(19,conflict);
        }
        assertEquals(1,c.count("booking"));assertEquals(1,c.count("request_charge"));assertEquals(1,c.count("booking_occupancy WHERE released_at IS NULL"));
        assertEquals(20,c.count("idempotency_record"));assertEquals(1,c.count("outbox_event WHERE type='REQUESTED'"));
    }
    @Test void discoversOnlyTheCurrentGuestTargetAndNeverUsesAClientSuppliedTarget() throws Exception {
        var browser=guest("scoped-http@example.test");String path="/api/v1/guest/booking";
        var cookie=new Cookie("__Host-purpuragenda",browser.cookie());
        assertEquals(401,g.send(get(path)).getStatus());assertEquals(404,g.send(get(path).cookie(cookie)).getStatus());
        String id=booking(create(browser,"scoped",body(3)));
        var another=c.guest("scoped-http@example.test");c.create(another,"other",c.request(4,9));
        String seen=c.f.value("SELECT last_seen_at FROM app_session WHERE browser_hash=?",browser.browserHash());
        var response=g.send(get(path).cookie(cookie).header("If-None-Match","*").header("If-Modified-Since","Wed, 01 Jan 2099 00:00:00 GMT"));
        assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));
        var result=json.readTree(response.getContentAsString());assertEquals(id,result.get("id").asText());
        assertFalse(result.has("intake") || result.has("customerId") || response.getContentAsString().contains("Synthetic city"));
        assertEquals(seen,c.f.value("SELECT last_seen_at FROM app_session WHERE browser_hash=?",browser.browserHash()));
        assertEquals(400,g.send(get(path+"?bookingId="+UUID.randomUUID()).cookie(cookie)).getStatus());
        c.jdbc.update("UPDATE access_grant SET revoked_at=clock_timestamp() WHERE kind='CREATE' AND booking_id=?",UUID.fromString(id));
        assertEquals(401,g.send(get(path).cookie(cookie)).getStatus());
    }

    @Test void managementEmailRedemptionDiscoversItsExactBookingWithoutAClientIdentifier() throws Exception {
        var owner=c.guest("management-http@example.test");UUID id=c.create(owner,"managed",c.request(3,9)).entityId();
        String reference=c.load(id).reference();var browser=g.boot();
        var body=json.createObjectNode().put("email","management-http@example.test").put("reference",reference);
        assertEquals(202,g.send(g.json(browser,"/api/v1/guest/management-proofs",body.toString())).getStatus());
        var redeemed=g.send(g.json(browser,"/api/v1/guest/redemptions",g.redeemBody(g.proofToken(),"MANAGE")));
        assertEquals(200,redeemed.getStatus());
        String cookie=redeemed.getHeader("Set-Cookie").split(";",2)[0].split("=",2)[1];
        var response=g.send(get("/api/v1/guest/booking").cookie(new Cookie("__Host-purpuragenda",cookie)));
        assertEquals(200,response.getStatus());assertTrue(id.toString().equals(json.readTree(response.getContentAsString()).get("id").asText()));
        assertFalse(response.getContentAsString().contains("Synthetic intake"));
    }

}
