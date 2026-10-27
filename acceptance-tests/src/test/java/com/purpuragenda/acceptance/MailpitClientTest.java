package com.purpuragenda.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MailpitClientTest {
    private static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde";
    private HttpServer server;
    private final AtomicInteger listings = new AtomicInteger();
    private volatile int appearOnListing = 1;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v1/messages", exchange -> {
            boolean visible = listings.incrementAndGet() >= appearOnListing;
            String json = visible
                    ? "{\"total\":3,\"messages\":[{\"ID\":\"m-other\",\"Subject\":\"Other\",\"To\":[{\"Address\":\"someone@example.test\"}]},"
                    + "{\"ID\":\"m-proof\",\"Subject\":\"Verify your Purpuragenda email\",\"To\":[{\"Name\":\"\",\"Address\":\"Guest@Example.test\"}]},"
                    + "{\"ID\":\"m-html\",\"Subject\":\"Booking confirmed\",\"To\":[{\"Address\":\"guest@example.test\"}]}]}"
                    : "{\"total\":0,\"messages\":[]}";
            respond(exchange, 200, json);
        });
        server.createContext("/api/v1/message/m-proof", exchange -> respond(exchange, 200,
                "{\"ID\":\"m-proof\",\"Text\":\"Open this link and choose Verify email:\\nhttp://localhost:18080/verify#token=" + TOKEN + "&purpose=CREATE\\n\\nThis single-use link\"}"));
        server.createContext("/api/v1/message/m-html", exchange -> respond(exchange, 200,
                "{\"ID\":\"m-html\",\"Text\":\"\",\"HTML\":\"<p>Meeting link: https://us02web.zoom.us/j/81234567890</p>\"}"));
        server.createContext("/api/v1/message/m-other", exchange -> respond(exchange, 200, "{\"ID\":\"m-other\",\"Text\":\"unrelated\"}"));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private MailpitClient client(List<Duration> pauses) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new MailpitClient(base, java.net.http.HttpClient.newHttpClient(), pauses::add);
    }

    @Test
    void extractsOnlyACompleteLocalProofLink() {
        String text = "Open this link and choose Verify email:\nhttp://localhost:18080/verify#token=" + TOKEN + "&purpose=CREATE\n\nExpires.";
        assertEquals("http://localhost:18080/verify#token=" + TOKEN + "&purpose=CREATE", MailpitClient.proofLink(text).orElseThrow());
        assertEquals("http://127.0.0.1:1/verify#token=" + TOKEN + "&purpose=MANAGE",
                MailpitClient.proofLink("x http://127.0.0.1:1/verify#token=" + TOKEN + "&purpose=MANAGE.").orElseThrow());
        assertTrue(MailpitClient.proofLink("http://localhost:18080/verify#token=short&purpose=CREATE").isEmpty());
        assertTrue(MailpitClient.proofLink("http://localhost:18080/verify#token=" + TOKEN + "X&purpose=CREATE").isEmpty());
        assertTrue(MailpitClient.proofLink("https://evil.example/verify#token=" + TOKEN + "&purpose=CREATE").isEmpty());
        assertTrue(MailpitClient.proofLink("http://localhost:18080/verify#token=" + TOKEN + "&purpose=ADMIN").isEmpty());
        assertTrue(MailpitClient.proofLink("").isEmpty());
        assertTrue(MailpitClient.proofLink(null).isEmpty());
    }

    @Test
    void filtersByRecipientCaseInsensitivelyAndFallsBackToHtmlBody() throws Exception {
        List<MailpitClient.Mail> mail = client(new java.util.ArrayList<>()).messagesTo("guest@example.test");
        assertEquals(List.of("m-proof", "m-html"), mail.stream().map(MailpitClient.Mail::id).toList());
        assertTrue(MailpitClient.proofLink(mail.get(0).text()).isPresent());
        assertTrue(mail.get(1).text().contains("Meeting link: https://us02web.zoom.us/j/81234567890"));
        assertEquals("Booking confirmed", mail.get(1).subject());
        assertFalse(mail.get(0).toString().contains("example.test"));
        assertTrue(client(new java.util.ArrayList<>()).messagesTo("nobody@example.test").isEmpty());
    }

    @Test
    void pollsUntilAMatchingMessageArrivesAndFailsAfterTheTimeout() throws Exception {
        List<Duration> pauses = new java.util.ArrayList<>();
        appearOnListing = 3;
        MailpitClient.Mail found = client(pauses).await("guest@example.test",
                mail -> mail.subject().equals("Booking confirmed"), Duration.ofSeconds(10), "confirmation");
        assertEquals("m-html", found.id());
        assertEquals(2, pauses.size());

        List<Duration> exhausted = new java.util.ArrayList<>();
        AssertionError failure = assertThrows(AssertionError.class, () -> client(exhausted).await("guest@example.test",
                mail -> mail.subject().equals("never sent"), Duration.ofSeconds(3), "missing mail"));
        assertTrue(failure.getMessage().contains("missing mail"));
        assertEquals(3, exhausted.size());
    }

    @Test
    void reportsUnavailableMailpitWithoutLeakingContent() {
        server.removeContext("/api/v1/messages");
        IOException failure = assertThrows(IOException.class, () -> client(new java.util.ArrayList<>()).messagesTo("guest@example.test"));
        assertTrue(failure.getMessage().contains("404"));
        assertFalse(failure.getMessage().contains("guest"));
    }
}
