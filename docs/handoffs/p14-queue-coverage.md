# P14 deterministic queue coverage handoff

- Agent and UTC checkpoint: Codex-p14, 2026-10-25T10:02:59Z.
- Current task: P14/queue-coverage; branch: task/p14-queue-coverage; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/23.
- Last pushed implementation commit: bc52d5a; uncommitted changes: prerequisite merge reconciliation and exact green counter evidence.
- What works: queue PR19 mergede917ee3; initial queue CI passed but unrelated Mac22 rerun exposed nondeterministic89% branch coverage.
- What remains: deterministic group-recheck interleaving, safe per-module JaCoCo diagnostics and actual repeated CI proof.
- Decisions: retain strict90% adapter branches; no production lease policy change; no raw provider/test logs uploaded.
- Verification: initial exact-head CI40182746590 atbc52d5a all7jobs passed, real queue11cases; adapter counters202/205lines151/166branches;125rootPython/docs/inventory/whitespace and compilation passed. Current-main integration/repeated CI remain required; no threshold reduced.
- Next concrete action: integrate current main including Mac bootstrap, verify all local gates and require repeated exact-head CI before coordinator merge.
- Recovery: SMTP WIP preserved locally in /tmp/purpuragenda-p14-smtp with its handoff; stacked PR dependencies: queue foundatione917ee3 merged; no SMTP changes adopted here; P08 persistence98c7535 and Mac bootstrap4233afd integrated with all mandatory jobs.
