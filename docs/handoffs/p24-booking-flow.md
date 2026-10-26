# Customer booking flow

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-26T22:28:04Z.
- Current task: P24/booking-flow; branch: task/p24-booking-flow; base: main; PR URL: none yet.
- Last pushed implementation commit: parent public-context62c2ea7 and shared-access73cb4fc; uncommitted changes: dependency integration and task claim only.
- What works: reviewed public selection, shared session/CSRF/immutable command layer, public review context and customer create/read/change APIs exist in stacked dependencies.
- What remains: real email verification, intake, authoritative review, submission/conflict recovery and resulting status UI; all current-main full gates and browser acceptance remain required.
- Decisions: root owns main/app routes; use generated API and shared access, no mocked backend. Capture and scrub verification fragments before router startup; keep tokens/intake in memory only. No migration or contract edits. Account/admin UI and management changes remain separate slices.
- Verification: no new UI implementation or passing checks claimed; dependencies have separately recorded targeted/full evidence.
- Next concrete action: integrate72's reviewed ephemeral-port fix, implement bounded real booking flow and meaningful Angular protocol/privacy/interaction tests, then actual backend preview and all gates.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and every existing worktree/secret/volume retained; stacked PR dependencies:72,74,75,78; public selection70 confirmed merged6fc7e0a.
