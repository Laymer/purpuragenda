# P31 backend: administrator schedule and policy commands

- Agent and UTC checkpoint: Claude-workerE, 2026-10-26T10:00:00Z.
- Current task: P31/config-commands; branch: task/p31-config-commands; base: main (current origin/main); PR URL: https://github.com/Laymer/purpuragenda/pull/60 (draft, reviewed at 327ced5).
- Last pushed implementation commit: 327ced5 (commands, adapter, tests, native-results note) plus the review follow-up (policy caps, DST/month-end mutation paths, stronger assertions, P36 note); uncommitted changes: none once pushed.
- What works: version-checked weekly replacement, date exception set/remove, blackout add/remove and policy edits. Authority comes from the locked persisted ADMIN session with fresh MFA; the commands share the resource lock, captured time, expiry reclamation and idempotency receipts. Newly conflicting future bookings (ConfigurationConflicts.evaluate inside the transaction) must be acknowledged exactly, with a reason; existing bookings and snapshots are never changed.
- What remains: HTTP and contracts (P22/P19 owner), the P31 UI, service commands (P30 scope, not part of this slice), independent review and hosted checks.
- Decisions: no migration (013 is used; 014 not needed). Config audit rows have no booking id and no outbox (outbox requires a booking); each acknowledged conflicting booking gets an audit row plus a CONFIGURATION_CONFLICT outbox event for P36/P32 follow-up. Receipt entity ids are the constant CALENDAR_ID and POLICY_ID with the new version. New additive codes CONFLICT_REVIEW_REQUIRED and INVALID_CONFIGURATION; CALENDAR_CHANGED and POLICY_CHANGED report stale versions.
- Verification: see docs/status.md for exact native results and limits.
- Next concrete action: coordinator squash-merges PR60 once hosted checks on the follow-up are green; then P22 exposes the commands and P31 UI consumes them.
- Recovery: origin/recovery/2026-10-25-prototype at 6f9405b; worktree /private/tmp/purpuragenda-p11-admin-create; stacked PR dependencies: none.
