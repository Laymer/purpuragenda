package com.purpuragenda.application;

import com.purpuragenda.application.port.out.GuestAccessStore;
import com.purpuragenda.application.port.out.SessionStore;
import com.purpuragenda.application.port.out.SessionStore.*;
import java.time.Instant;
import java.util.*;

/**
 * Cookie-neutral session use cases for the HTTP edge. Every method consumes the borrowed secret
 * buffers it receives. Bootstrap, status and logout acquire no grant or administrator locks;
 * authority handoffs deliberately delegate to their transaction-bound exchange ports.
 */
public final class SessionAccess {
    private final SessionStore sessions;
    public SessionAccess(SessionStore sessions) { this.sessions=Objects.requireNonNull(sessions); }

    /** Safe browser-visible state: role and deadlines only, never identifiers or grants. */
    public record View(Kind role,Instant idleExpiresAt,Instant absoluteExpiresAt) {
        public View { Objects.requireNonNull(role);Objects.requireNonNull(idleExpiresAt);Objects.requireNonNull(absoluteExpiresAt); }
    }
    /** {@code cookie} is non-null only when a new bearer cookie must be set. */
    public record Started(View view,char[] cookie,char[] csrf) implements AutoCloseable {
        public Started { cookie=cookie==null?null:cookie.clone();csrf=csrf.clone(); }
        @Override public char[] cookie() { return cookie==null?null:cookie.clone(); }
        @Override public char[] csrf() { return csrf.clone(); }
        @Override public void close() { if(cookie!=null)Arrays.fill(cookie,'\0');Arrays.fill(csrf,'\0'); }
        @Override public String toString() { return "StartedSession[REDACTED]"; }
    }

    public static View view(Snapshot session) {
        long idle=session.kind()==Kind.ADMIN?900:1800;
        Instant idleEnd=session.lastSeenAt().plusSeconds(idle);
        return new View(session.kind(),idleEnd.isAfter(session.absoluteExpiresAt())?session.absoluteExpiresAt():idleEnd,session.absoluteExpiresAt());
    }
    /** A live cookie only rotates its CSRF proof; anything else gets a fresh anonymous guest authority. */
    public Started bootstrap(char[] cookie) {
        if(cookie!=null) {
            var rotated=sessions.rotateCsrf(cookie);
            if(rotated.isPresent()) try(var value=rotated.get()) { return new Started(view(value.session()),null,value.csrf()); }
        }
        try(var issued=sessions.anonymous()) { return new Started(view(issued.session()),issued.token(),issued.csrf()); }
    }
    /** Status view without touching the session, so polling cannot extend idle lifetime. */
    public Optional<Snapshot> inspect(char[] cookie) { return cookie==null?Optional.empty():sessions.inspect(cookie); }
    public Optional<Snapshot> current(char[] cookie) { return cookie==null?Optional.empty():sessions.resolve(cookie); }
    /** Verifies the current CSRF proof of a session resolved in this request; consumes the borrowed buffer. */
    public boolean csrf(Snapshot session,char[] csrf) { return sessions.csrf(session.reference(),csrf); }
    /**
     * Atomically exchanges an email proof for a rotated guest session bound to the same browser. Empty for any
     * unknown, replayed, expired, mis-bound or revoked proof; the caller cannot tell which.
     */
    public Optional<Started> redeemGuest(Snapshot previous,GuestAccessStore.Redemption proof) {
        return sessions.exchangeGuest(previous.reference(),proof).map(issued -> {
            try(issued) { return new Started(view(issued.session()),issued.token(),issued.csrf()); }
        });
    }
    public boolean logout(Snapshot authorized) { return sessions.revoke(authorized.reference()); }
}
