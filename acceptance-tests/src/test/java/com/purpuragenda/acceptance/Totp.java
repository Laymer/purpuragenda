package com.purpuragenda.acceptance;

import java.nio.ByteBuffer;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 six-digit HMAC-SHA1 codes, computed from the authenticator key the private enrollment printed. */
final class Totp {
    static final int STEP_SECONDS = 30;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {}

    static long step(Instant moment) {
        return Math.floorDiv(moment.getEpochSecond(), (long) STEP_SECONDS);
    }

    /** Seconds until the next time step begins (1..30). */
    static long secondsToNextStep(Instant moment) {
        return STEP_SECONDS - Math.floorMod(moment.getEpochSecond(), (long) STEP_SECONDS);
    }

    static String code(String base32Key, Instant moment) {
        return code(base32Key, step(moment));
    }

    static String code(String base32Key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decode(base32Key), "HmacSHA1"));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = digest[digest.length - 1] & 0x0f;
            int binary = ((digest[offset] & 0x7f) << 24) | ((digest[offset + 1] & 0xff) << 16)
                    | ((digest[offset + 2] & 0xff) << 8) | (digest[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA1 is unavailable", impossible);
        }
    }

    /** Unpadded or padded RFC 4648 base32; whitespace is ignored, anything else is rejected. */
    static byte[] decode(String text) {
        String clean = text.replaceAll("[\\s=]", "").toUpperCase(java.util.Locale.ROOT);
        if (clean.isEmpty()) throw new IllegalArgumentException("EMPTY_TOTP_KEY");
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0, bits = 0;
        for (char c : clean.toCharArray()) {
            int value = ALPHABET.indexOf(c);
            if (value < 0) throw new IllegalArgumentException("INVALID_TOTP_KEY");
            buffer = ((buffer << 5) | value) & 0xfff;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out.put((byte) ((buffer >> bits) & 0xff));
            }
        }
        return java.util.Arrays.copyOf(out.array(), out.position());
    }
}
