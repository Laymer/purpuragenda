# Public service and time selection

- Agent and UTC checkpoint: Codex-worker-c, 2026-10-26T15:30:00Z.
- Current task: P23/service-time; branch: task/p23-service-time; base: main (7216986); PR URL: not yet created.
- Last pushed implementation commit: none; uncommitted changes: claim before feature code.
- What works: merged public catalog/availability HTTP and generated CatalogService SDK; English purple Angular shell.
- What remains: lazy public service/time selection, explicit zones/offsets, current server data, loading/error/empty/retry states and meaningful frontend tests; full gates and independent review.
- Decisions: exclusively own handwritten frontend routes/home/public feature and tests; no OpenAPI/generated changes. Selection stays in memory; no fake booking submission. Shared authenticated access/intake follows coordinated P17/P20 work.
- Verification: no new feature verification yet; predecessor checks are historical only.
- Next concrete action: implement real SDK-backed /book public selection and targeted Angular tests, then all required gates and independent review.
- Recovery: origin/recovery/2026-10-25-prototype at6f9405b; existing worktrees/volumes preserved; sibling P36 draft64 remains frozen at48705cd awaiting finalmainintegration; stacked PR dependencies: mergedP19 only.
