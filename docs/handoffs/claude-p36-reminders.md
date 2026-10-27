# P36/reminders (1.0.0 local release lane)

- Agent and UTC checkpoint: Claude-lane-a, 2026-10-27T12:00:00Z.
- Current task: P36/reminders; branch: task/p36-reminders; base: main; PR URL: see scope below.
- Last pushed implementation commit: unchanged existing PR heads; uncommitted changes: none.
- What works: Codex claims released; existing draft PR heads preserved without force-push.
- What remains: #82 durable reminders; current-main integration, all hosted checks and independent exact-head review before coordinator squash merge.
- Decisions: 2026-10-27 local 1.0.0 release scope in docs/decisions.md; stacked children are retargeted to main after their base merges.
- Verification: none claimed by this claim record; evidence is recorded per PR in docs/status.md.
- Next concrete action: merge current main or the new base into the PR branch and run scripts/ci/verify-build.sh.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and every existing branch preserved; stacked PR dependencies: #82 durable reminders.
