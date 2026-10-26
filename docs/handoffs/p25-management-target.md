# Scoped guest booking discovery

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-26T23:29:54Z.
- Current task: P25/management-target; branch: task/p25-management-target; base: main; PR URL: none yet.
- Last pushed implementation commit: none; uncommitted changes: local feature checkpoint ec38530 plus pending clean accountHTTP0509 integration, generated endpoint and current tests.
- What works: guest proof redemption and persisted scoped booking reads are merged; the verification landing has no authoritative booking target to display after a MANAGE proof.
- What remains: current-main integration, full native/hosted/Compose/coverage gates and independent review; management UI follows separately.
- Decisions: derive exactly the persisted grant-bound target, never guest history. Reuse existing complete persisted authority checks in one read-only REPEATABLE READ snapshot and captured database time. No session touch, resource lock, mutation, migration or client-supplied target. AccountHTTP84 clean contract ancestry adopted; release the extended contract at publication.
- Verification: target76Java (17domain/6application/24queryPG/8HTTPPG/5wire/16actual runtime) passes127seconds, zero failures/errors/skips.38Angular/fullbuild,255strictPython,19contract/31report regressions and regenerated SDK pass. Full category/coverage/Compose gates remain pending.
- Next concrete action: publish the scoped endpoint checkpoint, release contract ownership to finance, obtain independent review and integrate current main before full gates.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and all worktrees/secrets/volumes preserved; stacked PR dependencies: accountHTTP84/adminHTTP80/query76/password81; context74 now confirmed mergedaffe433, needs append-only main integration. Management UI follows this and booking-flow83.
