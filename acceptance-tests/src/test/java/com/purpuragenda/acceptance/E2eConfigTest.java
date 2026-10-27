package com.purpuragenda.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class E2eConfigTest {
    private static Map<String, String> valid() {
        Map<String, String> environment = new HashMap<>();
        environment.put("E2E_BASE_URL", "http://localhost:18080");
        environment.put("E2E_MAIL_URL", "http://127.0.0.1:18025");
        environment.put("E2E_ADMIN_EMAIL", "operator@example.test");
        environment.put("E2E_ADMIN_PASSWORD", "synthetic password value");
        environment.put("E2E_ADMIN_TOTP_KEY", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        environment.put("E2E_RUN_ID", "0123456789ab");
        return environment;
    }

    @Test
    void acceptsTheIsolatedLoopbackStackAndNormalizesOrigins() {
        E2eConfig config = E2eConfig.from(valid());
        assertEquals("http://localhost:18080", config.baseUrl().toString());
        assertEquals("http://127.0.0.1:18025", config.mailUrl().toString());
        assertEquals(Path.of("target/e2e-evidence"), config.evidenceDirectory());
        Map<String, String> trailing = valid();
        trailing.put("E2E_BASE_URL", "http://LOCALHOST:18080/");
        trailing.put("E2E_EVIDENCE_DIR", "somewhere");
        assertEquals("http://localhost:18080", E2eConfig.from(trailing).baseUrl().toString());
        assertEquals(Path.of("somewhere"), E2eConfig.from(trailing).evidenceDirectory());
    }

    @Test
    void refusesAnyNonLocalOrUnexpectedTarget() {
        for (String bad : new String[] {"https://localhost:18080", "http://example.test:18080", "http://127.0.0.1:18080",
                "http://localhost", "http://user@localhost:18080", "http://localhost:18080/path", "http://localhost:18080/?x=1", "not a url"}) {
            Map<String, String> environment = valid();
            environment.put("E2E_BASE_URL", bad);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> E2eConfig.from(environment), bad);
            assertEquals("E2E_TARGET_NOT_LOCAL", failure.getMessage());
        }
        Map<String, String> mail = valid();
        mail.put("E2E_MAIL_URL", "http://mail.example.test:8025");
        assertThrows(IllegalArgumentException.class, () -> E2eConfig.from(mail));
    }

    @Test
    void refusesMissingOrUnsafeCredentialsAndNeverPrintsThem() {
        for (String name : new String[] {"E2E_BASE_URL", "E2E_MAIL_URL", "E2E_ADMIN_EMAIL", "E2E_ADMIN_PASSWORD", "E2E_ADMIN_TOTP_KEY", "E2E_RUN_ID"}) {
            Map<String, String> environment = valid();
            environment.remove(name);
            assertThrows(IllegalArgumentException.class, () -> E2eConfig.from(environment), name);
            environment.put(name, "  ");
            assertThrows(IllegalArgumentException.class, () -> E2eConfig.from(environment), name);
        }
        String[][] invalid = {{"E2E_ADMIN_EMAIL", "operator@example.com"}, {"E2E_ADMIN_PASSWORD", "short"},
                {"E2E_ADMIN_TOTP_KEY", "abc"}, {"E2E_RUN_ID", "UPPER"}};
        for (String[] pair : invalid) {
            Map<String, String> environment = valid();
            environment.put(pair[0], pair[1]);
            assertThrows(IllegalArgumentException.class, () -> E2eConfig.from(environment), pair[0]);
        }
        String printed = E2eConfig.from(valid()).toString();
        assertFalse(printed.contains("synthetic password value") || printed.contains("GEZDGNBV") || printed.contains("operator@"));
    }
}
