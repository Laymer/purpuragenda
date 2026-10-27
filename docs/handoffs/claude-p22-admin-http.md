# P22/admin-http (1.0.0 local release lane)

- Agent and UTC checkpoint: Claude-lane-c, 2026-10-27T12:00:00Z.
- Current task: P22/admin-http; branch: task/p22-admin-read-http; base: main; PR URL: see scope below.
- Last pushed implementation commit: unchanged existing PR heads; uncommitted changes: none.
- What works: Codex claims released; existing draft PR heads preserved without force-push.
- What remains: #76 authority, #80 agenda/detail HTTP, #86 finance/approval HTTP; current-main integration, all hosted checks and independent exact-head review before coordinator squash merge.
- Decisions: 2026-10-27 local 1.0.0 release scope in docs/decisions.md; stacked children are retargeted to main after their base merges.
- Verification: none claimed by this claim record; evidence is recorded per PR in docs/status.md.
- Next concrete action: merge current main or the new base into the PR branch and run scripts/ci/verify-build.sh.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and every existing branch preserved; stacked PR dependencies: #76 authority, #80 agenda/detail HTTP, #86 finance/approval HTTP.
