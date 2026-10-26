# Durable booking delivery and expiry runtime

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-26T19:21:20Z.
- Current task: P45/booking-workers; branch: task/p45-booking-workers; base: main; PR URL: none yet.
- Last pushed implementation commit: parent notification26a6367; uncommitted changes: shared maintenance transaction port/adapter, DueBookingExpiry application hook and six real PostgreSQL cases.
- What works: query69 is confirmed merged; notification64 confirmed merged669bc5b after all required gates. Public context74 targets and source review pass, full final gates pending.
- What remains: runtime composition of both notification audiences, bounded outbox expansion and periodic expiry under the shared scheduling transaction, restart/restore/durable delivery evidence; no full local milestone claimed.
- Decisions: separate maintenance port shares the same resource lock, one database time and transaction without creating artificial customer idempotency receipts. Runtime work lanes remain independent, readiness-gated, fixed-delay, restore-suppressed and gracefully stopped. No live SMTP or authentication bypass. Runtime Order14 reserved; admin HTTP worker owns15.
- Verification: maintenance target47Java (17domain/1application/29actual PostgreSQL) passes, including20-way two-adapter race, rollback, post-lock database time, maintenance fail-closed and no synthetic idempotency records.25strict report regressions pass; lifecycle/SMTP composition and full gates pending.
- Next concrete action: add scheduling maintenance adapter/application hook and real-PG tests, compose expiry/outbox/booking lanes with lifecycle and realSMTP/restart/restore tests.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and all worktrees/secrets/volumes preserved; stacked PR dependencies: notificationPR64 before this slice merges; current-main integration and every gate/review remain mandatory.
