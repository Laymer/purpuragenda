# Customer booking flow

- Agent and UTC checkpoint: Claude-lane-b, 2026-10-27T07:40:00Z.
- Current task: P24/booking-flow; branch: task/p24-booking-flow; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/83.
- Last pushed implementation commit: 9f26df2 (restored main's P17 ledger row, claim reassigned to Claude-lane-b, bootstrap-failure test); uncommitted changes: merge of main68f898b (P18 password epochs PR81), ledger conflicts resolved keeping main's claims/evidence and re-applying P24 entries.
- What works: real typed email issuance/redemption, memory-only fragment capture before router startup, intake validation, authoritative plaintext review/consent, immutable submission with explicit uncertain retries, and actual booking status rendering are implemented. PR78 shared access is included byte-identically and is closed as subsumed.
- What remains: independent exact-head review, then coordinator squash merge. Customer management/account/admin UI remain separate slices; P27 admin auth UI PR87 is stacked on this branch.
- Decisions: root owns main/app routes; use generated API and shared access, no mocked backend. Capture and scrub verification fragments before router startup; keep tokens/intake in memory only. No migration or contract edits.
- Verification: merged main68f898b; Local verify-frontend passes110Angular tests and production build; handwritten coverage407/409lines393/416branches. Heads963d25b and9f26df2 passed all14hosted checks. Docker unavailable locally, so Compose/backend runs rely on hosted CI; local system Python3.14 runs249/250 CI tests, the unchanged native-toolchain tar-link case needs the pinned Python and passes hosted. Earlier sanitized failure counts (99tests/7failures, 91/1, 100/1) retained in docs/status.md.
- Next concrete action: gh pr checks 83 --watch on the new head, then request independent review.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and every existing worktree/secret/volume retained; stacked PR dependencies: none unmerged (74, 75, 72, 70, 79, 81 merged); PR87 depends on this PR.
