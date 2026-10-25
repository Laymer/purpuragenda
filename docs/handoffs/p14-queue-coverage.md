# P14 deterministic queue coverage handoff

- Agent and UTC checkpoint: Codex-p14, 2026-10-25T09:53:12Z.
- Current task: P14/queue-coverage; branch: task/p14-queue-coverage; base: main; PR URL: pending.
- Last pushed implementation commit: none; uncommitted changes: deterministic group recheck regression,11-case report guard, safe XML receipts and evidence before initial checkpoint.
- What works: queue PR19 mergede917ee3; initial queue CI passed but unrelated Mac22 rerun exposed nondeterministic89% branch coverage.
- What remains: deterministic group-recheck interleaving, safe per-module JaCoCo diagnostics and actual repeated CI proof.
- Decisions: retain strict90% adapter branches; no production lease policy change; no raw provider/test logs uploaded.
- Verification:125rootPython/docs/inventory/whitespace and all-module test-compile passed; actual PostgreSQL coverage remains CI-only; no threshold reduced.
- Next concrete action: add real two-connection interleaving regression and counter-only XML receipt upload, compile and publish small corrective PR.
- Recovery: SMTP WIP preserved locally in /tmp/purpuragenda-p14-smtp with its handoff; stacked PR dependencies: queue foundatione917ee3 merged; no SMTP changes adopted here.
