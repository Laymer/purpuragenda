# Public booking review context

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-26T22:06:00Z.
- Current task: P19/booking-context; branch: task/p19-booking-context; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/74.
- Last pushed implementation commit: 876c77f; uncommitted changes: append-only customer72/account73 integration; reviewed context/snapshot production unchanged.
- What works: new public context targeted17domain/4application/3HTTP/3configuration/12actual runtime cases passed. Current main includes confirmed administrator login PR71 and customer query PR69.
- What remains: final current-main full gates, exact review and clean Compose after72/75 merge; public context/local notice/operational notice configuration are implemented.
- Decisions: separate worktree while earlier PRs verify. Read-only advisory context; customer creation still checks exact service/policy/notice versions atomically. Notice content is plaintext, never HTML. Recovery checkpoint ef679f2 integrated; contract released; workerB owns admin HTTP contract until its publication. Service wire shape reuses the existing typed Service including active=true.
- Verification: targeted39Java cases passed; final focused27Java and240strictPython cases pass after unknown-mode regression. Initial selector omitted domain tests and failed rather than skipping them; claim metadata corrected from prior completed public catalog state to active context work. Integrated40Java,17Angular,243Python and16contract/regeneration cases pass; parent72 baseline958Java/14Angular/240Python passes. Query69 confirmed merged after all required gates.
- Next concrete action: publish integration retaining query22/HTTP6/account26/recovery11/public3/runtime13 guards; final gates after72/75 merge.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and every worktree/secret/volume preserved; stacked PR dependencies: PR69 confirmed merged; PR72 and recovery PR75 must merge before this slice.
