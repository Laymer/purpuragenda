# Durable booking delivery and expiry runtime

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-26T22:01:00Z.
- Current task: P45/booking-workers; branch: task/p45-booking-workers; base: main; PR URL: none yet.
- Last pushed implementation commit: d109082; uncommitted changes: five-lane runtime composition, lifecycle and actual Mailpit/restart/expiry/restore regressions.
- What works: query69 is confirmed merged; notification64 confirmed merged669bc5b after all required gates. Public context74 targets and source review pass, full final gates pending.
- What remains: current-main integration, full native/coverage/hosted/Compose/exact review; no full local milestone claimed.
- Decisions: separate maintenance port shares the same resource lock, one database time and transaction without creating artificial customer idempotency receipts. Runtime work lanes remain independent, readiness-gated, fixed-delay, restore-suppressed and gracefully stopped. No live SMTP or authentication bypass. Runtime Order14 reserved; admin HTTP worker owns15.
- Verification: maintenance target47Java (17domain/1application/29actual PostgreSQL) passes, including20-way two-adapter race, rollback, post-lock database time, maintenance fail-closed and no synthetic idempotency records.Five-lane lifecycle12 and real runtime12 cases now pass, with17domain/1application/6maintenancePG (48targetJava total,72seconds).25strict report regressions pass. Core d109082 independently source approved by workerB; full composition review/gates pending.
- Next concrete action: publish composition checkpoint, integrate current main and obtain exact review/all implemented gates.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and all worktrees/secrets/volumes preserved; stacked PR dependencies: notificationPR64 before this slice merges; current-main integration and every gate/review remain mandatory.
