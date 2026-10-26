# P31 backend: administrator schedule and policy commands

- Agent and UTC checkpoint: Claude-workerE, 2026-10-26T10:00:00Z.
- Current task: P31/config-commands; branch: task/p31-config-commands; base: main (current origin/main); PR URL: recorded on the draft PR once opened.
- Last pushed implementation commit: none yet; uncommitted changes: ConfigurationUnitOfWork, AdministratorConfiguration, PostgresConfiguration, tests and documentation.
- What works: version-checked weekly replacement, date exception set/remove, blackout add/remove and policy edits. Authority comes from the locked persisted ADMIN session with fresh MFA; the commands share the resource lock, captured time, expiry reclamation and idempotency receipts. Newly conflicting future bookings (ConfigurationConflicts.evaluate inside the transaction) must be acknowledged exactly, with a reason; existing bookings and snapshots are never changed.
- What remains: HTTP and contracts (P22/P19 owner), the P31 UI, service commands (P30 scope, not part of this slice), independent review and hosted checks.
- Decisions: no migration (013 is used; 014 not needed). Config audit rows have no booking id and no outbox (outbox requires a booking); each acknowledged conflicting booking gets an audit row plus a CONFIGURATION_CONFLICT outbox event for P36/P32 follow-up. Receipt entity ids are the constant CALENDAR_ID and POLICY_ID with the new version. New additive codes CONFLICT_REVIEW_REQUIRED and INVALID_CONFIGURATION; CALENDAR_CHANGED and POLICY_CHANGED report stale versions.
- Verification: see docs/status.md for exact native results and limits.
- Next concrete action: run full native verification, push the draft PR and wait for hosted checks.
- Recovery: origin/recovery/2026-10-25-prototype at 6f9405b; worktree /private/tmp/purpuragenda-p11-admin-create; stacked PR dependencies: none.
