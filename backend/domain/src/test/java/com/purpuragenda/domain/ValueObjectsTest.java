package com.purpuragenda.domain;

import com.purpuragenda.domain.booking.Money;
import com.purpuragenda.domain.booking.ServiceDefinition;
import com.purpuragenda.domain.customer.Input;
import com.purpuragenda.domain.customer.Intake;
import com.purpuragenda.domain.scheduling.ReservationInterval;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ValueObjectsTest {
    private static final UUID SERVICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant START = Instant.parse("2027-01-31T10:00:00Z");

    private void failure(String code, Runnable action) {
        var error = assertThrows(DomainFailure.class, action::run);
        assertEquals(code, error.code());
        assertEquals(code, error.getMessage());
    }

    @Test void moneyPreservesActualCurrencyAndBoundsIntegerAmounts() {
        assertEquals(0, Money.eur(0).minor());
        assertEquals(100_000_000L, Money.eur(100_000_000).minor());
        assertEquals("EUR", Money.eur(1500).currency());
        assertEquals("USD", new Money(1500, "USD").currency());
        failure("INVALID_AMOUNT", () -> Money.eur(-1));
        failure("INVALID_AMOUNT", () -> Money.eur(100_000_001));
        failure("INVALID_AMOUNT", () -> Money.eur(Long.MAX_VALUE));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"eur", "EU", "EURO", "ZZZ", "123", " EUR"})
    void rejectsInvalidCurrencies(String currency) {
        failure("INVALID_CURRENCY", () -> new Money(1, currency));
    }

    @ParameterizedTest @ValueSource(ints={1, 30, 60, 120})
    void servicesUseIndependentSessionDurationAndEurPrice(int minutes) {
        var service = new ServiceDefinition(SERVICE, 1, " Synthetic reading ", minutes, Money.eur(1500), true);
        assertEquals(SERVICE, service.id());
        assertEquals(1, service.version());
        assertEquals("Synthetic reading", service.name());
        assertEquals(minutes, service.durationMinutes());
        assertEquals(Money.eur(1500), service.price());
        assertTrue(service.active());
        assertFalse(new ServiceDefinition(SERVICE, 2, "Archived", minutes, Money.eur(0), false).active());
        assertEquals(120, Duration.between(START, ReservationInterval.reserve(START).end()).toMinutes());
    }

    @Test void invalidServiceValuesFailWithSafeCodes() {
        failure("INVALID_SERVICE", () -> new ServiceDefinition(null, 1, "Synthetic", 30, Money.eur(0), true));
        failure("INVALID_SERVICE", () -> new ServiceDefinition(SERVICE, 0, "Synthetic", 30, Money.eur(0), true));
        failure("INVALID_DURATION", () -> new ServiceDefinition(SERVICE, 1, "Synthetic", 0, Money.eur(0), true));
        failure("INVALID_DURATION", () -> new ServiceDefinition(SERVICE, 1, "Synthetic", 121, Money.eur(0), true));
        failure("INVALID_CURRENCY", () -> new ServiceDefinition(SERVICE, 1, "Synthetic", 30, null, true));
        failure("INVALID_CURRENCY", () -> new ServiceDefinition(SERVICE, 1, "Synthetic", 30, new Money(1, "USD"), true));
        failure("VALIDATION_FAILED", () -> new ServiceDefinition(SERVICE, 1, "x".repeat(121), 30, Money.eur(0), true));
    }

    @Test void textUsesTrimmedNfcAndUnicodeCodePointLengths() {
        assertEquals("é", Input.text(" e\u0301 ", 1, 1));
        assertEquals("😀", Input.text("😀", 1, 1));
        assertEquals("", Input.text(" ", 0, 1));
        failure("VALIDATION_FAILED", () -> Input.text(null, 0, 1));
        failure("VALIDATION_FAILED", () -> Input.text(" ", 1, 1));
        failure("VALIDATION_FAILED", () -> Input.text("ab", 1, 1));
        failure("VALIDATION_FAILED", () -> Input.text("a\u0000b", 1, 100));
        failure("VALIDATION_FAILED", () -> Input.text("a\nb", 1, 100));
        assertEquals("Reason fits", Input.reason(" Reason fits "));
        failure("VALIDATION_FAILED", () -> Input.reason("too short"));
        failure("VALIDATION_FAILED", () -> Input.reason("x".repeat(501)));
    }

    @Test void multilineTextIsBoundedAndAllowsOnlyExpectedWhitespaceControls() {
        assertEquals("", Input.multiline(null, 1000));
        assertEquals("é\nline\r\n\tend", Input.multiline(" e\u0301\nline\r\n\tend ", 1000));
        assertEquals("x".repeat(1000), Input.multiline("x".repeat(1000), 1000));
        failure("VALIDATION_FAILED", () -> Input.multiline("x".repeat(1001), 1000));
        failure("VALIDATION_FAILED", () -> Input.multiline("a\u007fb", 1000));
    }

    @Test void mailboxIdentityUsesIdnaAndCaseFoldingWithoutAliasMerging() {
        assertEquals("alex+label@xn--bcher-kva.example", Input.email(" Alex+Label@Bücher.example "));
        assertNotEquals(Input.email("a.b@example.test"), Input.email("ab@example.test"));
        assertNotEquals(Input.email("alex+label@example.test"), Input.email("alex@example.test"));
        assertEquals("a".repeat(64) + "@example.test", Input.email("a".repeat(64) + "@example.test"));
        String boundary = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);
        assertEquals(254, boundary.length());
        assertEquals(boundary, Input.email(boundary));
        failure("VALIDATION_FAILED", () -> Input.email(boundary + "x"));
        // Unicode domain expansion must be checked after IDNA as well as before it.
        String expanded = "a".repeat(64) + "@" + "é".repeat(57) + "." + "é".repeat(57) + "." + "é".repeat(57);
        failure("INVALID_EMAIL", () -> Input.email(expanded));
    }

    @ParameterizedTest @ValueSource(strings={"abc", "@example.test", "ab@", "a@@example.test", "a b@example.test",
        "é@example.test", ".a@example.test", "a.@example.test", "a..b@example.test", "a@bad_domain.test",
        "a@localhost", "a@example.test.", "a@-invalid.test", "a@.test"})
    void malformedMailboxesAreRejected(String email) { failure("INVALID_EMAIL", () -> Input.email(email)); }

    @Test void mailboxLocalPartIsBounded() {
        failure("INVALID_EMAIL", () -> Input.email("x".repeat(65) + "@example.test"));
    }

    @Test void phonesNormalizeFormattingButKeepInternationalIdentity() {
        assertEquals("+32400123456", Input.phone(" +32 (400) 123-456 "));
        assertEquals("+1234567", Input.phone("+1234567"));
        assertEquals("+123456789012345", Input.phone("+123456789012345"));
    }

    @ParameterizedTest @ValueSource(strings={"0400123456", "+01234567", "+123456", "+1234567890123456", "+32abc1234", "+ ( . - )"})
    void invalidInternationalPhonesAreRejected(String phone) {
        // Very short values fail the general length bound before phone normalization.
        failure(phone.length() < 8 ? "VALIDATION_FAILED" : "INVALID_PHONE", () -> Input.phone(phone));
    }

    @Test void onlyNamedAvailableZonesAreAccepted() {
        assertEquals(ZoneId.of("Europe/Brussels"), Input.zone("Europe/Brussels"));
        assertEquals(ZoneId.of("America/Los_Angeles"), Input.zone("America/Los_Angeles"));
        failure("INVALID_ZONE", () -> Input.zone(null));
        failure("INVALID_ZONE", () -> Input.zone("+02:00"));
        failure("INVALID_ZONE", () -> Input.zone("Unknown/Zone"));
    }

    private Intake intake(LocalDate birthDate, LocalTime birthTime) {
        return new Intake(" Alex ", " Example ", "+32 (400) 123-456", birthDate, birthTime, " Test town ", null);
    }

    @Test void intakePreservesCivilBirthDetailsAndUsesBrusselsToday() {
        var birth = LocalDate.of(2027, 2, 1);
        var intake = intake(birth, LocalTime.of(10, 30, 15));
        assertEquals("Alex", intake.firstName());
        assertEquals("Example", intake.lastName());
        assertEquals("+32400123456", intake.telephone());
        assertEquals("Test town", intake.birthplace());
        assertEquals("", intake.message());
        assertEquals(birth, intake.birthDate());
        assertEquals(LocalTime.of(10, 30, 15), intake.birthTime());
        Instant brusselsMidnight = Instant.parse("2027-01-31T23:00:00Z");
        assertDoesNotThrow(() -> intake.validateAt(brusselsMidnight));
        failure("INVALID_BIRTH_DATE", () -> intake.validateAt(brusselsMidnight.minusNanos(1000)));
        failure("VALIDATION_FAILED", () -> intake(null, LocalTime.NOON));
        failure("VALIDATION_FAILED", () -> intake(birth, null));
        failure("INVALID_BIRTH_TIME", () -> intake(birth, LocalTime.NOON.plusNanos(1)));
    }

    @Test void intakeFieldLimitsApplyBeforeScheduling() {
        var date = LocalDate.of(2000, 2, 29);
        var valid = new Intake("a".repeat(100), "b".repeat(100), "+1234567", date,
                LocalTime.MIDNIGHT, "p".repeat(200), "m".repeat(1000));
        assertEquals(date, valid.birthDate());
        failure("VALIDATION_FAILED", () -> new Intake("a".repeat(101), "Example", "+1234567", date,
                LocalTime.NOON, "Town", ""));
        failure("VALIDATION_FAILED", () -> new Intake("Alex", "b".repeat(101), "+1234567", date,
                LocalTime.NOON, "Town", ""));
        failure("VALIDATION_FAILED", () -> new Intake("Alex", "Example", "+1234567", date,
                LocalTime.NOON, "p".repeat(201), ""));
        failure("VALIDATION_FAILED", () -> new Intake("Alex", "Example", "+1234567", date,
                LocalTime.NOON, "Town", "m".repeat(1001)));
    }

    @Test void intervalsAreHalfOpenAndReserveExactly120ElapsedMinutesAcrossDst() {
        var interval = ReservationInterval.reserve(START);
        assertEquals(START, interval.start());
        assertEquals(START.plusSeconds(7200), interval.end());
        assertTrue(interval.intersects(interval));
        assertTrue(interval.intersects(ReservationInterval.reserve(START.minusSeconds(1))));
        assertTrue(interval.intersects(ReservationInterval.reserve(START.plusSeconds(7199))));
        assertFalse(interval.intersects(ReservationInterval.reserve(START.plusSeconds(7200))));
        assertFalse(interval.intersects(ReservationInterval.reserve(START.minusSeconds(7200))));
        assertFalse(interval.intersects(ReservationInterval.reserve(START.plusSeconds(7201))));
        for (String time : new String[]{"2027-03-28T00:30:00Z", "2027-10-31T00:30:00Z"}) {
            Instant start = Instant.parse(time);
            assertEquals(Duration.ofMinutes(120), Duration.between(start, ReservationInterval.reserve(start).end()));
        }
        // Arbitrary windows/blackouts share the generic interval; occupancy uses reserve().
        assertEquals(60, Duration.between(START, new ReservationInterval(START, START.plusSeconds(60)).end()).toSeconds());
    }

    @Test void invalidAndUnrepresentableIntervalsFailWithoutLeakingInput() {
        failure("INVALID_INTERVAL", () -> new ReservationInterval(null, START));
        failure("INVALID_INTERVAL", () -> new ReservationInterval(START, null));
        failure("INVALID_INTERVAL", () -> new ReservationInterval(START, START));
        failure("INVALID_INTERVAL", () -> new ReservationInterval(START, START.minusNanos(1)));
        failure("INVALID_INTERVAL", () -> ReservationInterval.reserve(null));
        failure("INVALID_INTERVAL", () -> ReservationInterval.reserve(Instant.MAX));
    }
}
