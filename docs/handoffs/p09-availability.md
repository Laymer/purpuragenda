# P09 availability handoff

- Agent and UTC checkpoint: Codex-p09, 2026-10-25T10:04:00Z.
- Current task: P09/availability; branch: task/p09-availability; base: main; PR URL: not yet opened.
- Last pushed implementation commit: none; uncommitted changes: public query, nine application cases, one mandatory actual-PostgreSQL case and negative report guard.
- What works: P08 persistence merged PR20, final b2e9eb2 all seven checks passed. What remains: public catalog and bounded availability application queries with meaningful tests; P09 creation, quotas and expiry deferred to separate slices.
- Decisions: no authentication assumptions; read-only captured database time; whole Brussels-day capacity; immutable output and timezone/DST boundaries.
- Verification: 108 domain and 15 application tests passed with zero skips; application69/70 lines and91/92 branches;126 CI Python cases passed; adapter test compilation passed. Actual PostgreSQL and complete implemented categories require CI because local Docker daemon is unavailable.
- Next concrete action: implement and test framework-free application query.
- Recovery: recovery/2026-10-25-prototype at6f9405b, selective adoption only; stacked PR dependencies: none.
