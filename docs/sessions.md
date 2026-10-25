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
`SecurityMaintenance.tick()` hook is composed but no scheduler or HTTP endpoint invokes it yet.

## HTTP edge

`POST /api/v1/session/bootstrap`, `GET /api/v1/session` and `POST /api/v1/session/logout` expose these sessions. The cookie is `__Host-purpuragenda` (Secure, HttpOnly, SameSite=Lax, Path=/, no Domain) operationally and `purpuragenda-local` (no Secure) in local, test and restore modes. Bootstrap needs only the exact configured Origin: with no live cookie it creates an anonymous guest session; with one it rotates only the CSRF proof and sets no cookie. Only digests are stored, so a reload must bootstrap again. `GET /session` returns the role and idle/absolute deadlines only through a read-only lookup that writes, locks and touches nothing, so polling cannot extend idle lifetime; unsafe requests and bootstrap do touch.

Every unsafe method on every route requires exactly one `Origin` equal to the canonical `APP_PUBLIC_ORIGIN` (`null`, repeated, wrong scheme, host or port are refused; forwarded headers are ignored), a `Sec-Fetch-Site` that is absent or `same-origin`, a live persisted session cookie and one `X-CSRF-Token` equal to the current proof. Failures are `application/problem+json` with `Cache-Control: no-store`, a fixed code (`ORIGIN_REJECTED`, `CROSS_SITE_REJECTED`, `SESSION_REQUIRED`, `CSRF_REJECTED`, `SESSION_UNAVAILABLE`) and a random correlation id; no exception text. Logout revokes the row and expires the cookie. Session-only requests lock the `app_session` row alone, never grant or administrator rows. Throttles and login/email endpoints are separate slices; until throttles land anonymous bootstrap creation is unlimited.

`X-Purpuragenda-Client-IP` (set by Caddy) is honored only when the socket peer exactly matches a literal IP in `APP_SECURITY_TRUSTED_PROXY_PEERS` (default empty; no hostnames or ranges) and exactly one valid literal IP is supplied; otherwise the socket peer is used. `Forwarded` and `X-Forwarded-*` are never read.
