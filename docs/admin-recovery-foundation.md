# Recovery isolation foundation

This foundation restricts existing private MFA components and supplies metadata
policy/types. Persisted recovery now builds on it as described in admin-recovery.md.
Public recovery endpoints, sessions and operator composition remain unfinished.

Password-only provisioning and ordinary enrollment are initial-bootstrap actions.
Both require no recovery-code rows and no immutable ADMIN_MFA_ENROLLED audit event
for that administrator. A cleared enrolled flag cannot reopen secret access.
Consumed code rows still count; even a privileged removal of code rows leaves the
immutable audit barrier. No history cleanup/reset mechanism is introduced.
Existing enrolled password+TOTP verification retains its accepted-step behavior.

ReenrollmentGrantPolicy contains only snapshots/metadata eligibility. Binding and
token digests are copied, bounded32-byte values; every representation is redacted.
Only ADMIN_REENROLLMENT is valid. Eligibility requires the same administrator,
browser/request binding, pending reenrollment, no consumed/revoked fact and one
exact10-minute elapsed window, inclusive issue time and exclusive expiry. Ordinary
authentication purpose is rejected. A constructed State is not evidence that any
grant exists or that a bearer token was verified; persistence must reload/verify
proof and current state atomically under the administrator lock.

The later recovery transaction must consume one code, preserve used/revoked
history, rotate the encrypted TOTP secret and grant only scoped reenrollment.
Exact browser/request retries must resume only that pending recovery after a lost
response; they must never recover normal administrator access. P17 supplies
server-owned browser context, sessions, CSRF and throttles before a usable public
flow. P17 must join factor/session mutation or redeem an atomically persisted
purpose proof, preserving disable/revocation races.

The additive010-admin-recovery migration follows008quota and009deferred-booking
correction. Its workflow reloads eligibility and proof under the administrator
lock; constructing these metadata values alone still conveys no authority.
