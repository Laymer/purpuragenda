# Protected customer HTTP

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-26T15:35:00Z.
- Current task: P20/customer-http; branch: task/p20-customer-http; base: main; PR URL: none yet.
- Last pushed implementation commit: query PR69 c02ddb3; uncommitted changes: stacked HTTP claim and integration of main8f346ad, no HTTP implementation yet.
- What works: PR67 automatic AUTH/cleanup and PR68 guest proof HTTP are confirmed merged. Query PR69 passes all implemented checks at its own head; it remains unmerged pending current-main integration after enrollment/notification drafts.
- What remains: protected customer create/view/cancel/reschedule HTTP, reviewed booking context, frontend workflow and full localv1 gates.
- Decisions: preserve clean root PR69 checkout while implementing HTTP in this separate worktree. Actual mutation authority/idempotency stays in existing application commands; GET uses read-only query. Admin worker temporarily owns OpenAPI; no concurrent contract edits.
- Verification: exact query c02ddb3 passes876Java/6Angular/234Python with all coverage gates,14hosted checks,6clean localCompose checks and independent exact-head review. PR68 exact8c96 passed875Java/10Angular/234Python plus14hosted/Compose6/review and merged8f346ad at2026-10-26T15:28:32Z. HTTP checks pending.
- Next concrete action: implement strict customer request/receipt mapping and protected controller, actual PostgreSQL HTTP boundary tests, then contract generation after admin releases ownership.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and all worktrees/secrets/volumes preserved; stacked PR dependencies: query PR69 must merge before this HTTP slice; current-main checks/review remain mandatory for both.
