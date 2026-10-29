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
 * The core booking and optional account journey in a real Chromium against the isolated real Compose stack: PostgreSQL, Mailpit and the
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
    private Instant guestProofRequestedAt;

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
                    MailpitClient.Mail received = requestReceivedMail(guest, mail, guestEmail);
                    Page agenda = administratorApproves(administrator, config, today, reference);
                    guestSeesConfirmation(guest, mail, guestEmail, received);
                    accountEnrollmentAndSettings(guest, origin, guestEmail, reference, mail);
                    administratorExports(agenda, origin, guestEmail);
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
        guestProofRequestedAt = Instant.now();
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

    /**
     * Awaited while the booking is still pending: the application deliberately suppresses a request-received
     * mail that is only sent after the booking was confirmed (BOOKING_NOT_PENDING), so a fast approval must not race it.
     */
    private MailpitClient.Mail requestReceivedMail(BrowserContext guest, MailpitClient mail, String email) throws Exception {
        Page page = guest.pages().stream().filter(candidate -> candidate.url().endsWith("/book")).findFirst().orElseThrow();
        at("request-received mail is in Mailpit while the booking is pending", page);
        MailpitClient.Mail received = mail.await(email, candidate -> candidate.subject().equals("Booking request received"),
                MAIL_TIMEOUT, "request-received mail");
        assertFalse(received.text().isBlank());
        return received;
    }

    private void guestSeesConfirmation(BrowserContext guest, MailpitClient mail, String email, MailpitClient.Mail received) throws Exception {
        Page page = guest.pages().stream().filter(candidate -> candidate.url().endsWith("/book")).findFirst().orElseThrow();
        at("guest refreshes the status and sees CONFIRMED", page);
        button(page, "Refresh booking status").click();
        assertThat(page.getByText("Status: confirmed.")).isVisible();
        assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Open meeting link"))).hasAttribute("href", ZOOM);

        at("confirmation mail is in Mailpit", page);
        MailpitClient.Mail confirmed = mail.await(email, candidate -> candidate.subject().equals("Booking confirmed"),
                MAIL_TIMEOUT, "confirmation mail");
        assertTrue(confirmed.text().contains("Meeting link: " + ZOOM), "confirmation mail carries the approved meeting link");
        assertNotEquals(received.id(), confirmed.id());
        assertEquals(List.of(email), confirmed.recipients());
    }

    private void accountEnrollmentAndSettings(BrowserContext guest, String origin, String email, String reference, MailpitClient mail) throws Exception {
        Page page = guest.newPage();
        at("guest requests optional account enrollment", page);
        // Email issuance shares a cooldown across proof purposes; exercise it without altering server time or policy.
        Duration remaining = Duration.between(Instant.now(), guestProofRequestedAt.plusSeconds(65));
        if (!remaining.isNegative()) sleep(remaining);
        page.navigate(origin + "/account/create");
        field(page, "Email address").fill(email);
        button(page, "Email me an account link").click();
        assertThat(page.getByText("Check your inbox for an account creation link.", new Page.GetByTextOptions().setExact(false))).isVisible();
        MailpitClient.Mail proof = mail.await(email, candidate -> MailpitClient.proofLink(candidate.text()).filter(link -> link.endsWith("purpose=ACCOUNT")).isPresent(), MAIL_TIMEOUT, "optional account email");
        at("guest explicitly chooses an account password", page);
        page.navigate(MailpitClient.proofLink(proof.text()).orElseThrow());
        assertThat(heading(page, "Create your optional account")).isVisible();
        assertFalse(page.url().contains("#"), "account proof fragment is removed");
        String password = java.util.UUID.randomUUID().toString(), replacement = java.util.UUID.randomUUID().toString();
        page.locator("#new-account-password").fill(password);
        page.locator("#confirm-account-password").fill(password);
        button(page, "Create my account").click();
        assertThat(page.getByText("Your account was created. Sign in with your login email and password.")).isVisible();
        accountSignIn(page, origin, email, password);
        at("account history retains the guest booking", page);
        Locator booking = page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(reference).setExact(true));
        assertThat(booking).isVisible(); booking.click();
        assertThat(page.getByText("Status: confirmed.")).isVisible();
        assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Open meeting link"))).hasAttribute("href", ZOOM);
        at("account saves an optional profile without changing booking snapshots", page);
        page.navigate(origin + "/account/settings");
        assertThat(heading(page, "Account settings")).isVisible();
        page.locator("#profile-first").fill("Synthetic profile");
        page.locator("#profile-last").fill("Updated");
        page.locator("#profile-phone").fill("+32 470 00 00 02");
        button(page, "Save profile").click();
        assertThat(page.getByText("Your optional profile was saved.")).isVisible();
        page.reload(); assertThat(page.locator("#profile-first")).hasValue("Synthetic profile");
        at("account changes password and signs in again", page);
        page.locator("#current-password").fill(password);
        page.locator("#replacement-password").fill(replacement);
        page.locator("#repeat-password").fill(replacement);
        button(page, "Change password and sign out").click();
        assertThat(page.getByText("Your password was changed and all account sessions were signed out. Sign in again.")).isVisible();
        assertThat(page.locator("#profile-first")).hasCount(0);
        accountSignIn(page, origin, email, replacement);
        assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(reference).setExact(true))).isVisible();
        page.navigate(origin + "/account/settings");
        assertThat(page.locator("#profile-first")).hasValue("Synthetic profile");
        assertThat(page.locator("#profile-phone")).hasValue("+32470000002");
        changeDeliveryContact(page,origin,email,reference,mail);
        page.close();
    }

    private void changeDeliveryContact(Page page,String origin,String email,String reference,MailpitClient mail) throws Exception {
        String replacement="delivery+"+email;
        at("account requests a verified replacement delivery email",page);
        page.navigate(origin+"/account");
        page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName(reference).setExact(true)).click();
        field(page,"New delivery email").fill(replacement);
        button(page,"Send contact verification link").click();
        assertThat(page.getByText("Check the new inbox.",new Page.GetByTextOptions().setExact(false))).isVisible();
        var proof=mail.await(replacement,candidate->MailpitClient.proofLink(candidate.text()).filter(link->link.contains("&purpose=CONTACT&bookingId=")).isPresent(),MAIL_TIMEOUT,"replacement contact proof");
        at("account explicitly confirms the scoped delivery contact proof",page);
        page.navigate(MailpitClient.proofLink(proof.text()).orElseThrow());
        assertThat(heading(page,"Change booking delivery email")).isVisible();
        assertFalse(page.url().contains("#"),"contact fragment is removed before review");
        button(page,"Confirm delivery email change").click();
        assertThat(page.getByText("Delivery email changed.",new Page.GetByTextOptions().setExact(false))).isVisible();
        at("both delivery addresses receive a safe security notice",page);
        for(String recipient:List.of(email,replacement)) {
            var notice=mail.await(recipient,candidate->candidate.subject().equals("Booking delivery contact changed"),MAIL_TIMEOUT,"delivery contact security notice");
            assertEquals(List.of(recipient),notice.recipients());assertTrue(notice.text().contains(reference));assertFalse(notice.text().contains(ZOOM));assertFalse(notice.text().contains(replacement));
        }
        at("account history and login identity remain unchanged after contact change",page);
        page.navigate(origin+"/account");assertThat(page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName(reference).setExact(true))).isVisible();
        page.navigate(origin+"/account/settings");assertThat(page.getByText("Login email: "+email+".",new Page.GetByTextOptions().setExact(false))).isVisible();
    }

    private void administratorExports(Page page,String origin,String email) throws Exception {
        at("administrator adds a customer-related private note",page);
        page.navigate(page.url()+"/history");
        field(page,"New private note (up to 4000 characters)").fill("Synthetic export note.");
        button(page,"Add private note").click();
        assertThat(page.getByText("Private note added.",new Page.GetByTextOptions().setExact(false))).isVisible();
        at("administrator locates the stable customer for a privacy export",page);
        page.navigate(origin+"/admin/customers");
        field(page,"Search by name, email or booking reference").fill(email);button(page,"Search customers").click();
        button(page,"View bookings for "+email).click();
        String customerPage=page.url();
        at("administrator reviews and confirms a profile correction",page);
        page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Correct profile").setExact(true)).click();
        page.locator("#correction-firstName").fill("Reviewed profile");page.locator("#correction-reason").fill("Synthetic profile correction request");button(page,"Prepare correction preview").click();
        assertThat(page.locator("table")).containsText("Reviewed profile");page.getByRole(AriaRole.CHECKBOX).check();button(page,"Confirm correction").click();assertThat(page.getByText("Correction applied.",new Page.GetByTextOptions().setExact(false))).isVisible();
        page.navigate(customerPage);field(page,"Search by name, email or booking reference").fill(email);button(page,"Search customers").click();button(page,"View bookings for "+email).click();
        at("administrator reviews and corrects the private note",page);
        page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Correct private notes").setExact(true)).first().click();button(page,"Correct this note").click();
        page.locator("#correction-text").fill("Reviewed synthetic export note.");page.locator("#correction-reason").fill("Synthetic note correction request");button(page,"Prepare correction preview").click();
        assertThat(page.locator("table")).containsText("Reviewed synthetic export note.");assertThat(page.locator("table")).containsText("Reviewed synthetic export note.");page.getByRole(AriaRole.CHECKBOX).check();button(page,"Confirm correction").click();assertThat(page.getByText("Correction applied.",new Page.GetByTextOptions().setExact(false))).isVisible();
        page.navigate(customerPage);field(page,"Search by name, email or booking reference").fill(email);button(page,"Search customers").click();button(page,"View bookings for "+email).click();
        page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Customer data export").setExact(true)).click();
        at("administrator previews customer notes and explicitly releases the export",page);
        page.locator("#export-reason").fill("Synthetic customer export request");button(page,"Prepare export preview").click();
        assertThat(page.locator("pre")).containsText("Reviewed synthetic export note.");
        assertThat(page.locator("pre")).containsText("Reviewed profile");
        assertThat(page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Save reviewed customer export"))).hasCount(0);
        page.getByRole(AriaRole.CHECKBOX).check();button(page,"Confirm and release export").click();
        assertThat(page.getByText("Export released after review.")).isVisible();button(page,"Prepare secure download").click();
        at("administrator downloads only the reviewed JSON export",page);
        var download=page.waitForDownload(()->page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Save reviewed customer export").setExact(true)).click());
        assertEquals("customer-export.json",download.suggestedFilename());
        String content=java.nio.file.Files.readString(download.path());
        assertTrue(content.contains("Reviewed synthetic export note."));assertTrue(content.contains("Reviewed profile"));assertTrue(content.contains(email));
        for(String forbidden:List.of("password_hash","totp_secret","csrf_hash","token_hash","encrypted_envelope"))assertFalse(content.contains(forbidden));
        download.delete();
    }

    private void accountSignIn(Page page, String origin, String email, String password) {
        at("account signs in explicitly", page);
        page.navigate(origin + "/account/login");
        page.locator("#account-email").fill(email);
        page.locator("#account-password").fill(password);
        button(page, "Sign in").click();
        page.waitForURL(origin + "/account");
        assertThat(heading(page, "Your account")).isVisible();
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
