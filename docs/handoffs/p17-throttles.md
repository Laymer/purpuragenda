# Persisted throttles and bounded cleanup

- Agent and UTC checkpoint: Claude-workerD, 2026-10-25T22:00:00Z.
- Current task: P17/throttles; branch: task/p17-throttles; base: main; PR URL: recorded on GitHub (draft).
- Last pushed implementation commit: see the draft PR head; uncommitted changes: none intended.
- What works: additive migration 013, RateLimitStore/SecurityCleanupStore ports, RequestThrottle and SecurityCleanup application services, PostgresRateLimits, PostgresSecurityCleanup, RateKey, rate key runtime settings and the inert SecurityMaintenance hook, with real PostgreSQL boundary, 20-way, rollback, lock-order and lease tests.
- What remains: HTTP exposure, scheduling the hook, and consuming throttles from protected endpoints (separate P17 slices, PR53 owns the HTTP session controller).
- Decisions: fixed windows, digest-only keys, IPv6 /64 buckets, children-before-parents grant cleanup, no decryption during cleanup.
- Verification: see docs/status.md and the draft PR for native counts and hosted checks.
- Next concrete action: independent review of the exact PR head, then coordinator merge when all checks are green.
- Recovery: worktree /private/tmp/purpuragenda-p17-throttles; no stashes created.
