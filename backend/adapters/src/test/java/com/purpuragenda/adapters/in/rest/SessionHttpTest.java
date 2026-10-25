package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.port.out.GuestAccessStore;
import com.purpuragenda.application.port.out.SessionStore;
import com.purpuragenda.application.port.out.SessionStore.*;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class SessionHttpTest {
    static final String ORIGIN="https://bookings.example.com";
    static final Pattern TOKEN=Pattern.compile("[A-Za-z0-9_-]{43}");
    /** Stateful in-memory port; persistence and timing are proven against PostgreSQL elsewhere. */
    static final class Memory implements SessionStore {
        record Entry(Snapshot session,String csrf,boolean revoked) { }
        final Map<String,Entry> byToken=new HashMap<>();boolean unavailable;int counter;
        String token(){return String.format("%043d",++counter).replace('0','a');}
        @Override public Issued anonymous() {
            check();var session=new Snapshot(new SessionReference(UUID.randomUUID(),"b".repeat(64)),Kind.ANONYMOUS,null,null,null,null,null,
                    Instant.parse("2026-10-25T10:00:00Z"),Instant.parse("2026-10-25T10:00:00Z"),Instant.parse("2026-10-25T12:00:00Z"),null);
            String token=token(),csrf=token();byToken.put(token,new Entry(session,csrf,false));return new Issued(session,token.toCharArray(),csrf.toCharArray());
        }
        @Override public Optional<CsrfRotation> rotateCsrf(char[] token) {
            check();var entry=byToken.get(new String(token));if(entry==null || entry.revoked)return Optional.empty();
            String csrf=token();byToken.put(new String(token),new Entry(entry.session,csrf,false));return Optional.of(new CsrfRotation(entry.session,csrf.toCharArray()));
        }
        @Override public Optional<Snapshot> resolve(char[] token) { check();var e=byToken.get(new String(token));return e==null || e.revoked?Optional.empty():Optional.of(e.session); }
        @Override public boolean csrf(SessionReference reference,char[] csrf) {
            return byToken.values().stream().anyMatch(e->e.session.reference().equals(reference) && !e.revoked && e.csrf.equals(new String(csrf)));
        }
        @Override public Optional<Issued> exchangeAdmin(SessionReference previous,String requestHash,char[] proofToken) { throw new AssertionError(); }
        @Override public Optional<Issued> exchangeGuest(SessionReference previous,GuestAccessStore.Redemption proof) { throw new AssertionError(); }
        @Override public boolean revoke(SessionReference reference) {
            byToken.replaceAll((k,e)->e.session.reference().equals(reference)?new Entry(e.session,e.csrf,true):e);return true;
        }
        void check(){if(unavailable)throw new IllegalStateException("SESSION_UNAVAILABLE");}
    }
    @org.springframework.web.bind.annotation.RestController static final class Probe {
        final String[] seen;Probe(String[] seen){this.seen=seen;}
        @org.springframework.web.bind.annotation.GetMapping("/x") public String getClient(jakarta.servlet.http.HttpServletRequest r){seen[0]=(String)r.getAttribute(ApiGuardFilter.CLIENT_ATTRIBUTE);return "ok";}
    }
    Memory store;MockMvc mvc;SessionCookies cookies=SessionCookies.operational();
    @BeforeEach void setup() {
        store=new Memory();var access=new SessionAccess(store);
        mvc=MockMvcBuilders.standaloneSetup(new SessionController(access,cookies))
                .addFilters(new ApiGuardFilter(ORIGIN,access,cookies,new ClientAddress(List.of("10.0.0.2")))).build();
    }
    MockHttpServletRequestBuilder post(String path){return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Origin",ORIGIN);}
    record Booted(String cookie,String csrf) { }
    Booted boot() throws Exception {
        var result=mvc.perform(post("/api/v1/session/bootstrap")).andExpect(status().isOk()).andReturn().getResponse();
        String setCookie=result.getHeader("Set-Cookie");String token=setCookie.substring(setCookie.indexOf('=')+1,setCookie.indexOf(';'));
        String body=result.getContentAsString();String csrf=body.replaceAll(".*\"csrfToken\":\"([^\"]+)\".*","$1");
        assertTrue(TOKEN.matcher(token).matches() || token.matches("a+\\d*"));return new Booted(token,csrf);
    }
    static org.springframework.test.web.servlet.result.StatusResultMatchers status(){return org.springframework.test.web.servlet.result.MockMvcResultMatchers.status();}
    void problem(ResultActions actions,int status,String code) throws Exception {
        var response=actions.andExpect(status().is(status)).andReturn().getResponse();
        assertEquals("no-store",response.getHeader("Cache-Control"));assertTrue(response.getContentType().startsWith("application/problem+json"));
        String body=response.getContentAsString();
        assertTrue(body.contains("\"code\":\""+code+"\""),body);assertTrue(body.matches(".*\"correlationId\":\"[0-9a-f-]{36}\".*"));
        assertFalse(body.contains("Exception"));assertNull(response.getHeader("Set-Cookie"));
    }
    Cookie cookie(Booted b){return new Cookie(cookies.name(),b.cookie);}

    @Test void bootstrapIssuesHostCookieAndDigestOnlyCsrfProofWithNoStore() throws Exception {
        var response=mvc.perform(post("/api/v1/session/bootstrap")).andExpect(status().isOk()).andReturn().getResponse();
        assertEquals("no-store",response.getHeader("Cache-Control"));
        String header=response.getHeader("Set-Cookie");
        assertTrue(header.startsWith("__Host-purpuragenda="));
        for(String attribute:List.of("Path=/","Secure","HttpOnly","SameSite=Lax"))assertTrue(header.contains("; "+attribute),attribute);
        assertFalse(header.contains("Domain"));assertFalse(header.contains("Max-Age"));
        String body=response.getContentAsString();
        assertTrue(body.startsWith("{\"role\":\"ANONYMOUS\",\"idleExpiresAt\":\"2026-10-25T10:30:00Z\",\"absoluteExpiresAt\":\"2026-10-25T12:00:00Z\",\"csrfToken\":\""));
        assertFalse(body.contains("reference") || body.contains("browser"));
    }
    @Test void bootstrapWithLiveCookieRotatesOnlyCsrfAndReloadNeedsReissue() throws Exception {
        var first=boot();
        var again=mvc.perform(post("/api/v1/session/bootstrap").cookie(cookie(first))).andExpect(status().isOk()).andReturn().getResponse();
        assertNull(again.getHeader("Set-Cookie"));
        String rotated=again.getContentAsString().replaceAll(".*\"csrfToken\":\"([^\"]+)\".*","$1");
        assertNotEquals(first.csrf,rotated);
        problem(mvc.perform(post("/api/v1/session/logout").cookie(cookie(first)).header("X-CSRF-Token",first.csrf)),403,"CSRF_REJECTED");
        mvc.perform(post("/api/v1/session/logout").cookie(cookie(first)).header("X-CSRF-Token",rotated)).andExpect(status().isNoContent());
    }
    @Test void staleOrRevokedCookieGetsNewAnonymousAuthority() throws Exception {
        var first=boot();store.byToken.replaceAll((k,e)->new Memory.Entry(e.session,e.csrf,true));
        var second=mvc.perform(post("/api/v1/session/bootstrap").cookie(cookie(first))).andExpect(status().isOk()).andReturn().getResponse();
        assertNotNull(second.getHeader("Set-Cookie"));assertFalse(second.getHeader("Set-Cookie").contains(first.cookie));
    }
    @Test void currentSessionExposesRoleAndDeadlinesOnly() throws Exception {
        var booted=boot();
        var response=mvc.perform(get("/api/v1/session").cookie(cookie(booted))).andExpect(status().isOk()).andReturn().getResponse();
        assertEquals("{\"role\":\"ANONYMOUS\",\"idleExpiresAt\":\"2026-10-25T10:30:00Z\",\"absoluteExpiresAt\":\"2026-10-25T12:00:00Z\"}",response.getContentAsString());
        assertEquals("no-store",response.getHeader("Cache-Control"));
        problem(mvc.perform(get("/api/v1/session")),401,"SESSION_REQUIRED");
        problem(mvc.perform(get("/api/v1/session").cookie(new Cookie(cookies.name(),"z".repeat(43)))),401,"SESSION_REQUIRED");
    }
    @Test void logoutRequiresCsrfRevokesClearsCookieAndCannotBeReplayed() throws Exception {
        var booted=boot();
        problem(mvc.perform(post("/api/v1/session/logout").cookie(cookie(booted))),403,"CSRF_REJECTED");
        problem(mvc.perform(post("/api/v1/session/logout").cookie(cookie(booted)).header("X-CSRF-Token","x".repeat(43))),403,"CSRF_REJECTED");
        problem(mvc.perform(post("/api/v1/session/logout").cookie(cookie(booted)).header("X-CSRF-Token",booted.csrf).header("X-CSRF-Token",booted.csrf)),403,"CSRF_REJECTED");
        problem(mvc.perform(post("/api/v1/session/logout").cookie(cookie(booted)).header("X-CSRF-Token","short")),403,"CSRF_REJECTED");
        var out=mvc.perform(post("/api/v1/session/logout").cookie(cookie(booted)).header("X-CSRF-Token",booted.csrf)).andExpect(status().isNoContent()).andReturn().getResponse();
        assertEquals("no-store",out.getHeader("Cache-Control"));
        String cleared=out.getHeader("Set-Cookie");assertTrue(cleared.startsWith("__Host-purpuragenda=;"));assertTrue(cleared.contains("Max-Age=0"));
        assertTrue(cleared.contains("Secure") && cleared.contains("HttpOnly") && cleared.contains("SameSite=Lax"));
        problem(mvc.perform(post("/api/v1/session/logout").cookie(cookie(booted)).header("X-CSRF-Token",booted.csrf)),401,"SESSION_REQUIRED");
        problem(mvc.perform(get("/api/v1/session").cookie(cookie(booted))),401,"SESSION_REQUIRED");
    }
    @Test void unsafeRequestsNeedOneExactOriginBeforeAnythingElse() throws Exception {
        var booted=boot();
        for(String[] origins:new String[][]{{},{"null"},{ORIGIN+"/"},{"http://bookings.example.com"},{"https://bookings.example.com:8443"},{"https://BOOKINGS.example.com"},
                {"https://evil.example"},{ORIGIN,ORIGIN},{ORIGIN,"https://evil.example"},{""}}) {
            var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/session/logout").cookie(cookie(booted)).header("X-CSRF-Token",booted.csrf);
            for(String origin:origins)request.header("Origin",origin);
            problem(mvc.perform(request),403,"ORIGIN_REJECTED");
            var bootstrap=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/session/bootstrap");
            for(String origin:origins)bootstrap.header("Origin",origin);
            problem(mvc.perform(bootstrap),403,"ORIGIN_REJECTED");
        }
        assertEquals(1,store.byToken.size());
        problem(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/session/bootstrap").header("Forwarded","host=evil;proto=https")
                .header("X-Forwarded-Host","bookings.example.com").header("X-Forwarded-Proto","https")),403,"ORIGIN_REJECTED");
    }
    @Test void everyUnsafeMethodAndUnknownRouteIsGuarded() throws Exception {
        for(var method:List.of("PUT","PATCH","DELETE","OPTIONS","TRACE","POST"))
            problem(mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method),"/api/v1/anything").header("Origin","https://evil.example")),403,"ORIGIN_REJECTED");
        problem(mvc.perform(post("/api/v1/anything")),401,"SESSION_REQUIRED");
        problem(mvc.perform(post("/api/v1/session/bootstrap/")),401,"SESSION_REQUIRED");
        var booted=boot();
        problem(mvc.perform(post("/api/v1/session/bootstrap/").cookie(cookie(booted))),403,"CSRF_REJECTED");
        mvc.perform(get("/api/v1/session/bootstrap")).andExpect(status().isMethodNotAllowed());
    }
    @Test void headIsSafeButPostOnlyBootstrapExemptionDoesNotExtendToOtherMethods() throws Exception {
        mvc.perform(head("/api/v1/session")).andExpect(status().isUnauthorized());
        problem(mvc.perform(put("/api/v1/session/bootstrap").header("Origin",ORIGIN)),401,"SESSION_REQUIRED");
        problem(mvc.perform(delete("/api/v1/session/bootstrap").header("Origin",ORIGIN)),401,"SESSION_REQUIRED");
        assertTrue(store.byToken.isEmpty());
    }
    @Test void crossSiteFetchMetadataIsRejectedEvenWithCorrectOrigin() throws Exception {
        var booted=boot();
        for(String value:List.of("cross-site","same-site","none","","SAME-ORIGIN"))
            problem(mvc.perform(post("/api/v1/session/bootstrap").header("Sec-Fetch-Site",value)),403,"CROSS_SITE_REJECTED");
        problem(mvc.perform(post("/api/v1/session/logout").cookie(cookie(booted)).header("X-CSRF-Token",booted.csrf).header("Sec-Fetch-Site","cross-site")),403,"CROSS_SITE_REJECTED");
        problem(mvc.perform(post("/api/v1/session/bootstrap").header("Sec-Fetch-Site","same-origin").header("Sec-Fetch-Site","same-origin")),403,"CROSS_SITE_REJECTED");
        mvc.perform(post("/api/v1/session/bootstrap").header("Sec-Fetch-Site","same-origin")).andExpect(status().isOk());
    }
    @Test void missingMalformedOrRepeatedCookiesAreNoSession() throws Exception {
        var booted=boot();
        problem(mvc.perform(post("/api/v1/session/logout").header("X-CSRF-Token",booted.csrf)),401,"SESSION_REQUIRED");
        problem(mvc.perform(post("/api/v1/session/logout").cookie(new Cookie(cookies.name(),"bad value")).header("X-CSRF-Token",booted.csrf)),401,"SESSION_REQUIRED");
        problem(mvc.perform(post("/api/v1/session/logout").cookie(cookie(booted),cookie(booted)).header("X-CSRF-Token",booted.csrf)),401,"SESSION_REQUIRED");
        problem(mvc.perform(post("/api/v1/session/logout").cookie(new Cookie("purpuragenda-local",booted.cookie)).header("X-CSRF-Token",booted.csrf)),401,"SESSION_REQUIRED");
    }
    @Test void storeFailuresAreSafeRetryableProblemsWithoutCauseText() throws Exception {
        var booted=boot();store.unavailable=true;
        for(var request:List.of(post("/api/v1/session/bootstrap").cookie(cookie(booted)),post("/api/v1/session/logout").cookie(cookie(booted)).header("X-CSRF-Token",booted.csrf))) {
            var response=mvc.perform(request).andExpect(status().isServiceUnavailable()).andReturn().getResponse();
            assertTrue(response.getContentAsString().contains("\"code\":\"SESSION_UNAVAILABLE\",") && response.getContentAsString().contains("\"retryable\":true"));
            assertFalse(response.getContentAsString().contains("SESSION_UNAVAILABLE\"}"));
        }
        problem(mvc.perform(get("/api/v1/session").cookie(cookie(booted))),503,"SESSION_UNAVAILABLE");
    }
    @Test void controllerRejectsUnguardedLogoutDefensively() throws Exception {
        var direct=MockMvcBuilders.standaloneSetup(new SessionController(new SessionAccess(store),cookies)).build();
        problem(direct.perform(post("/api/v1/session/logout")),401,"SESSION_REQUIRED");
        store.unavailable=true;
        problem(direct.perform(post("/api/v1/session/bootstrap")),503,"SESSION_UNAVAILABLE");
    }
    @Test void logoutStoreFailureAfterGuardIsSafe() throws Exception {
        var failing=new SessionStore() {
            public Issued anonymous(){throw new AssertionError();}
            public Optional<CsrfRotation> rotateCsrf(char[] t){throw new AssertionError();}
            public Optional<Snapshot> resolve(char[] t){return store.resolve(t);}
            public boolean csrf(SessionReference r,char[] c){return true;}
            public Optional<Issued> exchangeAdmin(SessionReference p,String h,char[] t){throw new AssertionError();}
            public Optional<Issued> exchangeGuest(SessionReference p,GuestAccessStore.Redemption r){throw new AssertionError();}
            public boolean revoke(SessionReference r){throw new IllegalStateException("SESSION_UNAVAILABLE");}
        };
        var booted=boot();var access=new SessionAccess(failing);
        var local=MockMvcBuilders.standaloneSetup(new SessionController(access,cookies)).addFilters(new ApiGuardFilter(ORIGIN,access,cookies,new ClientAddress(List.of()))).build();
        problem(local.perform(post("/api/v1/session/logout").cookie(cookie(booted)).header("X-CSRF-Token",booted.csrf)),503,"SESSION_UNAVAILABLE");
    }
    @Test void localModeUsesDistinctInsecureCookieName() throws Exception {
        var local=SessionCookies.local();var access=new SessionAccess(store);
        var mvc=MockMvcBuilders.standaloneSetup(new SessionController(access,local)).addFilters(new ApiGuardFilter("http://localhost:8080",access,local,new ClientAddress(List.of()))).build();
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/session/bootstrap").header("Origin","http://localhost:8080")).andExpect(status().isOk()).andReturn().getResponse();
        String header=response.getHeader("Set-Cookie");
        assertTrue(header.startsWith("purpuragenda-local="));assertFalse(header.contains("Secure"));assertFalse(header.startsWith("__Host-"));
        assertTrue(header.contains("HttpOnly") && header.contains("SameSite=Lax") && header.contains("Path=/"));assertEquals("purpuragenda-local",local.name());
        assertNotEquals(local.name(),cookies.name());
    }
    @Test void clientAddressIsRecordedFromTrustedProxyOnly() throws Exception {
        var seen=new String[1];var access=new SessionAccess(store);
        var mvc=MockMvcBuilders.standaloneSetup(new Probe(seen))
                .addFilters(new ApiGuardFilter(ORIGIN,access,cookies,new ClientAddress(List.of("10.0.0.2")))).build();
        mvc.perform(get("/x").header(ClientAddress.HEADER,"203.0.113.9").with(r->{r.setRemoteAddr("10.0.0.9");return r;})).andExpect(status().isOk());
        assertEquals("10.0.0.9",seen[0]);
        mvc.perform(get("/x").header(ClientAddress.HEADER,"203.0.113.9").with(r->{r.setRemoteAddr("10.0.0.2");return r;})).andExpect(status().isOk());
        assertEquals("203.0.113.9",seen[0]);
    }
}
