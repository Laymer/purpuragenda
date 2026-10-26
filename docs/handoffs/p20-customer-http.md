# Protected customer HTTP

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-26T15:35:00Z.
- Current task: P20/customer-http; branch: task/p20-customer-http; base: main; PR URL: none yet.
- Last pushed implementation commit: query PR69 c02ddb3; uncommitted changes: customer controller/strict wire parser, real PostgreSQL HTTP and runtime tests implemented after local integrationf886204; publication pending.
- What works: PR67 automatic AUTH/cleanup and PR68 guest proof HTTP are confirmed merged. Query PR69 passes all implemented checks at its own head; it remains unmerged pending current-main integration after enrollment/notification drafts.
- What remains: protected customer create/view/cancel/reschedule HTTP, reviewed booking context, frontend workflow and full localv1 gates.
- Decisions: preserve clean root PR69 checkout while implementing HTTP in this separate worktree. Actual mutation authority/idempotency stays in existing application commands; GET uses read-only query. Admin worker temporarily owns OpenAPI; no concurrent contract edits.
- Verification: exact query c02ddb3 passes876Java/6Angular/234Python with all coverage gates,14hosted checks,6clean localCompose checks and independent exact-head review. PR68 exact8c96 passed875Java/10Angular/234Python plus14hosted/Compose6/review and merged8f346ad at2026-10-26T15:28:32Z. HTTP targeted5wire/3controller/5actualPGHTTP plus11runtimePG cases pass, including20wayrace and actualHTTP proof/create/read/move/cancel.236Python passed before the latest strict-time regression; full checks and contract/client extension pending.
- Next concrete action: publish tested backend checkpoint; integrate administrator contract checkpoint after ownership release, extend/regenerate customer contracts, and run all implemented categories plus exact review.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and all worktrees/secrets/volumes preserved; stacked PR dependencies: query PR69 must merge before this HTTP slice; current-main checks/review remain mandatory for both.
