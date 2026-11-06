# Notification completeness audit (P36)

Audit date 2026-11-06, against main 8735d0a. It checks the notification requirements in docs/implementation-plan.md (Operations: transactional outbox) and the 2026-10-29 local 1.1.0 scope in docs/decisions.md. Each row names the code that implements the requirement and the automated test that proves it.

## Event to audience matrix

The persistence adapters emit these outbox types. `NotificationPlanner` maps each to its audience jobs, and `NotificationContent` renders the jobs from the booking facts that are current at send time.

| Event (emitted by) | Customer | Owner | Proof |
| --- | --- | --- | --- |
| REQUESTED (PostgresCustomerBooking) | Booking request received | Booking request received | BookingNotificationDatabaseTest request expansion case |
| ADMIN_CREATED (PostgresAdminBooking) | created, or confirmed, plus 48h/2h reminders | Booking created | administratorCreation case, ReminderDatabaseTest |
| APPROVED, CONFIRMED_EXTERNAL_PAYMENT (PostgresFinance) | Booking confirmed with meeting link, plus 48h/2h reminders | Booking confirmed, no link | lifecycle matrix |
| REJECTED (PostgresAdminChanges) | Booking request declined | same | lifecycle matrix |
| EXPIRED (PostgresScheduling expiry) | Booking request expired | same | lifecycle matrix |
| CANCELLED (customer and administrator changes) | Booking cancelled | same | lifecycle matrix, stale-suppression case |
| RESCHEDULED (customer and administrator changes) | Booking time changed, plus new reminders | same | rescheduling supersession cases |
| MEETING_CHANGED (PostgresAdminChanges) | Meeting link changed, with the new link | same, no link | lifecycle matrix |
| PAYMENT_RECORDED, REFUND_RECORDED (PostgresFinance) | factual notice | factual notice | lifecycle matrix, refund case |
| PAYMENT_RECONCILIATION_REQUIRED, REFUND_REQUIRED (PostgresFinance) | none | action alert | lifecycle matrix |
| CONTACT_CHANGED (PostgresContactChanges) | old and new address, no booking details | none | ContactChangesPostgresTest historical notices case |
| CONFIGURATION_CONFLICT (PostgresConfiguration) | none | none: follow-up appears in the administrator area | unknown-types case |
| any other type | none | visible FAILED job, does not block later events | unknown-types case |

The lifecycle matrix is `everyEmittedLifecycleEventDeliversItsPlannedAudiencesWithCurrentFacts` in BookingNotificationDatabaseTest. It runs on real PostgreSQL, and `scripts/ci/check-queue-report.py` makes its 9 cases mandatory (35 cases in the suite).

## Delivery requirements

| Requirement | Implementation | Proof |
| --- | --- | --- |
| Unique event/audience/template/revision jobs, each event expanded once | `notification_job` unique key, PostgresOutboxStore | twenty-expander case, second-audience rollback case |
| Claim with SKIP LOCKED and a lease, recheck current facts, send outside the transaction | PostgresNotificationQueue, BookingNotificationWorker | lease, pre-send suppression and driver-failure cases |
| Retries at 1m, 5m, 30m, 2h, 6h and 12h, then the failed queue | PostgresNotificationQueue | durable retry schedule case |
| Serialized per audience | audience advisory lock and live-lease check | audience lease and lock contention cases |
| Superseded queued work suppressed | `NotificationContent` relevance rules | supersession cases, NotificationDispatchTest |
| Stable Message-ID; a crash after SMTP acceptance resends with the same identity | job id used as the Message-ID | crash-after-SMTP case |
| No birth data, intake, private notes or tokens in email | `NotificationContent` renders only reference, service, time, state and link | forbidden-content assertions in each delivery case |
| Verification and recovery mail through encrypted, short-lived envelopes | AUTH worker (P14/P15) | AuthDeliveryDatabaseTest, AuthWorkerDatabaseTest |
| 48h and 2h customer reminders with 60m and 15m grace | migration 014, reminder relevance rules | ReminderDatabaseTest, ReminderTest |
| Operator suppression and administrator retry of failed jobs | operation_control, notification retry (P32) | operator suppression cases, NotificationRetryPostgresTest |
| Erased customers receive no queued mail | P35 identity erasure marks the booking's jobs SUPPRESSED with PRIVACY_ERASED | CustomerErasurePostgresTest identity redaction case |

## Findings

No requirement is missing from the implementation. The audit found two gaps in the proof, both closed in this change:

1. The real-PostgreSQL suite delivered only REQUESTED, ADMIN_CREATED, RESCHEDULED, CANCELLED and REFUND_RECORDED end to end. The other planned types were proven only by the planner unit test. The lifecycle matrix now covers them.
2. No test asserted that identity erasure suppresses mail still queued for the erased customer's bookings. The identity redaction case in CustomerErasurePostgresTest now does, for both audiences.

Observation for the owner, not changed here: identity erasure also rewrites the booking's already SENT or FAILED jobs to SUPPRESSED/PRIVACY_ERASED. Their delivery attempts remain, so the record that mail was sent survives, but the job state no longer shows it.

## Out of scope

- Calendar attachments in email. The 2026-10-29 owner scope defers them; P37 provides ICS downloads and Google Calendar links instead.
- COMPLETED and NO_SHOW outcomes send no notification. The plan does not list them.
- Live SMTP delivery, restore-time mail release (P47) and worker composition in operational profiles (P45) are covered by those tasks.
- Known residual risk, documented in docs/reminders.md: a change that commits between the relevance check and the SMTP send can still let one stale message out.
