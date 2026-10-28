# P32/operations-http

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-28T18:39:20Z.
- Current task: P32/operations-http; branch: task/p32-operations-http; base: main 555cdaa; PR URL: pending draft creation.
- Last pushed implementation commit: e2738bf (PR138, merged 555cdaa); uncommitted changes: verified operations HTTP/SDK/tests and retained CI evidence ready for first commit.
- What works: strict private reconciliation/notification GETs and fresh-MFA retry POST, runtime wiring and contract/SDK. PR138 confirmed merged 555cdaa after 15 hosted checks and independent review.
- What remains: push draft, exact-head review and hosted gates; operations UI follows separately.
- Decisions: GET-only callbacks separate from commands; private no-store bounded projections; retry requires reason, idempotency and strong If-Match of observed attempt count. Unknown notification gets safe404 after authority; queued retry receipt is separate from job revision.
- Verification: local8 HTTP tests including4 real PostgreSQL/MFA,18 RuntimeDatabaseTest cases,82 Python contract/report tests,576 Angular/build/CSP (2338/2343 lines,2625/2762 branches) passed. New controllers37/37 lines,48/50 branches. Contract adds627 lines; SDK regenerated/verified. Initial standalone enum schema failed existing object-schema assertion; enum now inline and full rerun passed without gate changes. Independent working-tree review clean; docs/diff before commit. Prior PR138 macOS failure and successful unchanged-head rerun retained in evidence.
- Next concrete action: run docs/diff checks, commit/push task/p32-operations-http, create draft PR and confirm exact-head review plus all15 hosted checks.
- Recovery: origin/recovery/2026-10-25-prototype at6f9405b (legacy origin/recovery/2026-10-25-prototype6f9405b); stacked PR dependencies: none.

Claim released: PR139 confirmed merged 344ca309d8dc27bb2e09ef14fc4feaf1e37a1d08 after15 hosted checks, zero alerts and independent exact-head reviewbfbed26; Codex-coordinator 2026-10-28T19:01:18Z. Next claim P32/operations-ui.
