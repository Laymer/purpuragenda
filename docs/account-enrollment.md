# Explicit optional account enrollment

The enrollment boundary requires a single-use ACCOUNT email proof bound to the requesting browser and a chosen password. Enrollment creates the existing immutable login identity, separate profile and credential transactionally; it never creates an authenticated session. A customer subsequently uses ordinary account login. No public administrator enrollment exists.

Before deriving the password, `AccountEnrollmentRequest` reserves one distributed `ACCOUNT_ENROLLMENT_IP` attempt. IPv4 uses its exact address; IPv6 uses its /64 network. At most five attempts occupy one fixed fifteen-minute window. Invalid, expired, wrong-purpose, wrong-browser and replayed proofs retain their reserved attempt; a transaction failure retains it too. Denials return a positive whole-second retry delay and never hash the password or redeem the proof. Only a committed successful enrollment releases its exact still-current window reservation. If that release fails, enrollment remains successful and the conservative charge remains. Password buffers are cleared on every path.

Email proof issuance retains its separate sixty-second address cooldown, five requests per hour per canonical email address and thirty per hour per network. The enrollment network budget does not invent an email identity from an untrusted token. A password hash alone or an enrollment response never authorizes a session.

Additive migration018 preserves existing throttle rows, widens the kind field for the explicit network kind, and adds the validated `rate_bucket_policy_v2` constraint. Readiness refuses a schema without that validated constraint. Applied migrations015–017 remain unchanged. Migration014 is separately reserved for reminders; integrations append new changesets after whichever changesets are already applied.

This checkpoint supplies backend orchestration and the persistent budget. Public enrollment issuance/redemption HTTP and its generated contract remain a separately coordinated continuation until implemented and tested; no enrollment route is claimed here. Local delivery remains captured by Mailpit, with no live customer SMTP.
