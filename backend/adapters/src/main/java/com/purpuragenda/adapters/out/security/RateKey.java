package com.purpuragenda.adapters.out.security;

import com.purpuragenda.application.port.out.RateLimitStore.Kind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Versioned 32-byte HMAC key for throttle identifiers. It is separate from the TOTP and delivery
 * encryption keys, so a stored bucket cannot be linked to an address or network without this secret.
 */
public final class RateKey {
    private final SecretKeySpec key;
    private final String keyId;

    public RateKey(Path file, String keyId) {
        if (keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,64}")) throw failure("INVALID_KEY_ID");
        this.keyId = keyId;
        byte[] bytes = null;
        try (var input = Files.newInputStream(Objects.requireNonNull(file), LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(33);
            if (bytes.length != 32) throw failure("KEY_MUST_BE_32_BYTES");
            key = new SecretKeySpec(bytes, "HmacSHA256");
        } catch (IOException | NullPointerException e) {
            throw failure("KEY_FILE_UNAVAILABLE");
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    public String keyId() { return keyId; }

    /** Scoped, domain-separated digest used as the bucket primary key. */
    String digest(Kind kind, String subject) {
        Objects.requireNonNull(kind);
        if (subject == null || subject.isEmpty()) throw failure("INVALID_THROTTLE_SUBJECT");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update("purpuragenda/rate/v1".getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) 0);
            mac.update(kind.name().getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) 0);
            return HexFormat.of().formatHex(mac.doFinal(subject.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw failure("RATE_DIGEST_UNAVAILABLE"); }
    }

    @Override public String toString() { return "RateKey[REDACTED]"; }

    private static IllegalStateException failure(String code) { return new IllegalStateException(code); }
}
