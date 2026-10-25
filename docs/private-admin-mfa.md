# Private atomic MFA foundation

P16/atomic-mfa adds private application factor workflows. There is no HTTP/CLI,
public registration, session issuance or reusable authorization receipt. P17 must
supply sessions, throttles and CSRF before exposing an authentication flow. P16
recovery redemption/forced reenrollment remains a separate required slice.

Password-proven provisioning returns a private copy of the encrypted-at-rest TOTP
secret only for an enabled, unenrolled administrator. Provisioning does not enroll
or authenticate. Enrollment requires password plus a valid6-digit TOTP; it records
the accepted step, enables MFA and inserts exactly10 SHA256 recovery-code hashes
atomically. The random256-bit/43-character URL-safe plaintext codes are returned
once in a redacted, closeable value. No plaintext code enters database/audit rows.
These initial recovery codes cannot yet be redeemed in this slice.

Authentication requires enrolled+enabled state, the password and a TOTP step newer
than the last accepted step. Existing P13 verification retains30-second steps and
the inclusive +/-1 window. Two contenders for one step serialize on the same admin
row; only one may accept it. The application owns this workflow through inward
MFA transaction/crypto ports. The JDBC adapter owns direct READ_COMMITTED
connections, bounded locks/statements and a row lock before one authoritative DB
time. Enrollment/verification audit timestamps use that captured time. Contexts
reject post-transaction or cross-thread access and erase decrypted secret buffers.

Secret/recovery/enrollment values defensively copy owned arrays, redact toString
and clear their buffers on close. Application password inputs are consumed and
cleared on every outcome; unreturned provisioning copies and issued recovery
buffers are also cleared when commit fails. Callers must copy/display provisioning and recovery material only through
a future private owner interface, close values promptly and never log/serialize
these objects into general artifacts. Managed-runtime copies cannot be promised
erased. No real owner secret or credentials are provisioned by implementation/tests.

Ten real PostgreSQL cases cover independent blocked enrollment/auth races,
monotonic replay, wrong factors/states, initial recovery hashing, audit rollback,
one captured DB time, context lifetime/thread guards and malformed storage. Their
own complete report is mandatory; unrelated tests cannot substitute for it.
