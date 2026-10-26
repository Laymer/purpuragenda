package com.purpuragenda.adapters.in.rest;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Canonical form of a raw request URI for security decisions. The servlet container reports the raw, still
 * percent-encoded URI, while MVC routing works on the decoded path without ';' path parameters. Security filters
 * must therefore compare this normalized value: percent-decode once, drop path parameters, collapse repeated
 * slashes, remove a trailing slash and fold case. Folding case is deliberately wider than MVC matching so
 * every variant that could ever reach a handler is covered. Undecodable input returns null and callers must
 * treat it as a possible match (fail closed).
 */
final class RequestPaths {
    private RequestPaths() { }

    static String normalize(String rawUri) {
        if (rawUri == null) return null;
        int query = rawUri.indexOf('?');
        String path = query < 0 ? rawUri : rawUri.substring(0, query);
        var out = new StringBuilder();
        for (String segment : path.split("/", -1)) {
            int parameter = segment.indexOf(';');
            String decoded = decode(parameter < 0 ? segment : segment.substring(0, parameter));
            if (decoded == null) return null;
            // An encoded slash or a second path parameter after decoding is still a different spelling of the same route.
            int hidden = decoded.indexOf(';');
            if (hidden >= 0) decoded = decoded.substring(0, hidden);
            for (String part : decoded.split("/", -1)) if (!part.isEmpty()) out.append('/').append(part.toLowerCase(Locale.ROOT));
        }
        return out.length() == 0 ? "/" : out.toString();
    }

    private static String decode(String segment) {
        if (segment.indexOf('%') < 0) return segment;
        var bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c != '%') { byte[] raw = String.valueOf(c).getBytes(StandardCharsets.UTF_8); bytes.write(raw, 0, raw.length); continue; }
            if (i + 3 > segment.length()) return null;
            int high = Character.digit(segment.charAt(i + 1), 16), low = Character.digit(segment.charAt(i + 2), 16);
            if (high < 0 || low < 0) return null;
            bytes.write(high * 16 + low);
            i += 2;
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
