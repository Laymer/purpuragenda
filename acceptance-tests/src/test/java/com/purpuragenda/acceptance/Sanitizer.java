package com.purpuragenda.acceptance;

import java.util.regex.Pattern;

/** Removes anything secret or personal from text that may be kept as failure evidence. */
final class Sanitizer {
    private static final Pattern HEADER = Pattern.compile("(?i)\\b(set-cookie|cookie|authorization|x-csrf-token|idempotency-key)\\b\\s*[:=][^\\r\\n]*");
    private static final Pattern FRAGMENT = Pattern.compile("#[^\\s\"')<>]*");
    private static final Pattern QUERY = Pattern.compile("\\?[^\\s\"')<>#]*");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("\\+\\d[\\d ().-]{6,}\\d");
    private static final Pattern LONG_TOKEN = Pattern.compile("[A-Za-z0-9_-]{32,}");
    private static final Pattern CODE = Pattern.compile("(?<![\\d.])\\d{6}(?![\\d.])");

    private Sanitizer() {}

    static String clean(String text) {
        if (text == null) return "";
        String value = HEADER.matcher(text).replaceAll(m -> m.group(1) + ": [redacted]");
        value = FRAGMENT.matcher(value).replaceAll("#[redacted]");
        value = QUERY.matcher(value).replaceAll("?[redacted]");
        value = EMAIL.matcher(value).replaceAll("[redacted-email]");
        value = PHONE.matcher(value).replaceAll("[redacted-phone]");
        value = LONG_TOKEN.matcher(value).replaceAll("[redacted-token]");
        value = CODE.matcher(value).replaceAll("[redacted-code]");
        return value.replaceAll("\\s+", " ").trim();
    }

    /** A page URL reduced to its path. Query and fragment never survive. */
    static String path(String url) {
        if (url == null) return "";
        try {
            java.net.URI uri = java.net.URI.create(url);
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            return clean(path);
        } catch (IllegalArgumentException malformed) {
            return "[unparseable-url]";
        }
    }
}
