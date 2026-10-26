# Public booking review context

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-26T18:48:00Z.
- Current task: P19/booking-context; branch: task/p19-booking-context; base: main; PR URL: none yet.
- Last pushed implementation commit: parent customer HTTP PR72 at1f4995b; uncommitted changes: application notice/context, public controller, runtime configuration/composition, protocol/config/runtime tests and documentation.
- What works: new public context targeted17domain/4application/3HTTP/3configuration/12actual runtime cases passed. Current main includes confirmed administrator login PR71.
- What remains: public authoritative service/policy/notice context for the review step, local synthetic notice and explicit operational notice configuration; all full v1 gates remain mandatory.
- Decisions: separate worktree while earlier PRs verify. Read-only advisory context; customer creation still checks exact service/policy/notice versions atomically. Notice content is plaintext, never HTML. Recovery worker owns contract slot until release; no contract edits yet.
- Verification: targeted39Java cases passed; final focused27Java and240strictPython cases pass after unknown-mode regression. Initial selector omitted domain tests and failed rather than skipping them; claim metadata corrected from prior completed public catalog state to active context work. Parent72 native running; query69 all14hosted/Compose6/source review pass, native pending.
- Next concrete action: rerun final focused configuration checks, publish backend checkpoint and integrate recovery contract after slot release; full native/current-main/Compose/review mandatory.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and every worktree/secret/volume preserved; stacked PR dependencies: PR69 and PR72 before this slice merges.
