package com.purpuragenda.domain;

import com.purpuragenda.domain.privacy.PrivacyReviewPolicy;
import com.purpuragenda.domain.privacy.PrivacyReviewPolicy.*;
import com.purpuragenda.domain.privacy.TemporaryExport;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrivacyReviewPolicyTest {
    private static final UUID RECORD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static Instant time(String value) { return Instant.parse(value); }
    private static Activity closed(String value) { Instant at = time(value); return new Activity(at, at, at); }
    private static Scope scope(Category category) { return new Scope(category, RECORD); }
    private static Review review(Category category, Activity activity, String now, Hold... holds) {
        return PrivacyReviewPolicy.assess(scope(category), activity, List.of(holds), time(now));
    }

    @Test void everyPurposeHasItsOwnInclusiveCivilReviewDeadline() {
        for (Category category : Category.values()) {
            int months = switch (category) {
                case INTAKE, PRIVATE_NOTE -> 12;
                case PROFILE, CONTACT, BOOKING_HISTORY, AUDIT -> 24;
                case FINANCIAL -> 120;
            };
            Activity activity = closed("2024-01-15T12:00:00Z");
            LocalDate expected = LocalDate.of(2024, 1, 15).plusMonths(months);
            Instant boundary = expected.atStartOfDay(ZoneId.of("Europe/Brussels")).toInstant();
            assertEquals(new Review(Status.NOT_DUE, expected), review(category, activity, boundary.minusNanos(1).toString()));
            Status due = category == Category.FINANCIAL ? Status.FINANCIAL_REVIEW_REQUIRED : Status.REVIEW_DUE;
            assertEquals(new Review(due, expected), review(category, activity, boundary.toString()));
            assertEquals(due, review(category, activity, boundary.plusSeconds(86400).toString()).status());
        }
    }

    @Test void usesBrusselsDateAcrossLeapDayAndSummerAndWinterOffsets() {
        assertEquals(new Review(Status.REVIEW_DUE, LocalDate.of(2025, 2, 28)),
                review(Category.INTAKE, closed("2024-02-28T23:30:00Z"), "2025-02-27T23:00:00Z"));
        assertEquals(new Review(Status.REVIEW_DUE, LocalDate.of(2025, 7, 1)),
                review(Category.PRIVATE_NOTE, closed("2024-06-30T22:30:00Z"), "2025-06-30T22:00:00Z"));
        assertEquals(new Review(Status.REVIEW_DUE, LocalDate.of(2026, 10, 27)),
                review(Category.AUDIT, closed("2024-10-27T00:30:00Z"), "2026-10-26T23:00:00Z"));
        assertEquals(LocalDate.of(2026, 3, 31),
                review(Category.CONTACT, closed("2024-03-31T01:30:00Z"), "2026-03-30T22:00:00Z").reviewOn());
    }

    @Test void latestActivityOrClosureResetsReviewIncludingUnclosedRelationships() {
        Instant created = time("2020-01-01T12:00:00Z"), updated = time("2024-02-29T12:00:00Z"), closed = time("2024-03-31T12:00:00Z");
        assertEquals(LocalDate.of(2025, 3, 31), review(Category.INTAKE, new Activity(created, updated, closed), "2025-03-01T00:00:00Z").reviewOn());
        assertEquals(LocalDate.of(2025, 3, 31), review(Category.PRIVATE_NOTE, new Activity(created, closed, updated), "2025-03-01T00:00:00Z").reviewOn());
        for (Category category : List.of(Category.INTAKE, Category.PRIVATE_NOTE, Category.PROFILE, Category.CONTACT, Category.BOOKING_HISTORY))
            assertEquals(Status.REVIEW_DUE, review(category, new Activity(created, created, null), "2040-01-01T12:00:00Z").status());
        assertEquals(Status.REVIEW_DUE, review(Category.AUDIT, new Activity(created, created, null), "2040-01-01T12:00:00Z").status());
        assertEquals(Status.FINANCIAL_REVIEW_REQUIRED, review(Category.FINANCIAL, new Activity(created, created, null), "2040-01-01T12:00:00Z").status());
    }

    @Test void holdsAreRecordAndPurposeSpecificAndInclusiveWithoutBroadRetention() {
        Activity activity = closed("2020-01-01T12:00:00Z");
        Hold hold = new Hold(scope(Category.INTAKE), "Synthetic review hold", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        assertEquals(Status.REVIEW_DUE, review(Category.INTAKE, activity, "2026-05-31T21:59:59Z", hold).status());
        assertEquals(Status.HELD, review(Category.INTAKE, activity, "2026-05-31T22:00:00Z", hold).status());
        assertEquals(Status.HELD, review(Category.INTAKE, activity, "2026-06-30T21:59:59Z", hold).status());
        assertEquals(Status.REVIEW_DUE, review(Category.INTAKE, activity, "2026-06-30T22:00:00Z", hold).status());
        assertEquals(Status.REVIEW_DUE, review(Category.PRIVATE_NOTE, activity, "2026-06-15T12:00:00Z", hold).status());
        Scope other = new Scope(Category.INTAKE, UUID.fromString("00000000-0000-0000-0000-000000000002"));
        assertEquals(Status.REVIEW_DUE, PrivacyReviewPolicy.assess(other, activity, List.of(hold), time("2026-06-15T12:00:00Z")).status());
        Hold indefinite = new Hold(scope(Category.INTAKE), "Synthetic review hold", LocalDate.of(2026, 6, 1), null);
        assertEquals(Status.HELD, review(Category.INTAKE, activity, "2040-06-15T12:00:00Z", indefinite).status());
        assertEquals("PrivacyHold[REDACTED]", indefinite.toString());
    }

    @Test void invalidDatesAndMissingEvidenceFailInsteadOfInventingRetentionDates() {
        Instant at = time("2024-01-01T12:00:00Z");
        assertThrows(DomainFailure.class, () -> new Activity(at, at.minusSeconds(1), null));
        assertThrows(DomainFailure.class, () -> new Activity(at, at, at.minusSeconds(1)));
        assertThrows(NullPointerException.class, () -> new Activity(null, at, at));
        assertThrows(NullPointerException.class, () -> new Activity(at, null, at));
        assertThrows(NullPointerException.class, () -> new Scope(null, RECORD));
        assertThrows(NullPointerException.class, () -> new Scope(Category.INTAKE, null));
        assertThrows(DomainFailure.class, () -> review(Category.INTAKE, new Activity(at, at, at.plusSeconds(1)), at.toString()));
        assertThrows(DomainFailure.class, () -> review(Category.INTAKE, new Activity(at, at.plusSeconds(1), at), at.toString()));
        assertThrows(DomainFailure.class, () -> new Hold(scope(Category.INTAKE), "short", LocalDate.of(2026, 1, 1), null));
        assertThrows(DomainFailure.class, () -> new Hold(scope(Category.INTAKE), "Synthetic reason", LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 1)));
        assertThrows(NullPointerException.class, () -> new Hold(null, "Synthetic reason", LocalDate.of(2026, 1, 1), null));
        assertThrows(NullPointerException.class, () -> new Hold(scope(Category.INTAKE), "Synthetic reason", null, null));
        assertThrows(NullPointerException.class, () -> PrivacyReviewPolicy.assess(scope(Category.INTAKE), closed(at.toString()), Arrays.asList(new Hold(scope(Category.INTAKE), "Synthetic reason", LocalDate.of(2020, 1, 1), null), null), at));
        assertThrows(NullPointerException.class, () -> PrivacyReviewPolicy.assess(null, closed(at.toString()), List.of(), at));
        assertThrows(NullPointerException.class, () -> PrivacyReviewPolicy.assess(scope(Category.INTAKE), null, List.of(), at));
        assertThrows(NullPointerException.class, () -> PrivacyReviewPolicy.assess(scope(Category.INTAKE), closed(at.toString()), null, at));
        assertThrows(NullPointerException.class, () -> PrivacyReviewPolicy.assess(scope(Category.INTAKE), closed(at.toString()), List.of(), null));
        assertThrows(NullPointerException.class, () -> new Review(null, LocalDate.of(2026, 1, 1)));
        assertThrows(NullPointerException.class, () -> new Review(Status.REVIEW_DUE, null));
    }

    @Test void temporaryExportsExpireAtOneElapsedHourIncludingBothDstTransitions() {
        for (String at : List.of("2026-03-29T00:30:00Z", "2026-10-25T00:30:00Z")) {
            Instant created = time(at); TemporaryExport export = new TemporaryExport(created);
            assertEquals(created, export.createdAt());
            assertEquals(created.plusSeconds(3600), export.expiresAt());
            assertFalse(export.availableAt(created.minusNanos(1)));
            assertTrue(export.availableAt(created));
            assertTrue(export.availableAt(created.plusSeconds(3600).minusNanos(1)));
            assertFalse(export.availableAt(created.plusSeconds(3600)));
            assertFalse(export.availableAt(created.plusSeconds(3601)));
            assertThrows(NullPointerException.class, () -> export.availableAt(null));
        }
        assertThrows(NullPointerException.class, () -> new TemporaryExport(null));
        assertThrows(DateTimeException.class, () -> new TemporaryExport(Instant.MAX));
    }
}
