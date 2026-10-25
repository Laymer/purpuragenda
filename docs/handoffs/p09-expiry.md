# P09 expiry handoff

- Agent and UTC checkpoint: Codex-p09, 2026-10-25T10:20:00Z.
- Current task: P09/expiry; branch: task/p09-expiry; base: main; PR URL: not yet opened.
- Last pushed implementation commit: none; uncommitted changes: expiry primitive, transaction-bound due/effect persistence, three application/six PostgreSQL cases and mandatory report regression.
- What works: availability PR24 merged8d2ecf6 after all12 exact-head gates passed. What remains: synchronous expiry primitive with atomic financial obligations and fixed system events; creation and quotas deferred.
- Decisions: same UOW resource lock, captured database time, UUID ordering; append-only financial facts; idempotent expiry effects; no network under locks.
- Verification: domain108/application19 pass, application coverage gate pass; adapter test compilation and164 strict CI Python cases pass. Local full verification attempted; actual PostgreSQL required in CI because Docker daemon unavailable.
- Next concrete action: push tested draft, require all implemented categories and independent review before merge.
- Recovery: recovery/2026-10-25-prototype at6f9405b; stacked PR dependencies: none.
