package com.purpuragenda.acceptance;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Settings handed over by scripts/ci/verify-e2e.py in the environment. The suite refuses any target
 * that is not the local loopback stack and never prints the administrator credential or key.
 */
record E2eConfig(URI baseUrl, URI mailUrl, String adminEmail, String adminPassword, String adminTotpKey,
                 Path evidenceDirectory, String runId) {
    private static final Pattern RUN_ID = Pattern.compile("[a-f0-9]{8,32}");
    private static final Pattern KEY = Pattern.compile("[A-Z2-7]{16,128}");

    static E2eConfig from(Map<String, String> environment) {
        URI base = local(required(environment, "E2E_BASE_URL"), true);
        URI mail = local(required(environment, "E2E_MAIL_URL"), false);
        String email = required(environment, "E2E_ADMIN_EMAIL");
        String password = required(environment, "E2E_ADMIN_PASSWORD");
        String key = required(environment, "E2E_ADMIN_TOTP_KEY");
        String runId = required(environment, "E2E_RUN_ID");
        if (!email.endsWith("@example.test")) throw new IllegalArgumentException("E2E_ADMIN_EMAIL_NOT_SYNTHETIC");
        if (password.length() < 15 || password.length() > 128) throw new IllegalArgumentException("E2E_ADMIN_PASSWORD_INVALID");
        if (!KEY.matcher(key).matches()) throw new IllegalArgumentException("E2E_ADMIN_TOTP_KEY_INVALID");
        if (!RUN_ID.matcher(runId).matches()) throw new IllegalArgumentException("E2E_RUN_ID_INVALID");
        Path evidence = Path.of(environment.getOrDefault("E2E_EVIDENCE_DIR", "target/e2e-evidence"));
        return new E2eConfig(base, mail, email, password, key, evidence, runId);
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("E2E_SETTING_MISSING: " + name);
        return value;
    }

    /** The browser origin must be exactly the configured public origin, which local mode pins to localhost. */
    private static URI local(String value, boolean browserOrigin) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("E2E_TARGET_NOT_LOCAL");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        boolean allowed = browserOrigin ? host.equals("localhost") : host.equals("localhost") || host.equals("127.0.0.1");
        boolean plain = uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                && (uri.getPath() == null || uri.getPath().isEmpty() || uri.getPath().equals("/"));
        if (!"http".equals(uri.getScheme()) || !allowed || !plain || uri.getPort() < 1) {
            throw new IllegalArgumentException("E2E_TARGET_NOT_LOCAL");
        }
        return URI.create("http://" + host + ":" + uri.getPort());
    }

    @Override
    public String toString() {
        return "E2eConfig[baseUrl=" + baseUrl + ", mailUrl=" + mailUrl + ", credentials=[redacted], runId=" + runId + "]";
    }
}
