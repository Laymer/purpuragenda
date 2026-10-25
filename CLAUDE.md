# Agent instructions

Read AGENTS.md and follow its implementation and shared delivery rules. Before code changes, read STATUS.md, docs/implementation-plan.md, docs/decisions.md and docs/status.md and reconcile the ledger with the working tree and GitHub. Claim an existing task, record meaningful checkpoints and interruption handoffs, and push ongoing work to a draft PR when possible. Distinguish code, verified behavior and merged work. Preserve sanitized failed-test evidence. STATUS.md alone owns task status; docs/status.md retains detailed evidence.

Follow docs/delivery.md for approved task dependencies and parallel ownership. Every new commit includes a meaningful relevant automated test addition/update; consolidate handoff-only checkpoints. Require 90% line/branch coverage per production module and Angular, retaining 95% lines for domain/application. Independent agent review and all implemented CI categories are mandatory before coordinator squash merge.

## Standing owner authorization

The owner grants the routine permissions necessary to implement the approved plan; see the 2026-10-25 standing authorization in docs/decisions.md. Continue authorized development, tests, dependency downloads, GitHub configuration, task PRs, independent reviews and green merges without repeated conversational confirmation. Tool-enforced permissions remain binding. Deployment, DNS/ingress activation, purchases and live customer communication remain manual release decisions.
