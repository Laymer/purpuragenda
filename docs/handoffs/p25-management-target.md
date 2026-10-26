# Scoped guest booking discovery

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-26T23:10:58Z.
- Current task: P25/management-target; branch: task/p25-management-target; base: main; PR URL: none yet.
- Last pushed implementation commit: none; uncommitted changes: guest-scope query, application projection, HTTP wiring and relevant tests on fresh main538e335.
- What works: guest proof redemption and persisted scoped booking reads are merged; the verification landing has no authoritative booking target to display after a MANAGE proof.
- What remains: one guest-session-derived booking lookup, application projection, protected HTTP/typed contract and real PostgreSQL authorization/boundary tests; full gates and independent review.
- Decisions: derive exactly the persisted grant-bound target, never guest history. Reuse existing complete persisted authority checks in one read-only REPEATABLE READ snapshot and captured database time. No session touch, resource lock, mutation, migration or client-supplied target. Wait for account HTTP contract slot before contract generation.
- Verification: target17domain/6application cases pass; new query/HTTP/runtime source and tests await compiled PostgreSQL verification. No full gate or browser claim.
- Next concrete action: adopt the clean accountHTTP0509 contract ancestry, add the scoped endpoint/client contract, run targeted PostgreSQL/HTTP/runtime and all exact-head gates.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and all worktrees/secrets/volumes preserved; stacked PR dependencies: none for backend slice, management UI follows this and booking-flow83.
