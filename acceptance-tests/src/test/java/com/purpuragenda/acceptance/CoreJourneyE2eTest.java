package com.purpuragenda.acceptance;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The 1.0.0 core journey in a real Chromium against the isolated real Compose stack: PostgreSQL, Mailpit and the
 * application behind Caddy. No mocks, no test endpoints and no authentication bypass. The administrator was enrolled
 * through the private admin-setup terminal flow by scripts/ci/verify-e2e.py; the code is computed from its key.
 */
@Tag("e2e")
class CoreJourneyE2eTest {
    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");
    private static final String ZOOM = "https://us02web.zoom.us/j/81234567890";
    private static final double TIMEOUT_MS = 30_000;
    private static final Duration MAIL_TIMEOUT = Duration.ofSeconds(120);

    private String step = "start";
    private Page focus;

    private void at(String name, Page page) {
        step = name;
        focus = page;
    }

    private static Locator button(Page page, String name) {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(name).setExact(true));
    }

    private static Locator heading(Page page, String name) {
        return page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName(name).setExact(true));
    }

    private static Locator field(Page page, String label) {
        return page.getByLabel(label, new Page.GetByLabelOptions().setExact(true));
    }

    @Test
    void guestRequestsAdministratorApprovesAndBothSidesSeeConfirmed() throws Exception {
        E2eConfig config = E2eConfig.from(System.getenv());
        String guestEmail = "guest+" + config.runId() + "@example.test";
        String origin = config.baseUrl().toString();
        LocalDate today = LocalDate.now(BRUSSELS);
        MailpitClient mail = new MailpitClient(config.mailUrl());
        PlaywrightAssertions.setDefaultAssertionTimeout(TIMEOUT_MS);
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            try {
                Browser.NewContextOptions options = new Browser.NewContextOptions().setViewportSize(1280, 900).setLocale("en-GB");
                try (BrowserContext guest = browser.newContext(options); BrowserContext administrator = browser.newContext(options)) {
                    guest.setDefaultTimeout(TIMEOUT_MS);
                    administrator.setDefaultTimeout(TIMEOUT_MS);
                    String reference = guestRequestsBooking(guest, origin, today, guestEmail, mail);
                    Page agenda = administratorApproves(administrator, config, today, reference);
                    guestSeesConfirmation(guest, mail, guestEmail);
                    agenda.close();
                }
            } catch (Throwable failure) {
                keepEvidence(config, failure);
                throw failure;
            }
        }
    }

    private String guestRequestsBooking(BrowserContext guest, String origin, LocalDate today, String email, MailpitClient mail) throws Exception {
        Page page = guest.newPage();
        at("guest opens /book", page);
        page.navigate(origin + "/book");
        assertThat(heading(page, "Request a consultation")).isVisible();

        at("guest selects a service and an available time", page);
        Locator services = page.locator("input[name=consultation]");
        assertThat(services.first()).isVisible();
        services.first().check();
        field(page, "Through").fill(today.plusDays(27).toString());
        button(page, "Show available times").click();
        Locator times = page.locator("input[name=time]");
        assertThat(times.first()).isVisible();
        times.first().check();
        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Your selection"))).isVisible();
        button(page, "Continue").click();

        at("guest requests the email proof", page);
        assertThat(heading(page, "Verify your email")).isVisible();
        page.locator("#booking-email").fill(email);
        button(page, "Send verification link").click();
        assertThat(page.getByText("Check your inbox.")).isVisible();

        at("guest reads the proof link from Mailpit and verifies in the same browser", page);
        MailpitClient.Mail proof = mail.await(email, candidate -> MailpitClient.proofLink(candidate.text()).isPresent(),
                MAIL_TIMEOUT, "verification email for the guest");
        String link = MailpitClient.proofLink(proof.text()).orElseThrow();
        Page verification = guest.newPage();
        at("guest opens the proof link", verification);
        verification.navigate(link);
        button(verification, "Verify email").click();
        assertThat(verification.getByText("Your email is verified for this browser.")).isVisible();
        verification.close();

        at("guest returns to the booking page", page);
        button(page, "I have verified my email").click();
        assertThat(heading(page, "Your consultation details")).isVisible();

        at("guest enters intake", page);
        field(page, "First name").fill("Synthetic");
        field(page, "Last name").fill("Journey");
        field(page, "Telephone").fill("+32 470 00 00 01");
        field(page, "Birth date").fill("1990-05-17");
        field(page, "Birth time").fill("08:30");
        field(page, "Birthplace").fill("Exampleville");
        field(page, "Message (optional)").fill("Synthetic end-to-end booking.");
        button(page, "Review request").click();

        at("guest reviews and submits", page);
        assertThat(heading(page, "Review your request")).isVisible();
        page.locator(".agreement input").check();
        button(page, "Submit booking request").click();

        at("guest sees the pending booking", page);
        assertThat(heading(page, "Your booking")).isVisible();
        assertThat(page.getByText("Status: pending confirmation.")).isVisible();
        String reference = page.locator("p:has-text('Reference:') strong").innerText().trim();
        assertTrue(reference.matches("\\S{8,80}"), "unexpected booking reference shape");
        return reference;
    }

    private Page administratorApproves(BrowserContext administrator, E2eConfig config, LocalDate today, String reference) {
        String origin = config.baseUrl().toString();
        Page page = administrator.newPage();
        at("administrator opens the private sign-in", page);
        page.navigate(origin + "/admin/login");
        assertThat(heading(page, "Administrator sign in")).isVisible();

        at("administrator signs in with password and a fresh authenticator code", page);
        page.locator("#admin-email").fill(config.adminEmail());
        page.locator("#admin-password").fill(config.adminPassword());
        if (Totp.secondsToNextStep(Instant.now()) <= 3) {
            sleep(Duration.ofSeconds(4)); // Never submit a code across a time-step boundary.
        }
        page.locator("#admin-totp").fill(Totp.code(config.adminTotpKey(), Instant.now()));
        button(page, "Sign in").click();
        page.waitForURL(origin + "/admin");
        assertThat(heading(page, "Agenda")).isVisible();

        at("administrator finds the pending booking in the agenda", page);
        page.locator("#agenda-from").fill(today.toString());
        page.locator("#agenda-through").fill(today.plusDays(30).toString());
        button(page, "Show").click();
        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName(Pattern.compile("^Awaiting confirmation \\(1\\)$")))).isVisible();
        Locator row = page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(reference)).first();
        assertThat(row).isVisible();
        row.click();

        at("administrator approves with a manual Zoom link", page);
        assertThat(page.locator("dd").filter(new Locator.FilterOptions().setHasText(Pattern.compile("^PENDING_CONFIRMATION$")))).isVisible();
        page.locator("#meeting-url").fill(ZOOM);
        button(page, "Approve booking").click();
        assertThat(page.getByText("Booking approved.")).isVisible();
        assertThat(page.locator("dd").filter(new Locator.FilterOptions().setHasText(Pattern.compile("^CONFIRMED$")))).isVisible();
        assertThat(page.locator("dd").filter(new Locator.FilterOptions().setHasText(ZOOM))).isVisible();
        return page;
    }

    private void guestSeesConfirmation(BrowserContext guest, MailpitClient mail, String email) throws Exception {
        Page page = guest.pages().stream().filter(candidate -> candidate.url().endsWith("/book")).findFirst().orElseThrow();
        at("guest refreshes the status and sees CONFIRMED", page);
        button(page, "Refresh booking status").click();
        assertThat(page.getByText("Status: confirmed.")).isVisible();
        assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Open meeting link"))).hasAttribute("href", ZOOM);

        at("customer notification mails are in Mailpit", page);
        MailpitClient.Mail received = mail.await(email, candidate -> candidate.subject().equals("Booking request received"),
                MAIL_TIMEOUT, "request-received mail");
        assertFalse(received.text().isBlank());
        MailpitClient.Mail confirmed = mail.await(email, candidate -> candidate.subject().equals("Booking confirmed"),
                MAIL_TIMEOUT, "confirmation mail");
        assertTrue(confirmed.text().contains("Meeting link: " + ZOOM), "confirmation mail carries the approved meeting link");
        assertNotEquals(received.id(), confirmed.id());
        assertEquals(List.of(email), confirmed.recipients());
    }

    private void keepEvidence(E2eConfig config, Throwable failure) {
        try {
            List<String> visible = List.of();
            String url = "";
            if (focus != null && !focus.isClosed()) {
                url = focus.url();
                visible = focus.locator("h1, h2, [role=alert], [role=status]").allInnerTexts();
            }
            String cause = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage()).lines().findFirst().orElse("");
            Evidence.write(config.evidenceDirectory(), "core-journey-failure.txt", Evidence.render(step, cause, url, visible));
        } catch (Exception ignored) {
            // Evidence is best effort; the original failure is always rethrown.
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", interrupted);
        }
    }
}
