# Purpuragenda implementation rules

Read docs/implementation-plan.md, docs/decisions.md and docs/status.md before changes.
The approved version 1 scope is an English, single-practitioner booking application.

- Keep domain dependent only on the JDK. Application depends only on domain and its own ports.
- Frameworks, JDBC, SMTP, calendar and HTTP belong in adapters. Bootstrap composes them.
- Scheduling mutations use SchedulingUnitOfWork, one resource lock, one captured authoritative time, and a PostgreSQL transaction. Never allow overlapping 120-minute reservations, even for an administrator.
- Retain immutable service/intake/policy snapshots and independent payment facts. Manual approval does not imply payment.
- Use Europe/Brussels civil dates for business rules and Instant for elapsed time. Test DST, month ends and inclusive cutoffs.
- Never put personal data, tokens, cookies, passwords, TOTP secrets or arbitrary provider errors in logs or test artifacts. Use synthetic test data.
- No public administrator registration, test authentication bypass, checkout integration, automatic Zoom provisioning or live email in local/restore mode.
- Preserve data volumes. Never use `docker compose down -v` in operational instructions.
- Verification commands must fail for unavailable dependencies, unimplemented suites and missing reports. Record actual results and limits in docs/status.md; do not mark a milestone complete without its gates.
- Keep API contracts and operational documentation aligned with implementation. Deployment and external supplier purchases are separate manual decisions.

## Shared delivery and handoffs

- Read STATUS.md, reconcile its active handoff with the working tree and GitHub, and claim an existing task before changing code. Do not silently start overlapping work.
- STATUS.md is the single task-status authority. Retain docs/status.md as detailed implementation evidence; distinguish implemented code, passing checks and merged work.
- Update the ledger at meaningful implementation checkpoints and before handoff or interruption. Record agent, UTC time, task, branch/base/PR, last pushed commit, local changes, decisions, exact checks/results/limits, next command, recovery reference and stacked dependencies.
- Push meaningful ongoing checkpoints to a draft PR when possible; otherwise record precisely what remains local. Preserve failed-test evidence using synthetic data and sanitized reports.
- Deliver one coherent task per PR, generally 200–800 handwritten changed lines; identify generated clients and lockfiles separately. Reuse recovery code selectively and verify before adoption. Preserve applied migrations with additive fixes.
- Every PR runs all implemented automated categories, including documentation-only changes. Add CI categories progressively; missing dependencies, suites and reports fail. Never weaken coverage or integrity gates.
- Squash-merge green PRs only after required checks/reviews pass and findings are resolved. Never bypass blockers. Mark Merged only after reconciling GitHub merge state. Deployment and purchases remain manual.

Follow docs/delivery.md for approved task dependencies and parallel ownership. Every new commit includes a meaningful relevant automated test addition/update; consolidate handoff-only checkpoints. Require 90% line/branch coverage per production module and Angular, retaining 95% lines for domain/application. Independent agent review and all implemented CI categories are mandatory before coordinator squash merge.
