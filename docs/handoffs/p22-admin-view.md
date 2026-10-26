# Persisted administrator agenda and detail query

- Agent and UTC checkpoint: Codex-worker-b, 2026-10-26T19:02:00Z.
- Current task: P22/admin-view; branch: task/p22-admin-view; base: main; PR URL: not yet created.
- Last pushed implementation commit: base ef679f255a78c9b4aa626cdbd4e0864e3fc6552a from recovery PR75; uncommitted changes: task claim and read-query implementation beginning.
- What works: merged admin commands, real password/TOTP login and persisted session authority. Recovery PR75 is source-approved and in full native/Compose/hosted verification.
- What remains: bounded read-only administrator agenda/detail application and PostgreSQL adapter, meaningful authorization/snapshot/boundary/rollback tests and full required gates. HTTP/contract/UI are later bounded slices.
- Decisions: no mutation/resource/session locks or session touches; consistent read-only snapshot and database time. Agenda uses inclusive Brussels civil dates up to31 days and stable start/id cursor pages up to100 rows. Minimal agenda excludes intake/contact; detail includes immutable booking snapshots and independent money. Enabled/enrolled/epoch/browser/liveness checks are mandatory; ordinary reads require a live persisted MFA session, without adding an unapproved detail-only step-up rule. Contract slot belongs coordinator PR74.
- Verification: this new slice not verified; predecessor targeted recovery13PG/8HTTP/9application and runtime13 pass,242Python/15Angular pass, full current-head gates pending. No stale evidence certifies this slice.
- Next concrete action: implement typed read ports/projections and real PostgreSQL authorization/MVCC/Brussels/pagination tests; run targeted and all mandatory categories, independently review published exact head.
- Recovery: preserve all worktrees, volumes, secrets and recovery6f9405b; stacked PR dependencies: PR69, PR72 customer HTTP and PR75 recovery remain unmerged unless independently confirmed.
