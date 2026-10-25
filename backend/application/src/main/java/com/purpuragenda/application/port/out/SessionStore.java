package com.purpuragenda.application.port.out;

import java.time.Instant;
import java.util.*;

/** Persisted sessions only; HTTP must supply same-origin CSRF/throttle protections separately. */
public interface SessionStore {
    enum Kind { ANONYMOUS, GUEST, ACCOUNT, ADMIN }
    record SessionReference(UUID id,String browserHash) {
        public SessionReference { Objects.requireNonNull(id);digest(browserHash); }
        @Override public String toString() { return "SessionReference[REDACTED]"; }
    }
    record ProofBinding(String browserHash,String requestHash) {
        public ProofBinding { digest(browserHash);digest(requestHash); }
        @Override public String toString() { return "ProofBinding[REDACTED]"; }
    }
    record Snapshot(SessionReference reference,Kind kind,UUID grantId,UUID customerId,UUID accountId,
                    UUID adminId,Long adminSecurityVersion,Instant issuedAt,Instant lastSeenAt,
                    Instant absoluteExpiresAt,Instant authenticatedAt) {
        @Override public String toString() { return "SessionSnapshot[REDACTED]"; }
    }
    record Issued(Snapshot session,char[] token,char[] csrf) implements AutoCloseable {
        public Issued { token=token.clone();csrf=csrf.clone(); }
        @Override public char[] token() { return token.clone(); }
        @Override public char[] csrf() { return csrf.clone(); }
        @Override public void close() { Arrays.fill(token,'\0');Arrays.fill(csrf,'\0'); }
        @Override public String toString() { return "IssuedSession[REDACTED]"; }
    }
    /** Replacement CSRF proof for a current live session; the bearer cookie and browser binding are unchanged. */
    record CsrfRotation(Snapshot session,char[] csrf) implements AutoCloseable {
        public CsrfRotation { csrf=csrf.clone(); }
        @Override public char[] csrf() { return csrf.clone(); }
        @Override public void close() { Arrays.fill(csrf,'\0'); }
        @Override public String toString() { return "CsrfRotation[REDACTED]"; }
    }
    Issued anonymous();
    /** Consumes the cookie buffer. Locks only the session row; no grant or administrator row is locked. */
    Optional<CsrfRotation> rotateCsrf(char[] token);
    /** Consumes the caller's cookie buffer; lookup and idle touch share one captured DB time. */
    Optional<Snapshot> resolve(char[] token);
    /** Read-only status lookup: never writes, touches, extends idle time or locks any row. Consumes the cookie buffer. */
    Optional<Snapshot> inspect(char[] token);
    /** Verifies both current persisted session and current CSRF digest. Input CSRF buffer is consumed. */
    boolean csrf(SessionReference session,char[] csrf);
    Optional<Issued> exchangeAdmin(SessionReference previous,String requestHash,char[] proofToken);
    Optional<Issued> exchangeGuest(SessionReference previous,GuestAccessStore.Redemption proof);
    boolean revoke(SessionReference session);
    private static void digest(String value) {
        if(value==null || !value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("INVALID_SESSION_BINDING");
    }
}
