package com.purpuragenda.bootstrap;

import com.purpuragenda.application.PrivateAdminBootstrap;
import com.purpuragenda.application.PrivateAdminMfa;
import com.purpuragenda.application.port.out.AdminMfaUnitOfWork;
import com.purpuragenda.application.port.out.PrivateAdminStore;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Database-free operator-prompt behavior with synthetic values. The real workflow is covered in RuntimeDatabaseTest. */
class AdminSetupTest {
    static final String PASSWORD = "synthetic passphrase 15+";

    static class FakeTerminal implements AdminSetup.Terminal {
        final boolean interactive;
        final Deque<String> lines = new ArrayDeque<>();
        final Deque<String> secrets = new ArrayDeque<>();
        final List<String> shown = new ArrayList<>(), prompts = new ArrayList<>();
        final List<char[]> issued = new ArrayList<>();
        FakeTerminal(boolean interactive) { this.interactive = interactive; }
        @Override public boolean interactive() { return interactive; }
        @Override public String line(String prompt) { prompts.add(prompt); return lines.poll(); }
        @Override public char[] secret(String prompt) {
            prompts.add(prompt);
            String value = secrets.poll();
            if (value == null) return null;
            char[] result = value.toCharArray();
            issued.add(result);
            return result;
        }
        @Override public void show(String text) { shown.add(text); }
    }

    static AdminSetup setup(FakeTerminal terminal, PrivateAdminStore.Result result, int[] creations) {
        var hash = new com.purpuragenda.application.port.out.PasswordHash() {
            @Override public String hash(char[] password) { return "synthetic-hash"; }
            @Override public boolean verify(char[] password, String encoded) { return false; }
        };
        var unit = new AdminMfaUnitOfWork() {
            @Override public <T> T locked(String emailKey, Function<Admin, T> work) { throw new IllegalStateException(); }
        };
        return new AdminSetup(new PrivateAdminBootstrap(hash, (email, passwordHash) -> { creations[0]++; return result; }),
                new PrivateAdminMfa(unit, hash, new com.purpuragenda.adapters.out.security.AdminCrypto()), terminal);
    }

    @Test void base32MatchesRfc4648Vectors() {
        var vectors = Map.of("", "", "f", "MY", "fo", "MZXQ", "foo", "MZXW6", "foob", "MZXW6YQ", "fooba", "MZXW6YTB", "foobar", "MZXW6YTBOI");
        vectors.forEach((plain, encoded) -> assertEquals(encoded, AdminSetup.base32(plain.getBytes(StandardCharsets.US_ASCII))));
        assertEquals("%40%2B%20", AdminSetup.encode("@+ "));
    }

    @Test void nonInteractiveInputIsRefusedBeforeAnyPromptOrChange() {
        var terminal = new FakeTerminal(false);
        int[] creations = {0};
        assertEquals(1, setup(terminal, PrivateAdminStore.Result.CREATED, creations).run());
        assertTrue(terminal.prompts.isEmpty());
        assertEquals(0, creations[0]);
        assertTrue(terminal.shown.getFirst().contains("interactive terminal"));
    }

    @Test void mismatchedMissingAndWeakPasswordsChangeNothingAndAreCleared() {
        for (var entries : List.of(List.of(PASSWORD, PASSWORD + "x"), List.of("too short", "too short"), List.of(PASSWORD))) {
            var terminal = new FakeTerminal(true);
            terminal.lines.add("owner@example.test");
            terminal.secrets.addAll(entries);
            int[] creations = {0};
            assertEquals(1, setup(terminal, PrivateAdminStore.Result.CREATED, creations).run());
            assertEquals(0, creations[0]);
            terminal.issued.forEach(buffer -> assertEquals(0, new String(buffer).chars().filter(c -> c != 0).count()));
            assertFalse(String.join("\n", terminal.shown).contains(PASSWORD));
        }
        var missingEmail = new FakeTerminal(true);
        missingEmail.secrets.addAll(List.of(PASSWORD, PASSWORD));
        assertEquals(1, setup(missingEmail, PrivateAdminStore.Result.CREATED, new int[1]).run());
    }

    @Test void existingAdministratorIsRefusedWithoutEnrollmentPrompts() {
        var terminal = new FakeTerminal(true);
        terminal.lines.add("owner@example.test");
        terminal.secrets.addAll(List.of(PASSWORD, PASSWORD));
        int[] creations = {0};
        assertEquals(1, setup(terminal, PrivateAdminStore.Result.ALREADY_EXISTS, creations).run());
        assertEquals(1, creations[0]);
        assertEquals(List.of("Administrator email: ", "Password (15-128 characters): ", "Repeat password: "), terminal.prompts);
        assertEquals(List.of("An administrator already exists; nothing was changed."), terminal.shown);
        terminal.issued.forEach(buffer -> assertEquals(0, new String(buffer).chars().filter(c -> c != 0).count()));
    }
}
