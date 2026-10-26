# Administrator finance HTTP

This implementation slice exposes the existing independent-money application commands. Publication, current-main integration, contract generation and complete verification remain pending; this document describes the implemented controller rather than claiming release acceptance.

All routes are POST under `/api/v1/admin/bookings/{bookingId}` and require a same-origin persisted administrator session, CSRF and an Idempotency-Key. The finance transaction independently rechecks enabled/enrolled status, security epoch, browser/session binding and fresh MFA within ten minutes under the scheduling resource lock. A guard snapshot alone cannot authorize money. Revocation is checked before an idempotent result is replayed.

| Suffix | Exact JSON fields | Booking precondition | Successful response |
| --- | --- | --- | --- |
| `/receipts` | amountMinor, currency, method, reference, verifiedAt | None | 201 bookingId, paymentEntryId, bookingVersion |
| `/approve` | meetingUrl | Strong numeric If-Match | 200 bookingId, version; numeric ETag |
| `/confirm-external-payment` | meetingUrl | Strong numeric If-Match | 200 bookingId, version; numeric ETag |
| `/refunds` | receiptId, amountMinor, currency, method, reference, verifiedAt | None | 201 bookingId, paymentEntryId, bookingVersion |
| `/refund-requirements` | receiptId, reason | None | 200 bookingId, receiptId, bookingVersion |

Actual-money facts apply to terminal bookings too and do not advance the booking command version. These three routes reject If-Match rather than silently promise a booking-version condition they cannot enforce. Their response deliberately distinguishes the payment-entry identifier from the booking identifier. Amounts are integer minor units in their actual currency; no conversion is inferred. verifiedAt is an offset timestamp with no finer than microsecond precision and cannot be later than the captured transaction time.

Manual approval preserves payment status. External-payment confirmation separately requires sufficient matching-currency net receipts, a live pending hold and a valid allowed meeting URL. A valid receipt remains committed if a later confirmation fails. Late receipts never revive terminal bookings and create reconciliation/refund follow-up. Refunds link one actual receipt, use its currency and cannot exceed its remaining balance. Explicit refund requirements are idempotent per receipt.

Bodies are bounded strict JSON; unknown, missing and duplicate fields fail. Missing confirmation versions return VERSION_REQUIRED428, malformed versions INVALID_REQUEST400, stale versions STALE_VERSION412. Invalid payment/refund/meeting business input returns400; remaining business conflicts return409. Missing authority returns SESSION_REQUIRED401, insufficient fresh MFA AUTHENTICATION_REQUIRED403, wrong role ADMIN_ACCESS_REQUIRED403, and authorized unknown bookings BOOKING_NOT_FOUND404. Unexpected transaction failures return safe retryable TEMPORARILY_UNAVAILABLE503. Every response is no-store and contains no arbitrary provider error.

CPU wire tests cover mappings, boundaries and safe errors. A separate PostgreSQL HTTP suite uses real password/TOTP login and tests independent facts, revocation after guard evaluation, transaction rollback and twenty-way idempotency/refund races. Runtime Order17 is reserved for this slice. Exact executed results and remaining gates belong in its handoff and docs/status.md.

The finance authority check accepts a legitimate concurrent session activity update after its resource time was captured and before its session row lock. It still uses that one command time for issued, idle, absolute expiry and fresh-MFA checks. Read-only repeatable-read queries retain their own nonfuture activity bound because they observe one consistent snapshot. A separate required P31 follow-up remains in PostgresConfiguration, whose existing mutable last-seen upper bound needs its own deterministic interleaving regression and correction while retaining an explicit nonfuture authenticated-at bound; this finance change does not claim all mutation adapters are fixed.
