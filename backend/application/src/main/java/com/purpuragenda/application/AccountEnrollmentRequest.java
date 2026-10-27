package com.purpuragenda.application;

import java.util.Arrays;
import java.util.Objects;

/** Public enrollment boundary: a network budget precedes memory-hard credential work and proof redemption. */
public final class AccountEnrollmentRequest {
    public record Result(boolean enrolled, long retryAfterSeconds) {
        public Result {
            if (retryAfterSeconds < 0 || enrolled && retryAfterSeconds != 0)
                throw new IllegalArgumentException("INVALID_ENROLLMENT_RESULT");
        }
        @Override public String toString() { return "AccountEnrollmentResult[REDACTED]"; }
    }
    private final AccountEnrollment enrollment;
    private final RequestThrottle throttle;
    public AccountEnrollmentRequest(AccountEnrollment enrollment, RequestThrottle throttle) {
        this.enrollment = Objects.requireNonNull(enrollment);
        this.throttle = Objects.requireNonNull(throttle);
    }
    public Result enroll(String token, String browserHash, char[] password, String clientIp) {
        try {
            var admission = throttle.accountEnrollment(clientIp);
            if (!admission.allowed()) return new Result(false, admission.retryAfterSeconds());
            try {
                boolean enrolled = enrollment.enroll(token, browserHash, password).isPresent();
                if (enrolled) {
                    // The account transaction committed; a release failure retains the charge without hiding success.
                    try { admission.attempt().succeeded(); } catch (RuntimeException ignored) { }
                }
                return new Result(enrolled, 0);
            } finally { admission.attempt().failed(); }
        } finally { if (password != null) Arrays.fill(password, '\0'); }
    }
}
