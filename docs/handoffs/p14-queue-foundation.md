# P14 queue foundation handoff

- Agent and UTC checkpoint: Codex-p14, 2026-10-25T09:07:51Z.
- Current task: P14/queue-foundation; branch: task/p14-queue-foundation; base: main; PR URL: pending.
- Last pushed implementation commit: none yet; local reviewed implementation9c3faff; uncommitted changes: queue port/JDBC implementation, ten real database tests, unit/report guards and evidence before first checkpoint.
- What works: P12 shell merged c1151b2 with actual-container and Angular CI; crypto/migration/security/inventory foundations merged.
- What remains: minimal queue port/JDBC leases/retries and mandatory real PostgreSQL concurrency tests; SMTP/auth envelopes later P14 slice.
- Decisions: explicit READ_COMMITTED; pg_stat_activity barrier before expiry change; no P36 event expansion/reminders; no network calls in queue transaction; per-booking/audience advisory lock plus database exclusion; stale completion cannot record attempts.
- Verification: test-compile all7modules passed;101rootPython passed; unchanged full build passes6Angular/108domain/2application/11security/2image/1storage then fails mandatory PostgreSQL startup (Docker unavailable), zero skips; no adapter/integrity coverage claim. Application3/3lines no branches.
- Next concrete action: verify integrated P08 core compilation/inventory, push draft and require exact-head real PostgreSQL CI.
- Recovery: recovery/2026-10-25-prototype at6f9405b; stacked PR dependencies: P12/P13/P07 merged; P08 transaction-core6c1f96f merged and integrated; booking/policy persistence owned separately.
