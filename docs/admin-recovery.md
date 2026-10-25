# Persisted administrator recovery

P16 recovery is a private application workflow over one locked administrator row,
one captured PostgreSQL time and one transaction. P17 must provide server-owned
browser/request binding, throttles and CSRF before exposing it. No ordinary
administrator authorization, session or public registration is issued here.

An enabled administrator proves the existing password and an unused recovery code.
The transaction consumes that code, rotates the encrypted TOTP secret, clears MFA
enrollment, increments `admin_user.security_version`, revokes earlier reenrollment
grants and deletes sessions whose principal is the administrator UUID. The returned
256-bit bearer token is stored only as SHA256 and permits reenrollment for exactly
ten elapsed minutes. Its persisted purpose, administrator, browser, request and
security version must all match. Issue time is inclusive; expiry is exclusive.

An exact password/code/browser/request retry may replace the token for the same
pending recovery. It returns the same rotated TOTP secret and preserves the original
expiry and security version. The previous token stops working. A differently bound,
expired, revoked or completed retry fails. If recovery expires, another unused old
recovery code can start a new attempt; consumed/revoked codes are never reset.

Reenrollment requires the password, live grant and a valid code from the rotated
TOTP secret. It consumes the grant, records the accepted TOTP step, revokes the
previous recovery-code set, inserts ten new hashes and increments the security
version atomically. Old code rows remain as history. The new codes are returned
once in closeable, redacted values. Reenrollment does not authenticate: ordinary
access still requires fresh password/TOTP, with replay prevention.

P17 session proofs must capture `security_version` inside the factor transaction,
then consume the persisted proof while locking and rechecking the enabled,
enrolled administrator and current version. Neither a standalone MFA boolean nor
a reenrollment token is a session capability. Sessions must use the administrator
UUID as their principal and validate the same epoch so recovery also fences a
concurrent session handoff.

Migration010 is additive and preserves001–009. Grant constraints enforce purpose,
digest shapes, exact finite lifetime and recovery-code ownership. Audit events
contain only administrator identity, fixed event type and captured time. Recovery
inputs and unreturned tokens/secrets are cleared on ordinary failure paths; no
provider exception details cross the adapter boundary. Callers must close returned
values promptly and never include passwords, tokens, TOTP secrets or codes in logs
or general artifacts. Managed-runtime copies cannot be promised erased.

The mandatory real PostgreSQL suite covers independent redemption/reenrollment
races, bound retry and replay, revocation, expiry, audit rollback, restricted
access, upgrade preservation, context ownership and secret cleanup. Native/CI
results and remaining integration limits are recorded in status.md.
