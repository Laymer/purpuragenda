package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.RequestThrottle;
import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.port.out.RateLimitStore;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork;
import com.purpuragenda.application.port.out.SessionStore;
import com.purpuragenda.application.scheduling.PublicScheduling;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.scheduling.*;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PublicHttpTest {
    static final String ORIGIN = "https://bookings.example.com";
    static final UUID SERVICE = UUID.fromString("10000000-0000-0000-0000-000000000009");

    static final class Fixture implements SchedulingUnitOfWork, SchedulingUnitOfWork.View {
        Instant now = Instant.parse("2027-01-04T08:00:00Z");
        List<ServiceDefinition> services = List.of(new ServiceDefinition(SERVICE, 3, "Synthetic \"quoted\" service", 60, new Money(1500, "EUR"), true),
                new ServiceDefinition(UUID.randomUUID(), 1, "Archived", 30, new Money(100, "EUR"), false));
        int reads;
        boolean failing;
        public <T> T read(Function<View, T> work) { reads++; if (failing) throw new IllegalStateException("synthetic-private-detail"); return work.apply(this); }
        public Receipt execute(CommandKey key, Function<Transaction, Receipt> work) { throw new AssertionError("anonymous read only"); }
        public Instant now() { return now; }
        public List<ServiceDefinition> services() { return services; }
        public SchedulingPolicy policy() { return new SchedulingPolicy(7, 30, 20, java.time.Duration.ZERO, java.time.Duration.ofDays(1), java.time.Duration.ZERO, java.time.Duration.ZERO); }
        public AvailabilityCalendar calendar() { return AvailabilityCalendar.defaults(); }
        public Optional<Booking> booking(UUID id) { throw new AssertionError(); }
        public List<Booking> bookings(ReservationInterval interval) { return List.of(); }
    }

    /** Counts per normalized subject with the approved 120-per-minute ceiling; windows are proven against PostgreSQL elsewhere. */
    static final class Counting implements RateLimitStore {
        final Map<String, Integer> counts = new HashMap<>();
        final List<String> subjects = new ArrayList<>();
        boolean failing;
        long retry = 60;
        public Decision admit(List<Charge> charges) {
            if (failing) throw new IllegalStateException("synthetic-private-detail");
            var charge = charges.getFirst();
            assertEquals(Kind.AVAILABILITY_IP, charge.kind());
            subjects.add(charge.subject());
            int count = counts.merge(charge.subject(), 1, Integer::sum);
            return count > charge.kind().limit() ? Decision.deny(retry)
                    : Decision.allow(List.of(new Reservation(charge.kind(), "a".repeat(64), Instant.EPOCH)));
        }
        public boolean release(Reservation reservation) { return false; }
    }

    Fixture fixture;
    Counting limits;
    MockMvc mvc;

    @BeforeEach void setup() {
        fixture = new Fixture();
        limits = new Counting();
        var sessions = (SessionStore) Proxy.newProxyInstance(SessionStore.class.getClassLoader(), new Class<?>[]{SessionStore.class},
                (p, m, a) -> { throw new AssertionError("public GET never touches sessions"); });
        mvc = MockMvcBuilders.standaloneSetup(new PublicController(new PublicScheduling(fixture)))
                .addFilters(new ApiGuardFilter(ORIGIN, new SessionAccess(sessions), SessionCookies.operational(), new ClientAddress(List.of("10.0.0.2"))),
                        new AvailabilityThrottleFilter(new RequestThrottle(limits))).build();
    }

    String availability(String query, int status) throws Exception {
        var response = mvc.perform(get("/api/v1/availability?" + query)).andExpect(status().is(status)).andReturn().getResponse();
        return response.getContentAsString();
    }
    static String query(String from, String to, String zone) { return "serviceId=" + SERVICE + "&from=" + from + "&to=" + to + "&displayZone=" + zone; }
    void problem(String body, String code, boolean retryable) {
        assertTrue(body.contains("\"code\":\"" + code + "\""), body);
        assertTrue(body.contains("\"retryable\":" + retryable));
        assertTrue(body.matches(".*\"correlationId\":\"[0-9a-f-]{36}\".*"));
        assertFalse(body.contains("synthetic-private-detail") || body.contains("Exception") || body.contains(SERVICE.toString()));
    }

    @Test void catalogMapsEveryFieldExplicitlyHidesArchivedAndRevalidatesWithAStrongEtag() throws Exception {
        var first = mvc.perform(get("/api/v1/services")).andExpect(status().isOk()).andReturn().getResponse();
        assertEquals("[{\"id\":\"" + SERVICE + "\",\"version\":3,\"name\":\"Synthetic \\\"quoted\\\" service\",\"durationMinutes\":60,"
                + "\"priceMinor\":1500,\"currency\":\"EUR\",\"active\":true}]", first.getContentAsString());
        assertEquals("no-cache", first.getHeader("Cache-Control"));
        assertTrue(first.getContentType().startsWith("application/json"));
        String etag = first.getHeader("ETag");
        assertTrue(etag.matches("\"[0-9a-f]{32}\""));
        for (String match : new String[]{etag, "W/" + etag, "\"other\", " + etag, "*"}) {
            var revalidated = mvc.perform(get("/api/v1/services").header("If-None-Match", match)).andExpect(status().isNotModified()).andReturn().getResponse();
            assertEquals(etag, revalidated.getHeader("ETag"));
            assertEquals("", revalidated.getContentAsString());
        }
        mvc.perform(get("/api/v1/services").header("If-None-Match", "\"other\"")).andExpect(status().isOk());
        // A price change produces a new validator.
        fixture.services = List.of(new ServiceDefinition(SERVICE, 4, "Synthetic \"quoted\" service", 60, new Money(1600, "EUR"), true));
        assertNotEquals(etag, mvc.perform(get("/api/v1/services")).andReturn().getResponse().getHeader("ETag"));
        assertEquals(0, limits.subjects.size(), "the catalog is not part of the availability throttle");
    }

    @Test void catalogStorageFailureIsASafeRetryableProblem() throws Exception {
        fixture.failing = true;
        var response = mvc.perform(get("/api/v1/services")).andExpect(status().isServiceUnavailable()).andReturn().getResponse();
        assertEquals("no-store", response.getHeader("Cache-Control"));
        problem(response.getContentAsString(), "TEMPORARILY_UNAVAILABLE", true);
    }

    @Test void availabilityUsesTheContractFieldNamesNoStoreAndBothDurationsWithDisplayZoneOffsets() throws Exception {
        var response = mvc.perform(get("/api/v1/availability?" + query("2027-01-05", "2027-01-05", "America/New_York"))).andExpect(status().isOk()).andReturn().getResponse();
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertNull(response.getHeader("ETag"));
        String body = response.getContentAsString();
        assertTrue(body.startsWith("{\"serverNow\":\"2027-01-04T08:00:00Z\",\"businessZone\":\"Europe/Brussels\",\"displayZone\":\"America/New_York\","
                + "\"horizonInstant\":\"2027-02-04T08:00:00Z\",\"policyVersion\":7,\"slots\":[{\"startInstant\":\"2027-01-05T08:00:00Z\","
                + "\"sessionEndInstant\":\"2027-01-05T09:00:00Z\",\"reservedEndInstant\":\"2027-01-05T10:00:00Z\",\"businessDate\":\"2027-01-05\","
                + "\"displayLocalTime\":\"2027-01-05T03:00\",\"offset\":\"-05:00\"}"), body);
        assertTrue(body.endsWith("],\"restrictions\":[]}"));
        for (String internal : new String[]{"\"start\"", "sessionEnd\"", "\"horizon\"", "displayLocal\"", "displayOffset", "reservationEnd"})
            assertFalse(body.contains(internal), internal);
    }

    @Test void brusselsSpringForwardOmitsTheGapAndAutumnOverlapKeepsExplicitOffsets() throws Exception {
        fixture.now = Instant.parse("2027-03-20T08:00:00Z");
        String spring = availability(query("2027-03-28", "2027-03-28", "Europe/Brussels"), 200);
        assertTrue(spring.contains("\"displayLocalTime\":\"2027-03-28T09:00\",\"offset\":\"+02:00\""), spring);
        assertFalse(spring.contains("T02:") || spring.contains("T02:30"), "nonexistent local times are omitted");
        String zone = availability(query("2027-03-28", "2027-03-28", "America/New_York"), 200);
        assertTrue(zone.contains("-04:00"), zone);
        fixture.now = Instant.parse("2027-10-20T08:00:00Z");
        String autumn = availability(query("2027-10-31", "2027-10-31", "Europe/Brussels"), 200);
        assertTrue(autumn.contains("\"displayLocalTime\":\"2027-10-31T09:00\",\"offset\":\"+01:00\""), autumn);
        // A display zone whose own overlap falls inside the range yields both instants labelled by offset.
        fixture.now = Instant.parse("2027-10-30T00:00:00Z");
        String london = availability(query("2027-10-31", "2027-10-31", "Europe/London"), 200);
        assertTrue(london.contains("\"offset\":\"Z\"") || london.contains("\"offset\":\"+00:00\""), london);
    }

    @Test void monthEndHorizonIsInclusiveAndNothingBeyondIt() throws Exception {
        fixture.now = Instant.parse("2027-01-31T10:00:00Z"); // horizon is the clamped calendar month: 2027-02-28T10:00Z
        String body = availability(query("2027-02-28", "2027-03-01", "Europe/Brussels"), 200);
        assertTrue(body.contains("\"horizonInstant\":\"2027-02-28T10:00:00Z\""), body);
        var starts = new ArrayList<Instant>();
        var matcher = java.util.regex.Pattern.compile("\"startInstant\":\"([^\"]+)\"").matcher(body);
        while (matcher.find()) starts.add(Instant.parse(matcher.group(1)));
        assertFalse(starts.isEmpty());
        assertTrue(starts.stream().noneMatch(s -> s.isAfter(Instant.parse("2027-02-28T10:00:00Z"))));
        assertEquals(Instant.parse("2027-02-28T10:00:00Z"), starts.getLast());
        assertTrue(availability(query("2027-03-01", "2027-03-01", "Europe/Brussels"), 200).contains("\"slots\":[]"));
    }

    @Test void thirtyOneDatesAreAllowedAndThirtyTwoAreRejectedAsTooWide() throws Exception {
        availability(query("2027-01-05", "2027-02-04", "Europe/Brussels"), 200);
        problem(availability(query("2027-01-05", "2027-02-05", "Europe/Brussels"), 400), "RANGE_TOO_WIDE", false);
        problem(availability(query("2027-01-05", "2027-01-04", "Europe/Brussels"), 400), "INVALID_DATE_RANGE", false);
    }

    @Test void malformedMissingDuplicateOrUnknownInputsAreSafeProblemsWithoutEchoes() throws Exception {
        String good = query("2027-01-05", "2027-01-05", "Europe/Brussels");
        for (String bad : new String[]{"", "serviceId=" + SERVICE, good.replace(SERVICE.toString(), "not-a-uuid"),
                good.replace("2027-01-05", "2027-1-5"), good.replace("from=2027-01-05", "from=2027-02-30"), good + "&from=2027-01-06",
                good.replace("displayZone=Europe/Brussels", "displayZone="), good.replace("displayZone=Europe/Brussels", "displayZone=" + "x".repeat(65)),
                good.replace("from=2027-01-05", "from=%EF%BC%92027-01-05")})
            problem(availability(bad, 400), "INVALID_REQUEST", false);
        problem(availability(good.replace("Europe/Brussels", "Mars/Olympus"), 400), "INVALID_ZONE", false);
        problem(availability(good.replace(SERVICE.toString(), UUID.randomUUID().toString()), 404), "SERVICE_UNAVAILABLE", false);
        fixture.services = List.of(new ServiceDefinition(SERVICE, 3, "Archived now", 60, new Money(1, "EUR"), false));
        problem(availability(good, 404), "SERVICE_UNAVAILABLE", false);
        fixture.failing = true;
        problem(availability(good, 503), "TEMPORARILY_UNAVAILABLE", true);
    }

    @Test void throttleAllowsExactlyOneHundredTwentyThenAnswers429WithRetryAfterAndNoStore() throws Exception {
        String good = query("2027-01-05", "2027-01-05", "Europe/Brussels");
        for (int i = 0; i < 120; i++) mvc.perform(get("/api/v1/availability?" + good)).andExpect(status().isOk());
        var denied = mvc.perform(get("/api/v1/availability?" + good)).andExpect(status().isTooManyRequests()).andReturn().getResponse();
        assertEquals("60", denied.getHeader("Retry-After"));
        assertEquals("no-store", denied.getHeader("Cache-Control"));
        assertTrue(denied.getContentType().startsWith("application/problem+json"));
        problem(denied.getContentAsString(), "RATE_LIMITED", true);
        int reads = fixture.reads;
        mvc.perform(get("/api/v1/availability?" + good)).andExpect(status().isTooManyRequests());
        assertEquals(reads, fixture.reads, "a throttled request performs no scheduling read");
    }

    @Test void malformedRequestsAreChargedAndThrottleFailureFailsClosedWithoutDetail() throws Exception {
        for (int i = 0; i < 120; i++) mvc.perform(get("/api/v1/availability")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/availability")).andExpect(status().isTooManyRequests());
        limits.failing = true;
        var response = mvc.perform(get("/api/v1/availability?" + query("2027-01-05", "2027-01-05", "UTC"))).andExpect(status().isServiceUnavailable()).andReturn().getResponse();
        problem(response.getContentAsString(), "TEMPORARILY_UNAVAILABLE", true);
        assertEquals(0, fixture.reads);
    }

    @Test void clientAddressComesFromTheTrustedProxyHeaderOnlyAndSpoofingFromOtherPeersCannotEscapeTheBucket() throws Exception {
        String good = "/api/v1/availability?" + query("2027-01-05", "2027-01-05", "UTC");
        // Trusted proxy peer: the single literal header identifies the visitor; IPv6 collapses to one /64.
        mvc.perform(get(good).with(r -> { r.setRemoteAddr("10.0.0.2"); return r; }).header(ClientAddress.HEADER, "203.0.113.9")).andExpect(status().isOk());
        mvc.perform(get(good).with(r -> { r.setRemoteAddr("10.0.0.2"); return r; }).header(ClientAddress.HEADER, "2001:db8:1:2::1")).andExpect(status().isOk());
        mvc.perform(get(good).with(r -> { r.setRemoteAddr("10.0.0.2"); return r; }).header(ClientAddress.HEADER, "2001:db8:1:2:ffff::9")).andExpect(status().isOk());
        assertEquals(List.of("203.0.113.9/32", "20010db800010002/64", "20010db800010002/64"), limits.subjects);
        limits.subjects.clear();
        // Untrusted peers, forwarded headers, repeated or malformed values: the socket peer is charged.
        mvc.perform(get(good).with(r -> { r.setRemoteAddr("198.51.100.4"); return r; }).header(ClientAddress.HEADER, "203.0.113.50")
                .header("X-Forwarded-For", "203.0.113.51").header("Forwarded", "for=203.0.113.52")).andExpect(status().isOk());
        mvc.perform(get(good).with(r -> { r.setRemoteAddr("10.0.0.2"); return r; }).header(ClientAddress.HEADER, "203.0.113.60", "203.0.113.61")).andExpect(status().isOk());
        mvc.perform(get(good).with(r -> { r.setRemoteAddr("10.0.0.2"); return r; }).header(ClientAddress.HEADER, "not-an-ip")).andExpect(status().isOk());
        mvc.perform(get(good).with(r -> { r.setRemoteAddr("10.0.0.2"); return r; }).header(ClientAddress.HEADER, "203.0.113.70, 203.0.113.71")).andExpect(status().isOk());
        assertEquals(List.of("198.51.100.4/32", "10.0.0.2/32", "10.0.0.2/32", "10.0.0.2/32"), limits.subjects);
        // Rotating spoofed headers from an untrusted peer never opens a new bucket.
        limits.counts.clear();
        for (int i = 0; i < 121; i++) mvc.perform(get(good).with(r -> { r.setRemoteAddr("198.51.100.4"); return r; }).header(ClientAddress.HEADER, "203.0.113." + (i % 250))).andReturn();
        assertEquals(1, limits.counts.size());
        mvc.perform(get(good).with(r -> { r.setRemoteAddr("198.51.100.4"); return r; }).header(ClientAddress.HEADER, "203.0.113.200")).andExpect(status().isTooManyRequests());
    }

    @Test void unsafeMethodsOnPublicRoutesStillRequireTheGuardAndGetNoSessionAccess() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/availability")).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/services")).andExpect(status().isForbidden());
    }

    @Test void constructorsRejectMissingDependenciesAndEscapingCoversControlCharacters() {
        assertThrows(NullPointerException.class, () -> new PublicController(null));
        assertThrows(NullPointerException.class, () -> new AvailabilityThrottleFilter(null));
        assertEquals("\"a\\\"b\\\\c\\u0001\\u007f\"", PublicController.string("a\"b\\c\u0001\u007f"));
    }
}
