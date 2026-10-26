package com.purpuragenda.application;

import com.purpuragenda.application.port.out.GuestAccessStore;
import com.purpuragenda.application.port.out.SessionStore;
import com.purpuragenda.application.port.out.SessionStore.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionAccessTest {
    static final Instant ISSUED=Instant.parse("2026-10-25T10:00:00Z");
    static Snapshot snapshot(Kind kind,Instant lastSeen,Instant absolute) {
        return new Snapshot(new SessionReference(UUID.randomUUID(),"a".repeat(64)),kind,null,null,null,null,null,ISSUED,lastSeen,absolute,null);
    }
    static final class Fake implements SessionStore {
        Snapshot live=snapshot(Kind.ANONYMOUS,ISSUED,ISSUED.plusSeconds(7200));
        boolean csrfMatches=true,revoked,unavailable;List<String> calls=new ArrayList<>();char[] lastBuffer;
        @Override public Issued anonymous() { calls.add("anonymous");return new Issued(live,"t".repeat(43).toCharArray(),"c".repeat(43).toCharArray()); }
        @Override public Optional<CsrfRotation> rotateCsrf(char[] token) {
            calls.add("rotate");lastBuffer=token;
            return token[0]=='L'?Optional.of(new CsrfRotation(live,"n".repeat(43).toCharArray())):Optional.empty();
        }
        @Override public Optional<Snapshot> resolve(char[] token) { calls.add("resolve");if(unavailable)throw new IllegalStateException("SESSION_UNAVAILABLE");return token[0]=='L'?Optional.of(live):Optional.empty(); }
        @Override public Optional<Snapshot> inspect(char[] token) { calls.add("inspect");return token[0]=='L'?Optional.of(live):Optional.empty(); }
        @Override public boolean csrf(SessionReference session,char[] csrf) { calls.add("csrf");lastBuffer=csrf;return csrfMatches; }
        @Override public Optional<Issued> exchangeAdmin(SessionReference previous,String requestHash,char[] proofToken) { throw new AssertionError(); }
        Optional<Issued> guestResult=Optional.empty();SessionReference exchanged;
        @Override public Optional<Issued> exchangeGuest(SessionReference previous,GuestAccessStore.Redemption proof) { calls.add("exchangeGuest");exchanged=previous;return guestResult; }
        @Override public boolean revoke(SessionReference session) { calls.add("revoke");revoked=true;return true; }
    }
    @Test void bootstrapWithoutLiveCookieIssuesAnonymousAuthorityAndBothSecrets() {
        var store=new Fake();var access=new SessionAccess(store);
        for(char[] cookie:new char[][]{null,"X".repeat(43).toCharArray()}) try(var started=access.bootstrap(cookie)) {
            assertEquals("t".repeat(43),new String(started.cookie()));assertEquals("c".repeat(43),new String(started.csrf()));
            assertEquals(Kind.ANONYMOUS,started.view().role());assertEquals("StartedSession[REDACTED]",started.toString());
        }
        assertEquals(List.of("anonymous","rotate","anonymous"),store.calls);
    }
    @Test void bootstrapWithLiveCookieRotatesOnlyCsrfAndSetsNoCookie() {
        var store=new Fake();var access=new SessionAccess(store);
        try(var started=access.bootstrap("L".repeat(43).toCharArray())) {
            assertNull(started.cookie());assertEquals("n".repeat(43),new String(started.csrf()));
        }
        assertEquals(List.of("rotate"),store.calls);
    }
    @Test void startedCopiesAndErasesSecrets() {
        char[] cookie="a".toCharArray(),csrf="b".toCharArray();
        var started=new SessionAccess.Started(SessionAccess.view(snapshot(Kind.GUEST,ISSUED,ISSUED.plusSeconds(7200))),cookie,csrf);
        cookie[0]='x';csrf[0]='y';assertEquals('a',started.cookie()[0]);assertEquals('b',started.csrf()[0]);
        started.cookie()[0]='z';assertEquals('a',started.cookie()[0]);started.close();assertEquals('\0',started.csrf()[0]);
    }
    @Test void idleDeadlineUsesRoleLimitAndIsCappedByAbsoluteLifetime() {
        var seen=ISSUED.plusSeconds(60);
        assertEquals(seen.plusSeconds(1800),SessionAccess.view(snapshot(Kind.ACCOUNT,seen,ISSUED.plusSeconds(43200))).idleExpiresAt());
        assertEquals(seen.plusSeconds(900),SessionAccess.view(snapshot(Kind.ADMIN,seen,ISSUED.plusSeconds(28800))).idleExpiresAt());
        var nearEnd=snapshot(Kind.GUEST,ISSUED.plusSeconds(7000),ISSUED.plusSeconds(7200));
        var view=SessionAccess.view(nearEnd);assertEquals(ISSUED.plusSeconds(7200),view.idleExpiresAt());assertEquals(view.absoluteExpiresAt(),view.idleExpiresAt());
        var exact=snapshot(Kind.GUEST,ISSUED.plusSeconds(5400),ISSUED.plusSeconds(7200));assertEquals(ISSUED.plusSeconds(7200),SessionAccess.view(exact).idleExpiresAt());
        assertThrows(NullPointerException.class,()->new SessionAccess.View(null,seen,seen));
        assertThrows(NullPointerException.class,()->new SessionAccess(null));
    }
    @Test void currentAndCsrfDelegateAndAbsentCookieNeverTouchesStore() {
        var store=new Fake();var access=new SessionAccess(store);
        assertTrue(access.current(null).isEmpty());assertTrue(access.inspect(null).isEmpty());assertTrue(store.calls.isEmpty());
        assertTrue(access.inspect("X".repeat(43).toCharArray()).isEmpty());assertEquals(store.live,access.inspect("L".repeat(43).toCharArray()).orElseThrow());
        assertEquals(List.of("inspect","inspect"),store.calls);store.calls.clear();
        assertTrue(access.current("X".repeat(43).toCharArray()).isEmpty());
        var session=access.current("L".repeat(43).toCharArray()).orElseThrow();
        assertTrue(access.csrf(session,"c".toCharArray()));store.csrfMatches=false;assertFalse(access.csrf(session,"c".toCharArray()));
        assertThrows(IllegalStateException.class,()->{store.unavailable=true;access.current("L".repeat(43).toCharArray());});
    }
    @Test void logoutRevokesOnlyTheAuthorizedSessionReference() {
        var store=new Fake();var access=new SessionAccess(store);
        assertTrue(access.logout(store.live));assertTrue(store.revoked);assertEquals(List.of("revoke"),store.calls);
    }
    @Test void guestRedemptionReturnsRotatedSecretsAndRolesOrNothing() {
        var store=new Fake();var access=new SessionAccess(store);
        var proof=new GuestAccessStore.Redemption("p".repeat(43),GuestAccessStore.Kind.CREATE,"a".repeat(64));
        assertTrue(access.redeemGuest(store.live,proof).isEmpty());
        var guest=snapshot(Kind.GUEST,ISSUED,ISSUED.plusSeconds(7200));
        store.guestResult=Optional.of(new Issued(guest,"g".repeat(43).toCharArray(),"h".repeat(43).toCharArray()));
        try(var started=access.redeemGuest(store.live,proof).orElseThrow()) {
            assertEquals(Kind.GUEST,started.view().role());assertEquals("g".repeat(43),new String(started.cookie()));assertEquals("h".repeat(43),new String(started.csrf()));
        }
        assertEquals(store.live.reference(),store.exchanged);
    }
}
