package com.purpuragenda.domain.privacy;

import com.purpuragenda.domain.customer.Input;
import java.time.*;
import java.util.*;
import static com.purpuragenda.domain.DomainFailure.require;

/** Review deadlines only. A due review never authorizes deletion or overrides financial preservation. */
public final class PrivacyReviewPolicy {
    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");
    private PrivacyReviewPolicy() { }

    public enum Category {
        INTAKE(12), PRIVATE_NOTE(12), PROFILE(24), CONTACT(24), BOOKING_HISTORY(24), AUDIT(24), FINANCIAL(120);
        private final int months;
        Category(int months) { this.months = months; }
    }
    public enum Status { NOT_DUE, HELD, REVIEW_DUE, FINANCIAL_REVIEW_REQUIRED }

    /** Stable record scope prevents a financial or other hold from retaining unrelated customer data. */
    public record Scope(Category category, UUID recordId) {
        public Scope { Objects.requireNonNull(category); Objects.requireNonNull(recordId); }
    }

    /** All dates are evidence supplied by the owning workflow, never inferred from the current clock. */
    public record Activity(Instant createdAt, Instant lastActivityAt, Instant closedAt) {
        public Activity {
            Objects.requireNonNull(createdAt); Objects.requireNonNull(lastActivityAt);
            require(!lastActivityAt.isBefore(createdAt), "INVALID_PRIVACY_ACTIVITY");
            require(closedAt == null || !closedAt.isBefore(createdAt), "INVALID_PRIVACY_ACTIVITY");
        }
        private Instant anchor() {
            return closedAt != null && closedAt.isAfter(lastActivityAt) ? closedAt : lastActivityAt;
        }
    }

    /** A hold applies through its inclusive Brussels end date; null means no automatic end. */
    public record Hold(Scope scope, String reason, LocalDate startsOn, LocalDate through) {
        public Hold {
            Objects.requireNonNull(scope); reason = Input.reason(reason); Objects.requireNonNull(startsOn);
            require(through == null || !through.isBefore(startsOn), "INVALID_PRIVACY_HOLD");
        }
        private boolean applies(Scope target, LocalDate today) {
            return scope.equals(target) && !today.isBefore(startsOn) && (through == null || !today.isAfter(through));
        }
        @Override public String toString() { return "PrivacyHold[REDACTED]"; }
    }

    public record Review(Status status, LocalDate reviewOn) {
        public Review { Objects.requireNonNull(status); Objects.requireNonNull(reviewOn); }
    }

    /**
     * Customer records use the latest activity/closure date, including records without closure.
     * Audit and financial facts have their own activity dates, independent of customer closure.
     * The proposed financial interval is a review cue; accounting confirmation is still required.
     */
    public static Review assess(Scope target, Activity activity, Collection<Hold> holds, Instant now) {
        Objects.requireNonNull(target); Objects.requireNonNull(activity); Objects.requireNonNull(holds); Objects.requireNonNull(now);
        require(!activity.anchor().isAfter(now), "INVALID_PRIVACY_ACTIVITY");
        LocalDate today = now.atZone(BRUSSELS).toLocalDate();
        LocalDate due = activity.anchor().atZone(BRUSSELS).toLocalDate().plusMonths(target.category().months);
        // Validate every hold even if an earlier one matches, so malformed input cannot be hidden by ordering.
        var checked = List.copyOf(holds);
        if (checked.stream().anyMatch(hold -> hold.applies(target, today))) return new Review(Status.HELD, due);
        if (today.isBefore(due)) return new Review(Status.NOT_DUE, due);
        return new Review(target.category() == Category.FINANCIAL ? Status.FINANCIAL_REVIEW_REQUIRED : Status.REVIEW_DUE, due);
    }
}
