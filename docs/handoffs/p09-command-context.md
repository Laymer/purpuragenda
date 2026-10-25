# Scheduling command failure boundary

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-25T13:47:49Z.
- Current task: P09/command-context; branch: task/p09-command-context; base: main; PR URL: not yet created.
- Last pushed implementation commit: none; base00cdd57; uncommitted changes: tested command runner, receipt codes, unit/PG/report regressions and evidence ready for publication.
- What works: existing transaction/savepoint, expiry and quota foundations are merged; Docker server29.6.2 is reachable.
- What remains: shared expected-failure command runner, database regression tests and exact-head full checks/review; typed authority/atomic creation depends on P15 and P17.
- Decisions: translate only a closed set of business failures inside the attempt savepoint; corrupt storage and unrecognized failures abort the entire transaction. Preserve receipt format for existing stored records.
- Verification: full native build passed108domain/57application/147adapters/3architecture,6Angular and185Python; allcoverage gates pass. Contract drift,31policy cases, live protection/docs/inventory/whitespace pass. Exact-head hosted categories pending.
- Next concrete action: publish draft PR and obtain exact-commit independent review plus allimplemented CI categories.
- Recovery: recovery/2026-10-25-prototype at6f9405b; current root checkout; stacked PR dependencies: none; native Mac verification depends on P49 tooling currently owned by worker C.
