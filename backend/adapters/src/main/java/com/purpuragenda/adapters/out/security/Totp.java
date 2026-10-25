package com.purpuragenda.adapters.out.security;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.spec.SecretKeySpec;

/** Pure verification; P16 must atomically persist the accepted step to prevent replay. */
public final class Totp {
    private static final SecureRandom RANDOM = new SecureRandom();
    private Totp() { }

    public static byte[] newSecret() {
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        return secret;
    }

    public static long acceptedStep(byte[] secret, String code, Instant now, long lastAcceptedStep) {
        if (secret == null || secret.length != 20 || code == null || !code.matches("[0-9]{6}")
                || now == null || now.getEpochSecond() < 0
                // java-otp represents each candidate using epoch milliseconds.
                || now.getEpochSecond() / 30 >= Long.MAX_VALUE / 1000 / 30
                || lastAcceptedStep < -1) return -1;
        try {
            var generator = new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30), 6, "HmacSHA1");
            var key = new SecretKeySpec(secret, "HmacSHA1");
            long current = now.getEpochSecond() / 30;
            long accepted = -1;
            for (long step = Math.max(0, current - 1); step <= current + 1; step++) {
                String expected = String.format(Locale.ROOT, "%06d",
                        generator.generateOneTimePassword(key, Instant.ofEpochSecond(step * 30)));
                boolean matches = MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                        code.getBytes(StandardCharsets.US_ASCII));
                if (matches && step > lastAcceptedStep) accepted = step;
            }
            return accepted;
        } catch (GeneralSecurityException e) { throw new IllegalStateException("TOTP_UNAVAILABLE"); }
    }
}
