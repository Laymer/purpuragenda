# Persistent session foundation

P17 stores anonymous, guest and administrator sessions through `SessionStore` and
`PostgresSessions`. Account storage constraints are present for P18; account
authentication and public HTTP routes are not composed by this slice. HTTP cookie
attributes, same-origin checks, request throttles and scheduled cleanup remain
separate P17 work before exposing authentication.

Only SHA256 token and CSRF digests enter PostgreSQL. A fresh anonymous session has
a random server-owned browser binding. Successful authentication rotates the
session ID, bearer token and CSRF token while retaining that binding. Returned
secrets use copied, redacted, closeable buffers; lookup, CSRF and administrator
exchange consume their borrowed secret buffers. Session references are internal
metadata and never substitute for a verified bearer cookie.

Administrator factor verification persists a hashed `ADMIN_AUTHENTICATION` proof
inside the locked password/TOTP transaction. It expires after 60 seconds and binds
the browser, request and administrator security epoch. Session exchange locks the
administrator, proof and previous session, captures database time once, rechecks
enabled/enrolled state and consumes the proof atomically with session rotation and
audit. The standalone boolean factor result cannot authorize a session. Recovery
proofs permit reenrollment only; fresh MFA is required afterward.

Changes to enabled state, password hash, TOTP secret or enrolled state advance a
monotonic database epoch. Restoring an earlier credential value cannot revive an
old proof or session. Accepted TOTP-step advances alone do not change the epoch.
Recovery revokes outstanding authentication proofs and persisted sessions in its
existing administrator transaction. An explicit recovery increment and credential
rotation produce one epoch advance.

Guest exchange acquires the shared scheduling resource, joins challenge redemption
without an inner commit, then rotates the prior anonymous/guest session. The
challenge, identity, grant and session commit together; a missing, expired or
revoked previous session leaves the proof unconsumed. The session retains the
original CREATE grant after booking creation and resolves only its derived MANAGE
grant. Revoking the parent also invalidates any direct reference to that child.

Administrator sessions expire at 15 minutes idle or eight hours absolute; account
sessions use 30 minutes and 12 hours; guest/anonymous sessions use 30 minutes and
two hours. Equality is expired. Idle touches never extend the absolute lifetime.
Elapsed time uses `Instant`, including across Brussels daylight-saving changes.

Resolve and CSRF checks are preliminary HTTP checks. Every scheduling mutation
must recheck authority under resource, authority and session locks before receipt
lookup. Session-only lookup/touch/logout acquire no later authority locks. Guest
handoff takes the resource first; administrator handoff and recovery take the
administrator first. This lock order is shared with the P09 command adapter.

Additive migration 012 preserves existing identities and recovery history. New
runtime readiness probes the required session/proof columns through the app role
without reading private rows or restricted migration metadata. Schema 011 remains
DOWN until 012 has been applied.

New PostgreSQL tests cover migration rerun, strict clock boundaries, proof/session
rollback, a 20-way proof race, recovery races, credential epochs, grant revocation
and token rotation. Their full report is mandatory; compilation alone does not
certify these scenarios.
