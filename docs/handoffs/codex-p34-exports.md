# P34/exports

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-29T18:20:00Z.
- Current task: P34/exports; branch: task/p34-exports; base: main 4daab6b; PR URL: pending draft creation.
- Last pushed implementation commit: 3c5f192 (PR141 merged4daab6b); uncommitted changes: complete export application/store/migration/HTTP/runtime/UI/contract/tests ready for draft checkpoint.
- What works: PR141 merged after15 green checks and independent review. Export implementation has reasoned preparation, encrypted bound chunks, fresh persisted MFA, reviewed release, private download and bounded purge.
- What remains: full clean Java rerun after migration-count fixtures/connection failures, clean-revision browser receipt, exact-commit review and all-category CI; corrections/erasure/calendars/recovery/local-release gates follow.
- Decisions: keep existing fields; plaintext preview is private/no-store, encrypted snapshot expires after1h; financial facts untouched; third-party review required before release.
- Verification: new application2 tests and existing44 domain tests passed. Initial targeted command failed because domain had no selected tests; corrected the selection. Owner started Docker;14 PostgreSQL+4 actual-MFA HTTP+18 Runtime tests pass;627 Angular/build/CSP and366 Python/SDK checks pass. Real Chromium journey passed74.97s but dirty-revision receipt correctly fails. Full Java found migration-count18->19 assertions and SQLSTATE08001 in two existing suites; fixes/rerun pending. Independent working-tree review clean after2 findings fixed. PR141 run40924679767 all15 green and zero open scanning alerts.
- Next concrete action: push draft export PR; rerun failing/full Java checks, confirm exact-head independent review and all hosted gates before squash merge.
- Recovery: origin/recovery/2026-10-25-prototype at6f9405b; stacked PR dependencies: none.
