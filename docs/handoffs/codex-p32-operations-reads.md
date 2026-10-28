# P32/operations-reads

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-28T17:54:37Z.
- Current task: P32/operations-reads; branch: task/p32-operations-reads; base: main badd5ea; PR URL: pending draft creation.
- Last pushed implementation commit: 99ee0ed (PR136, merged badd5ea); uncommitted changes: operations read port/adapter/tests and ledger ready for first tested commit.
- What works: bounded administrator reconciliation and booking-notification pages with fixed safe error codes, stable UUID cursors and optional booking/state filters. PR136 confirmed merged badd5ea after 15 hosted checks and independent review.
- What remains: push draft, exact-head review and hosted gates; retry command, HTTP/UI remain separate slices.
- Decisions: reconciliation derives outstanding balances from immutable receipts/refunds and full obligations; notification projection excludes auth envelopes, tokens and recipient data. Persisted administrator authority in read-only repeatable-read snapshots, no session touch or fresh-MFA step-up.
- Verification: local14 real PostgreSQL tests,319 application tests/coverage and49 Python mandatory-report tests passed; new adapter28/28 lines and20/20 branches. InitialPG test compile failed on long fixture count assigned to int; correctedlong and complete targeted rerun passed. Independent working-tree review clean; docs/diff checks before commit. Hosted full backend coverage/all categories pending.
- Next concrete action: run docs/diff checks, commit/push task/p32-operations-reads, create draft PR and confirm exact-head independent review plus all15 hosted checks.
- Recovery: origin/recovery/2026-10-25-prototype at6f9405b (legacy origin/recovery/2026-10-25-prototype6f9405b); stacked PR dependencies: none.
