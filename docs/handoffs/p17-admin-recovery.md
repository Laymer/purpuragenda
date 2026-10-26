# Restricted administrator recovery HTTP

- Agent and UTC checkpoint: Codex-worker-b, 2026-10-26T18:50:00Z.
- Current task: P17/admin-recovery; branch: task/p17-admin-recovery; base: main; PR URL: not yet created.
- Last pushed implementation commit: predecessor PR71 confirmed merged d96e0a65a51c96b2c3b7cd9b22659596cb3b1474 at18:39:37Z; uncommitted changes: claim and recovery implementation beginning. Published customer contract checkpoint1f4995b integrated append-only as801c78b.
- What works: real password/TOTP login, persisted one-use proof exchange, revocation recheck, canonical distributed attempts, guarded guest HTTP.
- What remains: restricted recovery/reenrollment HTTP, original-expiry projection, tests, contract/client, all required verification and independent review.
- Decisions: recovery requires an anonymous persisted session; server-derived purpose/browser/session binding; grants permit reenrollment only. No session issued by recovery. Exact retries rotate bearer without extending expiry. Fresh MFA required after reenrollment. No migration or live email.
- Verification: predecessor exactba8 passed921Java,11Angular,237Python, all14hosted, clean localCompose6 and independent review. Interrupted Docker run is explicitly failed and preserved separately. Targeted recovery5+facade4 application cases and actual PostgreSQL13+HTTP8 cases pass;242 strictPython and15Angular with coverage pass. Three additional HTTP boundary cases and runtime Order13 are added; current-head runtime/full gates pending. Initial two fixture SQL errors are corrected with no production workaround; sanitized receipt artifacts/p17-admin-recovery-failure/initial-fixture.json.
- Next concrete action: expose persisted original grant deadline, implement throttled restricted commands and actual PostgreSQL/HTTP tests, then contract/client generation and full checks.
- Recovery: preserve all existing worktrees, secrets, volumes and recovery6f9405b. stacked PR dependencies: P20 customer query PR69 and customer HTTP checkpoint1f4995b; neither assumed merged.
