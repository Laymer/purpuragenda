package com.purpuragenda.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SanitizerTest {
    private static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde";

    @Test
    void proofLinksLoseTheirTokenAndFragment() {
        String cleaned = Sanitizer.clean("Open http://localhost:18080/verify#token=" + TOKEN + "&purpose=CREATE now");
        assertFalse(cleaned.contains(TOKEN));
        assertFalse(cleaned.contains("purpose"));
        assertTrue(cleaned.contains("/verify#[redacted]"));
    }

    @Test
    void emailsPhonesCodesCookiesAndQueriesAreRedacted() {
        String cleaned = Sanitizer.clean("guest+abc@example.test called +32 470 00 00 01 code 123456 "
                + "Cookie: session=secretvalue; other=1\nX-CSRF-Token: csrfsecret\nGET /api?token=abc&x=1");
        assertFalse(cleaned.contains("guest+abc"));
        assertFalse(cleaned.contains("470"));
        assertFalse(cleaned.contains("123456"));
        assertFalse(cleaned.contains("secretvalue"));
        assertFalse(cleaned.contains("csrfsecret"));
        assertFalse(cleaned.contains("token=abc"));
        assertTrue(cleaned.contains("[redacted-email]") && cleaned.contains("[redacted-phone]") && cleaned.contains("[redacted-code]"));
    }

    @Test
    void ordinaryStatusTextSurvivesAndSeveralUncodedNumbersAreKept() {
        assertEquals("Status: pending confirmation. Awaiting confirmation (1)", Sanitizer.clean("Status: pending confirmation.\n  Awaiting confirmation (1)"));
        assertEquals("1234567 is seven digits", Sanitizer.clean("1234567 is seven digits"));
        assertEquals("", Sanitizer.clean(null));
    }

    @Test
    void pathsDropQueryFragmentAndMalformedInput() {
        assertEquals("/verify", Sanitizer.path("http://localhost:1/verify#token=" + TOKEN));
        assertEquals("/admin/bookings", Sanitizer.path("http://localhost:1/admin/bookings?x=1"));
        assertEquals("/", Sanitizer.path("http://localhost:1"));
        assertEquals("[unparseable-url]", Sanitizer.path("http://bad host/"));
        assertEquals("", Sanitizer.path(null));
    }

    @Test
    void evidenceKeepsStepAndMessagesButNoSecretsAndWritesOnlyValidNames(@TempDir Path directory) throws Exception {
        String content = Evidence.render("guest opens the proof link", "AssertionError: http://localhost:1/verify#token=" + TOKEN,
                "http://localhost:1/verify#token=" + TOKEN, List.of("Verify your email", "", "Sent to guest@example.test", "x".repeat(400)));
        assertTrue(content.contains("failed step: guest opens the proof link"));
        assertTrue(content.contains("page path: /verify"));
        assertTrue(content.contains("Verify your email"));
        assertFalse(content.contains(TOKEN) || content.contains("guest@example.test"));
        assertTrue(content.lines().allMatch(line -> line.length() < 330));
        Path file = Evidence.write(directory.resolve("nested"), "core-journey-failure.txt", content);
        assertEquals(content, Files.readString(file));
        assertThrows(IllegalArgumentException.class, () -> Evidence.write(directory, "../escape.txt", content));
        assertThrows(IllegalArgumentException.class, () -> Evidence.write(directory, "UPPER.txt", content));
    }
}
