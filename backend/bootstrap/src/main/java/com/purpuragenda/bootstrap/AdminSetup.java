package com.purpuragenda.bootstrap;

import com.purpuragenda.application.PrivateAdminBootstrap;
import com.purpuragenda.application.PrivateAdminMfa;
import com.purpuragenda.application.port.out.PrivateAdminStore;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Private operator workflow: creates the single administrator and completes TOTP enrollment.
 * Credentials come only from a private terminal and provisioning material goes only back to it.
 * No argument, environment variable, log or return value carries a password, secret or code.
 */
final class AdminSetup {
    static final int ATTEMPTS = 5;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    interface Terminal {
        boolean interactive();
        String line(String prompt);
        /** Reads without echo; the caller clears the returned buffer. */
        char[] secret(String prompt);
        void show(String text);
    }

    private final PrivateAdminBootstrap bootstrap;
    private final PrivateAdminMfa mfa;
    private final Terminal terminal;

    AdminSetup(PrivateAdminBootstrap bootstrap, PrivateAdminMfa mfa, Terminal terminal) {
        this.bootstrap = bootstrap;
        this.mfa = mfa;
        this.terminal = terminal;
    }

    /** Returns zero only when an administrator was created and fully enrolled. */
    int run() {
        if (!terminal.interactive()) return refuse("A private interactive terminal is required; nothing was changed.");
        char[] password = null, confirmation = null;
        try {
            String email = terminal.line("Administrator email: ");
            password = terminal.secret("Password (15-128 characters): ");
            confirmation = terminal.secret("Repeat password: ");
            if (email == null || password == null || confirmation == null || !Arrays.equals(password, confirmation))
                return refuse("The entries did not match; nothing was changed.");
            try {
                PrivateAdminBootstrap.validatePassword(password.clone());
            } catch (RuntimeException invalid) {
                return refuse("The password must contain 15 to 128 characters without control characters; nothing was changed.");
            }
            try {
                if (bootstrap.create(email, password.clone()) == PrivateAdminStore.Result.ALREADY_EXISTS)
                    return refuse("An administrator already exists; nothing was changed.");
            } catch (RuntimeException unavailable) {
                return refuse("The administrator could not be created; nothing was changed.");
            }
            return enroll(email, password);
        } finally {
            clear(password);
            clear(confirmation);
        }
    }

    private int enroll(String email, char[] password) {
        try {
            var secret = mfa.provision(email, password.clone());
            if (secret.isEmpty()) return incomplete();
            try (var value = secret.get()) {
                byte[] bytes = value.bytes();
                try {
                    String encoded = base32(bytes);
                    terminal.show("Add this authenticator key now. It is shown once and is not stored in any log.\n"
                            + "Key: " + encoded + "\nURI: otpauth://totp/Purpuragenda:" + encode(email)
                            + "?secret=" + encoded + "&issuer=Purpuragenda&algorithm=SHA1&digits=6&period=30");
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                String code = terminal.line("Authenticator code: ");
                var enrollment = mfa.enroll(email, password.clone(), code == null ? "" : code.trim());
                if (enrollment.isPresent()) {
                    try (var issued = enrollment.get()) {
                        var text = new StringBuilder("Enrollment complete. Store these one-time recovery codes now; they are not shown again.\n");
                        issued.codes().forEach(c -> {
                            char[] characters = c.value();
                            text.append(characters).append('\n');
                            clear(characters);
                        });
                        terminal.show(text.toString());
                    }
                    return 0;
                }
                terminal.show("That code was not accepted.");
            }
            return incomplete();
        } catch (RuntimeException unavailable) {
            return incomplete();
        }
    }

    private int incomplete() {
        return refuse("The administrator exists but authenticator enrollment is incomplete.");
    }

    private int refuse(String message) {
        terminal.show(message);
        return 1;
    }

    private static void clear(char[] value) {
        if (value != null) Arrays.fill(value, '\0');
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** RFC 4648 Base32 without padding, as authenticator applications expect. */
    static String base32(byte[] bytes) {
        var result = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                result.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
            buffer &= (1 << bits) - 1;
        }
        if (bits > 0) result.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return result.toString();
    }
}
