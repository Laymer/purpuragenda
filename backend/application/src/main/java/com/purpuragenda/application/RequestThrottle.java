package com.purpuragenda.application;

import com.purpuragenda.application.port.out.RateLimitStore;
import com.purpuragenda.application.port.out.RateLimitStore.*;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Applies the approved request limits. Raw addresses and email never leave this boundary except as keyed-digest input. */
public final class RequestThrottle {
    /** A caller must report the outcome of a reserved administrator attempt exactly once. */
    public static final class AdminAttempt {
        private final RateLimitStore store;
        private final Reservation reservation;
        private boolean finished;
        AdminAttempt(RateLimitStore store, Reservation reservation) { this.store = store; this.reservation = reservation; }
        /** Successful authentication returns the slot only when the same fixed window is still current. */
        public synchronized boolean succeeded() {
            if (finished) return false;
            finished = true;
            return store.release(reservation);
        }
        /** A failed attempt keeps its reserved slot. */
        public synchronized void failed() { finished = true; }
        @Override public String toString() { return "AdminAttempt[REDACTED]"; }
    }

    /** Admission, and the whole-second Retry-After when denied. Reservation is present only for administrator attempts. */
    public record Admission(boolean allowed, long retryAfterSeconds, AdminAttempt attempt) {
        @Override public String toString() { return "Admission[allowed=" + allowed + "]"; }
    }

    private final RateLimitStore store;

    public RequestThrottle(RateLimitStore store) { this.store = Objects.requireNonNull(store); }

    /** 60 second cooldown, five per hour per address and thirty per hour per client network. */
    public Admission emailIssuance(String email, String clientIp) {
        String address = email(email), network = network(clientIp);
        return admission(List.of(new Charge(Kind.EMAIL_COOLDOWN, address), new Charge(Kind.EMAIL_ADDRESS, address),
                new Charge(Kind.EMAIL_IP, network)), false);
    }

    public Admission availability(String clientIp) {
        return admission(List.of(new Charge(Kind.AVAILABILITY_IP, network(clientIp))), false);
    }

    /** Reserve before any password/MFA work so concurrent guesses cannot exceed five. */
    public Admission adminAttempt(String account, String clientIp) {
        return admission(List.of(new Charge(Kind.ADMIN_ATTEMPT, email(account) + "\n" + network(clientIp))), true);
    }

    private Admission admission(List<Charge> charges, boolean reserved) {
        Decision decision = store.admit(charges);
        if (!decision.allowed()) return new Admission(false, decision.retryAfterSeconds(), null);
        return new Admission(true, 0, reserved ? new AdminAttempt(store, decision.reservations().getFirst()) : null);
    }

    static String email(String value) {
        if (value == null) throw invalid();
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        int at = normalized.indexOf('@');
        if (normalized.length() > 254 || at < 1 || at != normalized.lastIndexOf('@') || at == normalized.length() - 1
                || normalized.chars().anyMatch(c -> c <= 32 || c == 127)) throw invalid();
        return normalized;
    }

    /** IPv4 is exact. IPv6 shares one bucket per /64 so a client cannot rotate through its own prefix. */
    static String network(String value) {
        if (value == null || value.isEmpty() || value.length() > 45 || value.indexOf('%') >= 0) throw invalid();
        // Only canonical dotted-quad IPv4 or colon-form IPv6: no legacy short, octal or hex spellings.
        if (value.indexOf(':') < 0 && !value.matches("(0|[1-9]\\d{0,2})(\\.(0|[1-9]\\d{0,2})){3}")) throw invalid();
        try {
            byte[] bytes = InetAddress.ofLiteral(value).getAddress();
            List<String> parts = new ArrayList<>();
            if (bytes.length == 4) { for (byte b : bytes) parts.add(Integer.toString(b & 255)); return String.join(".", parts) + "/32"; }
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < 8; i++) prefix.append(String.format("%02x", bytes[i] & 255));
            return prefix + "/64";
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("INVALID_THROTTLE_SUBJECT"); }
}
