package com.purpuragenda.acceptance;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads captured mail through the Mailpit HTTP API only; nothing is injected and nothing is deleted. */
final class MailpitClient {
    /** The guest proof mail link: {origin}/verify#token=&lt;43 characters&gt;&amp;purpose=CREATE|MANAGE|ACCOUNT or CONTACT with exact booking id. */
    private static final Pattern PROOF_LINK = Pattern.compile(
            "(http://(?:localhost|127\\.0\\.0\\.1):\\d{1,5}/verify#token=[A-Za-z0-9_-]{43}&purpose=(?:CREATE|MANAGE|ACCOUNT|CONTACT&bookingId=[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}))(?![A-Za-z0-9_-])");

    /** A captured message. The body text is held in memory for assertions and is never logged. */
    record Mail(String id, String subject, List<String> recipients, String text) {
        @Override
        public String toString() {
            return "Mail[subject=" + subject + ", recipients=[redacted]]";
        }
    }

    interface Pause {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final URI base;
    private final HttpClient http;
    private final Pause pause;

    MailpitClient(URI base) {
        this(base, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).proxy(java.net.ProxySelector.of(null)).build(),
                duration -> Thread.sleep(duration.toMillis()));
    }

    MailpitClient(URI base, HttpClient http, Pause pause) {
        this.base = base;
        this.http = http;
        this.pause = pause;
    }

    static Optional<String> proofLink(String body) {
        if (body == null) return Optional.empty();
        Matcher match = PROOF_LINK.matcher(body);
        return match.find() ? Optional.of(match.group(1)) : Optional.empty();
    }

    /** Every captured message addressed to the recipient, newest first, with its decoded text. */
    List<Mail> messagesTo(String address) throws IOException, InterruptedException {
        String wanted = address.toLowerCase(Locale.ROOT);
        List<Mail> found = new ArrayList<>();
        JsonObject listing = get("/api/v1/messages?limit=200");
        JsonArray messages = listing.has("messages") && listing.get("messages").isJsonArray() ? listing.getAsJsonArray("messages") : new JsonArray();
        for (JsonElement element : messages) {
            JsonObject summary = element.getAsJsonObject();
            List<String> recipients = recipients(summary);
            if (!recipients.contains(wanted)) continue;
            String id = text(summary, "ID");
            JsonObject detail = get("/api/v1/message/" + URLEncoder.encode(id, StandardCharsets.UTF_8));
            String body = text(detail, "Text");
            if (body.isEmpty()) body = text(detail, "HTML");
            found.add(new Mail(id, text(summary, "Subject"), recipients, body));
        }
        return found;
    }

    /** Polls until a message to the address satisfies the condition, or fails after the timeout. */
    Mail await(String address, Predicate<Mail> condition, Duration timeout, String description) throws IOException, InterruptedException {
        Duration waited = Duration.ZERO, interval = Duration.ofSeconds(1);
        while (true) {
            for (Mail mail : messagesTo(address)) {
                if (condition.test(mail)) return mail;
            }
            if (waited.compareTo(timeout) >= 0) {
                throw new AssertionError("No captured message matched within " + timeout.toSeconds() + " s: " + description);
            }
            pause.sleep(interval);
            waited = waited.plus(interval);
        }
    }

    private static List<String> recipients(JsonObject message) {
        List<String> addresses = new ArrayList<>();
        if (message.has("To") && message.get("To").isJsonArray()) {
            for (JsonElement item : message.getAsJsonArray("To")) {
                if (item.isJsonObject()) addresses.add(text(item.getAsJsonObject(), "Address").toLowerCase(Locale.ROOT));
            }
        }
        return addresses;
    }

    private static String text(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private JsonObject get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15)).GET()
                .header("Accept", "application/json").build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("Mailpit request failed with HTTP " + response.statusCode());
        JsonElement parsed = JsonParser.parseString(response.body());
        if (!parsed.isJsonObject()) throw new IOException("Mailpit returned an unexpected document");
        return parsed.getAsJsonObject();
    }
}
