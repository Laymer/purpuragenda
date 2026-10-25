package com.purpuragenda.application;

import java.time.*;
import java.util.*;
import static com.purpuragenda.domain.DomainFailure.require;

/** Metadata eligibility only: never a persisted authorization proof or session capability. */
public final class ReenrollmentGrantPolicy {
    private ReenrollmentGrantPolicy() { }
    public static final Duration LIFETIME=Duration.ofMinutes(10);
    public enum Purpose { ADMIN_REENROLLMENT, ADMIN_AUTHENTICATION }
    /** Digests of server-owned browser binding and exact recovery request, never raw identifiers. */
    public record Binding(byte[] browserHash,byte[] requestHash) {
        public Binding {
            require(browserHash!=null && browserHash.length==32 && requestHash!=null && requestHash.length==32,"INVALID_REENROLLMENT_BINDING");
            browserHash=browserHash.clone();requestHash=requestHash.clone();
        }
        @Override public byte[] browserHash() { return browserHash.clone(); }
        @Override public byte[] requestHash() { return requestHash.clone(); }
        public boolean matches(Binding other) {
            return other!=null && Arrays.equals(browserHash,other.browserHash) && Arrays.equals(requestHash,other.requestHash);
        }
        @Override public String toString() { return "ReenrollmentBinding[REDACTED]"; }
    }
    public record State(UUID id,UUID adminId,Purpose purpose,Binding binding,byte[] tokenHash,
                        Instant issuedAt,Instant expiresAt,Instant consumedAt,Instant revokedAt) {
        public State {
            require(id!=null && adminId!=null && purpose==Purpose.ADMIN_REENROLLMENT && binding!=null
                    && tokenHash!=null && tokenHash.length==32 && lifetime(issuedAt,expiresAt),"INVALID_REENROLLMENT_GRANT");
            require((consumedAt==null || !consumedAt.isBefore(issuedAt)) && (revokedAt==null || !revokedAt.isBefore(issuedAt)),"INVALID_REENROLLMENT_GRANT");
            tokenHash=tokenHash.clone();
        }
        @Override public byte[] tokenHash() { return tokenHash.clone(); }
        /** Caller must separately load/verify the bearer proof and current state atomically. */
        public boolean eligible(UUID expectedAdmin,Binding expectedBinding,Purpose requested,Instant now,boolean pendingReenrollment) {
            return pendingReenrollment && requested==Purpose.ADMIN_REENROLLMENT && adminId.equals(expectedAdmin)
                    && binding.matches(expectedBinding) && now!=null && !now.isBefore(issuedAt) && now.isBefore(expiresAt)
                    && consumedAt==null && revokedAt==null;
        }
        @Override public String toString() { return "ReenrollmentGrantState[REDACTED]"; }
    }
    private static boolean lifetime(Instant issued,Instant expires) {
        try { return issued!=null && expires!=null && expires.equals(issued.plus(LIFETIME)); }
        catch(DateTimeException | ArithmeticException invalid) { return false; }
    }
}
