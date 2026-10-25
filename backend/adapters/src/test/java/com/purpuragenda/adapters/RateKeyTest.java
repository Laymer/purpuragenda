package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.security.RateKey;
import com.purpuragenda.application.port.out.RateLimitStore.Kind;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RateKeyTest {
    @TempDir Path directory;

    Path key(String name, int fill) throws Exception {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return Files.write(directory.resolve(name), bytes);
    }
    static String digest(RateKey key, Kind kind, String subject) throws Exception {
        Method method = RateKey.class.getDeclaredMethod("digest", Kind.class, String.class);
        method.setAccessible(true);
        try { return (String) method.invoke(key, kind, subject); }
        catch (java.lang.reflect.InvocationTargetException failure) { throw (Exception) failure.getCause(); }
    }

    @Test void digestsAreScopedKeyedStableAndHideTheSubject() throws Exception {
        var first = new RateKey(key("a", 7), "rate-v1");
        var second = new RateKey(key("b", 8), "rate-v1");
        String value = digest(first, Kind.EMAIL_ADDRESS, "synthetic@example.test");
        assertTrue(value.matches("[a-f0-9]{64}"));
        assertEquals(value, digest(first, Kind.EMAIL_ADDRESS, "synthetic@example.test"));
        assertNotEquals(value, digest(first, Kind.EMAIL_COOLDOWN, "synthetic@example.test"));
        assertNotEquals(value, digest(first, Kind.EMAIL_ADDRESS, "synthetic2@example.test"));
        assertNotEquals(value, digest(second, Kind.EMAIL_ADDRESS, "synthetic@example.test"));
        assertFalse(value.contains("synthetic"));
        assertEquals("rate-v1", first.keyId());
        assertEquals("RateKey[REDACTED]", first.toString());
    }

    @Test void keyMustBeExactlyThirtyTwoBytesUnlinkedAndVersioned() throws Exception {
        Path good = key("good", 1);
        for (String id : new String[]{null, "", "bad id", "x".repeat(65)})
            assertEquals("INVALID_KEY_ID", assertThrows(IllegalStateException.class, () -> new RateKey(good, id)).getMessage());
        Files.write(directory.resolve("short"), new byte[31]);
        Files.write(directory.resolve("long"), new byte[33]);
        for (Path invalid : new Path[]{directory.resolve("short"), directory.resolve("long")})
            assertEquals("KEY_MUST_BE_32_BYTES", assertThrows(IllegalStateException.class, () -> new RateKey(invalid, "rate-v1")).getMessage());
        Files.createSymbolicLink(directory.resolve("link"), good);
        for (Path unavailable : new Path[]{directory.resolve("missing"), directory.resolve("link"), directory, null}) {
            var failure = assertThrows(IllegalStateException.class, () -> new RateKey(unavailable, "rate-v1"));
            assertEquals("KEY_FILE_UNAVAILABLE", failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    @Test void emptyOrMissingSubjectsAreRejected() throws Exception {
        var rate = new RateKey(key("a", 7), "rate-v1");
        assertThrows(IllegalStateException.class, () -> digest(rate, Kind.EMAIL_IP, ""));
        assertThrows(IllegalStateException.class, () -> digest(rate, Kind.EMAIL_IP, null));
        assertThrows(NullPointerException.class, () -> digest(rate, null, "x"));
    }
}
