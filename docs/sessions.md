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

## Persisted throttles and bounded cleanup

Migration 013 turns `rate_limit_bucket` into scoped fixed-window counters: email cooldown (1 per 60 seconds),
5 per hour per address, 30 per hour per client network, administrator password/MFA 5 per 15 minutes per
account and network, and availability 120 per minute per network. Keys are HMAC-SHA256 digests under the
separate rate key; raw email and addresses are never stored or logged (IPv6 shares one bucket per /64).
`PostgresRateLimits` upserts and locks buckets one statement at a time in ascending digest order, captures one
database time after all locks, and admits every charge or none, returning a whole-second ceiling Retry-After.
Window equality is expired. Administrator attempts are reserved before factor work; success releases a slot
only while the same window is still current. Storage failure throws a cause-free exception and callers fail closed.

`PostgresSecurityCleanup` and `SecurityCleanup` provide bounded hourly retention (sessions and proofs one hour
after death, grants and challenges 24 hours, throttle rows one hour after window end). Independent rows use
SKIP LOCKED. The mixed grant transaction locks scheduling resource, grants (children first), referencing sessions,
delivery jobs, then challenges; envelopes and challenge references are cleared before challenge deletion, live
fenced leases and jobs held by workers keep their challenge, and no envelope is ever decrypted. The
`SecurityMaintenance.tick()` hook is invoked hourly by RuntimeWorkers while ready; restore mode starts no background lanes.

## HTTP edge

`POST /api/v1/session/bootstrap`, `GET /api/v1/session` and `POST /api/v1/session/logout` expose these sessions. The cookie is `__Host-purpuragenda` (Secure, HttpOnly, SameSite=Lax, Path=/, no Domain) operationally and `purpuragenda-local` (no Secure) in local, test and restore modes. Bootstrap needs only the exact configured Origin: with no live cookie it creates an anonymous guest session; with one it rotates only the CSRF proof and sets no cookie. Only digests are stored, so a reload must bootstrap again. `GET /session` returns the role and idle/absolute deadlines only through a read-only lookup that writes, locks and touches nothing, so polling cannot extend idle lifetime; unsafe requests and bootstrap do touch.

Every unsafe method on every route requires exactly one `Origin` equal to the canonical `APP_PUBLIC_ORIGIN` (`null`, repeated, wrong scheme, host or port are refused; forwarded headers are ignored), a `Sec-Fetch-Site` that is absent or `same-origin`, a live persisted session cookie and one `X-CSRF-Token` equal to the current proof. Failures are `application/problem+json` with `Cache-Control: no-store`, a fixed code (`ORIGIN_REJECTED`, `CROSS_SITE_REJECTED`, `SESSION_REQUIRED`, `CSRF_REJECTED`, `SESSION_UNAVAILABLE`) and a random correlation id; no exception text. Logout revokes the row and expires the cookie. Session-only requests lock the `app_session` row alone, never grant or administrator rows. Distributed email, administrator-attempt and availability throttles are composed. Anonymous bootstrap has no separate approved request budget.

`X-Purpuragenda-Client-IP` (set by Caddy) is honored only when the socket peer exactly matches a literal IP in `APP_SECURITY_TRUSTED_PROXY_PEERS` (default empty; no hostnames or ranges) and exactly one valid literal IP is supplied; otherwise the socket peer is used. `Forwarded` and `X-Forwarded-*` are never read.

Operational notes for throttles: rotating the rate key (`APP_SECURITY_RATE_KEY_FILE`/`ID`) changes every digest, so
throttle state effectively resets and the old rows are removed by cleanup one hour after their windows end. Migration
013 deletes existing `rate_limit_bucket` rows by design: no earlier code wrote them and they are ephemeral. A
`SecurityMaintenance.tick()` skipped because another sweep is running reports `failed={overlap}`; that is expected
and not an error.

## Guest proof endpoints

`POST /api/v1/guest/create-proofs` (`{email}`) and `/guest/management-proofs` (`{reference,email}`) require a live session, the exact Origin and CSRF like every unsafe route. The distributed email limits are charged before any lookup; the reply is `202 {"accepted":true}` for known and unknown contacts alike, after a fixed minimum latency that hides delivery work. `POST /guest/redemptions` (`{token,purpose}`) consumes the proof and rotates the session in one transaction (new cookie and CSRF proof, same browser binding). Rejected proofs return `400 PROOF_INVALID`; boundary and storage failures retain the safe guard or availability codes. Bodies must be strict flat JSON of at most 2 KiB. The emailed link keeps the proof in a URL fragment so it never reaches a server log; the future frontend landing must strip the fragment and explicitly redeem by POST (P24/P26).


## Administrator login

`POST /api/v1/admin/login` accepts exactly `{email,password,totp}` behind the same Origin/session/CSRF guard. It reserves the canonical mailbox/client-network budget before password/TOTP work. The factor transaction creates a purpose/browser/request-bound persisted proof, kept entirely server-internal; normal authorization is issued only after a one-use exchange rechecks enabled/enrolled state, security epoch and the prior session. A successful response contains only role/deadlines and fresh CSRF with a rotated cookie. A valid recovery grant never substitutes for MFA. Reenrollment/recovery HTTP remains separate.

Unknown, disabled, unenrolled, wrong-password, replayed-factor and raced-revocation failures share `401 AUTHENTICATION_FAILED`. Throttle denial is429 with authoritative Retry-After, malformed bodies400, and unavailable storage503; all are no-store safe problems. An unavailable success-counter release preserves the already-committed authorized session while leaving its slot charged, so uncertainty cannot enlarge admission capacity. Passwords are never trimmed or normalized, and borrowed credential/proof/response buffers are erased. The administrator login UI is P27; these endpoints do not provide public administrator creation.


### Restricted administrator recovery HTTP

`POST /api/v1/admin/recovery` accepts exactly email, password and recoveryCode. `POST /api/v1/admin/recovery/reenrollment` accepts exactly email, password, token and totp. Both require an anonymous persisted session, exact Origin and current CSRF proof, use bounded strict JSON, and reserve the same normalized account/client-network attempt capacity as ordinary administrator login before factor work. Authenticated users first log out and bootstrap an anonymous session.

The server derives the ADMIN_REENROLLMENT purpose binding from the persisted session UUID and browser hash; clients cannot supply purpose, browser/request hashes or administrator identity. The initial response contains a restricted token, original persisted expiresAt, Base32 TOTP setup secret and provisioning URI. An exact retry rotates the token without extending its ten-minute deadline. Replacing the anonymous session invalidates that retry context; a different unused recovery code is necessary to start a new recovery.

Completion returns ten new recovery codes and requiresFreshMfa=true, never an administrator session or Set-Cookie. It consumes the restricted grant, replaces recovery codes and revokes previous administrator sessions/proofs. The enrollment TOTP step cannot immediately authenticate; ordinary login requires a fresh MFA step. Both responses are no-store. Setup material and codes belong only in volatile browser memory and the private one-time recovery display, never persistent storage, logs or analytics. These endpoints do not provide public administrator registration or a password reset.
