# Atomic customer creation and persisted authority

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-25T15:02:03Z.
- Current task: P09/atomic-creation; branch: task/p09-atomic-creation; base: main; PR URL: not yet created.
- Last pushed implementation commit: b4e5b85 merged39001ae in PR43; uncommitted changes: resolved transaction authorization, customer creation adapter, maintenance regression and mandatory report update.
- What works: PR43 confirmed merged39001ae after exact independent workerA approval, CI40202720748 all13checks and native108domain/78application/176adapters/3architecture,6Angular/198Python with all coverage gates. Typed customer creation and cross-midnight availability are implemented; public booking remains unwired.
- What remains: concrete locked session/grant authorization, atomic grant binding and REQUESTED effects, PostgreSQL authorization/rollback/race tests, current-main integration, exact-head review and every implemented category.
- Decisions: resource lock then one captured DB time; authority before receipt replay; derive scope from locked grant/account, preserve originalCREATE with exact-booking derivedMANAGE, no caller identity. Pending migration012 is workerB-owned; do not copy or replace it. No deployment/live SMTP/purchases.
- Verification: prior application slice gates pass as above; the new adapter is not yet verified; customer authority/race tests and actual012 integration remain. Future session integration requires actual production012 before full acceptance.
- Next concrete action: implement internal resolved scheduling command and customer creation adapter against the agreed session schema, then integrate workerB checkpoint and run real PostgreSQL tests.
- Recovery: origin/recovery/2026-10-25-prototype at6f9405b; prior P09 branches and handoff stash preserved; shared native tools/private/tmp/purpuragenda-p49-native-toolchain/.toolchain; stacked PR dependencies: P17/session-proofs migration012.
