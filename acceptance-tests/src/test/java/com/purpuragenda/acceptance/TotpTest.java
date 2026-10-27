package com.purpuragenda.acceptance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpTest {
    /** RFC 6238 appendix B secret "12345678901234567890" as base32. */
    private static final String RFC_KEY = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void matchesRfc6238Sha1VectorsTruncatedToSixDigits() {
        assertEquals("287082", Totp.code(RFC_KEY, Instant.ofEpochSecond(59L)));
        assertEquals("081804", Totp.code(RFC_KEY, Instant.ofEpochSecond(1_111_111_109L)));
        assertEquals("050471", Totp.code(RFC_KEY, Instant.ofEpochSecond(1_111_111_111L)));
        assertEquals("005924", Totp.code(RFC_KEY, Instant.ofEpochSecond(1_234_567_890L)));
        assertEquals("279037", Totp.code(RFC_KEY, Instant.ofEpochSecond(2_000_000_000L)));
        assertEquals("353130", Totp.code(RFC_KEY, Instant.ofEpochSecond(20_000_000_000L)));
    }

    @Test
    void stepsAdvanceExactlyAtThirtySecondBoundaries() {
        assertEquals(1, Totp.step(Instant.ofEpochSecond(59)));
        assertEquals(2, Totp.step(Instant.ofEpochSecond(60)));
        assertEquals(30, Totp.secondsToNextStep(Instant.ofEpochSecond(60)));
        assertEquals(1, Totp.secondsToNextStep(Instant.ofEpochSecond(89)));
        assertEquals(Totp.code(RFC_KEY, 1), Totp.code(RFC_KEY, Instant.ofEpochSecond(59)));
        assertNotEquals(Totp.code(RFC_KEY, 1), Totp.code(RFC_KEY, 2));
    }

    @Test
    void decodesPaddedLowercaseAndSpacedKeysIdentically() {
        byte[] expected = "12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertArrayEquals(expected, Totp.decode(RFC_KEY));
        assertArrayEquals(expected, Totp.decode(RFC_KEY.toLowerCase(java.util.Locale.ROOT)));
        assertArrayEquals(expected, Totp.decode("GEZD GNBV GY3T QOJQ GEZD GNBV GY3T QOJQ"));
        assertArrayEquals("foobar".getBytes(java.nio.charset.StandardCharsets.US_ASCII), Totp.decode("MZXW6YTBOI======"));
        assertArrayEquals("fooba".getBytes(java.nio.charset.StandardCharsets.US_ASCII), Totp.decode("MZXW6YTB"));
    }

    @Test
    void rejectsEmptyOrNonBase32Keys() {
        assertThrows(IllegalArgumentException.class, () -> Totp.decode(""));
        assertThrows(IllegalArgumentException.class, () -> Totp.decode("===="));
        assertThrows(IllegalArgumentException.class, () -> Totp.decode("GEZD1NBV"));
        assertThrows(IllegalArgumentException.class, () -> Totp.code("not base32!", 1));
    }
}
