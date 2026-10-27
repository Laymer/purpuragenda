package com.purpuragenda.acceptance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Sanitized failure evidence: step, page path and visible status text only. No screenshots, traces or HTML. */
final class Evidence {
    private Evidence() {}

    static String render(String step, String cause, String pageUrl, List<String> visibleText) {
        StringBuilder out = new StringBuilder();
        out.append("failed step: ").append(Sanitizer.clean(step)).append('\n');
        out.append("cause: ").append(Sanitizer.clean(cause)).append('\n');
        out.append("page path: ").append(Sanitizer.path(pageUrl)).append('\n');
        out.append("visible headings and messages:\n");
        for (String line : visibleText) {
            String clean = Sanitizer.clean(line);
            if (!clean.isEmpty()) out.append("  - ").append(clean.length() > 300 ? clean.substring(0, 300) : clean).append('\n');
        }
        return out.toString();
    }

    static Path write(Path directory, String name, String content) throws IOException {
        if (!name.matches("[a-z0-9][a-z0-9.-]{0,63}")) throw new IllegalArgumentException("INVALID_EVIDENCE_NAME");
        Files.createDirectories(directory);
        Path file = directory.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
