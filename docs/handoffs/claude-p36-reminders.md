# P36/reminders (1.0.0 local release lane)

- Agent and UTC checkpoint: Claude-lane-a, 2026-10-27T15:00:00Z.
- Current task: P36/reminders; branch: task/p36-reminders; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/82.
- Last pushed implementation commit: 3ebe5ef (DST rendered-time test and residual-risk documentation); uncommitted changes: none after the merge of main 56e9856.
- What works: durable 48-hour and 2-hour customer reminders with immutable deadlines, half-open grace windows and revision suppression. Independent exact-head review approved e98447a. Since then, main 525e4a6 (PR 89) and 56e9856 (PR 91) have been merged in with merge commits, and follow-up 3ebe5ef was added.
- What remains: all 14 hosted checks on the final head and the coordinator's re-review of the delta since e98447a, then the coordinator squash merge.
- Decisions: ledger conflicts keep main's rows and claims and re-apply this PR's own P36 rows. Migration 014 stays appended after applied 017 in the master changelog. Follow-up 3ebe5ef replaces the tautological lead-duration assertion with explicit due instants and exact rendered Brussels "Time:" lines for both reminders around 2026-03-29 and 2026-10-25, including the repeated 02:30 hour, and a leap-year month end. docs/reminders.md now records the load-to-SMTP stale-reminder window, permanent MEETING_UNAVAILABLE suppression, and that the 014 trigger forbids booking_id changes on reminder rows, which P35 erasure must account for.
- Verification: ReminderTest passes locally on JDK 25 (6 tests, zero failures/errors/skips). Python checks: 250 of 251 pass locally; the native-toolchain tar-link test fails only because the pinned Python is not installed here, and passes in hosted CI. Documentation, suite, contract and commit-policy checks pass. Docker is unavailable locally, so PostgreSQL, coverage and Compose evidence comes from hosted CI. Before 3ebe5ef, e98447a passed all 14 hosted checks.
- Next concrete action: gh pr checks 82 --watch, then hand the head to the coordinator for re-review.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and every existing branch preserved; stacked PR dependencies: none.
