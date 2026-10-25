# Task ledger

This is the single task-status authority. Read alongside the working tree and GitHub before changes. Recovered WIP is unverified prototype code on the recovery branch, not adopted implementation. Historical reports do not certify this revision. Detailed evidence remains in docs/status.md.

| Task | Advancement |
| --- | --- |
| P01 — Establish shared status, agent handoffs and initial documentation checks | Merged; https://github.com/Laymer/purpuragenda/pull/9; 15ba8b4 concurrency slice; original PR1 d4f78ac |
| P02 — Adopt the pinned toolchain, Maven modules, wrapper, architecture checks and build CI | Merged; https://github.com/Laymer/purpuragenda/pull/2; d0c5978;  |
| P03 — Adopt money, service values, intake validation and reservation intervals | Merged; https://github.com/Laymer/purpuragenda/pull/3; fc3f0fa;  |
| P04 — Complete scheduling policies, availability windows, exceptions, DST and horizon rules | Merged; https://github.com/Laymer/purpuragenda/pull/4; 06f829a;  |
| P05 — Complete immutable booking snapshots and state transitions | Merged; https://github.com/Laymer/purpuragenda/pull/5; 1470d88;  |
| P06 — Complete external receipt, refund and reconciliation calculations | Merged; https://github.com/Laymer/purpuragenda/pull/6; 8d7f62d |
| P07 — Adopt PostgreSQL migrations, constraints, roles and insert-only seeds | Draft PR; https://github.com/Laymer/purpuragenda/pull/7; dd482e3 review only; SQL not adopted |
| P08 — Complete JDBC scheduling transactions, locking, savepoints and idempotency storage | Recovered WIP; dependencies in docs/delivery.md;  |
| P09 — Complete availability, request creation, quotas and synchronous expiry reclamation | Recovered WIP; dependencies in docs/delivery.md;  |
| P10 — Complete atomic cancellation, rescheduling and appointment outcomes | Recovered WIP; dependencies in docs/delivery.md;  |
| P11 — Complete administrator creation, overrides and configuration conflict assessment | Recovered WIP; dependencies in docs/delivery.md;  |
| P12 — Establish contract generation, the Angular application shell and typed API access | Draft PR; https://github.com/Laymer/purpuragenda/pull/11; contract foundation only; shell pending; dependencies in docs/delivery.md;  |
| P13 — Complete cryptographic primitives, protected key handling and RFC-vector tests | Recovered WIP; dependencies in docs/delivery.md;  |
| P14 — Complete durable SMTP delivery, encrypted authentication envelopes and fenced leases | Recovered WIP; dependencies in docs/delivery.md;  |
| P15 — Complete guest email proof, scoped management access and recovery | Recovered WIP; dependencies in docs/delivery.md;  |
| P16 — Complete private administrator bootstrap, password/TOTP authentication and recovery | Recovered WIP; dependencies in docs/delivery.md;  |
| P17 — Complete JDBC sessions, CSRF, canonical-origin checks, throttles and cleanup | Recovered WIP; dependencies in docs/delivery.md;  |
| P18 — Complete optional accounts, immutable login identity and separate profile storage | Recovered WIP; dependencies in docs/delivery.md;  |
| P19 — Complete public service and availability HTTP contracts | Recovered WIP; dependencies in docs/delivery.md;  |
| P20 — Complete protected booking creation and customer-change HTTP contracts | Recovered WIP; dependencies in docs/delivery.md;  |
| P21 — Complete administrator receipt, confirmation and refund commands and APIs | Recovered WIP; dependencies in docs/delivery.md;  |
| P22 — Complete administrator booking, service and schedule APIs with version checks | Recovered WIP; dependencies in docs/delivery.md;  |
| P23 — Complete public service selection and timezone-aware time selection | Recovered WIP; dependencies in docs/delivery.md;  |
| P24 — Complete verification, intake, review, submission and conflict recovery UI | Recovered WIP; dependencies in docs/delivery.md;  |
| P25 — Complete customer booking status, cancellation and rescheduling UI | Recovered WIP; dependencies in docs/delivery.md;  |
| P26 — Complete account enrollment, history, profile and session-recovery UI | Recovered WIP; dependencies in docs/delivery.md;  |
| P27 — Complete administrator agenda, pending queue, meeting entry and approval UI | Recovered WIP; dependencies in docs/delivery.md;  |
| P28 — Complete administrator manual creation, outcomes and explicit override UI | Recovered WIP; dependencies in docs/delivery.md; incomplete prototype |
| P29 — Complete administrator external receipt and refund UI | Recovered WIP; dependencies in docs/delivery.md;  |
| P30 — Complete service creation, editing and archiving UI | Recovered WIP; dependencies in docs/delivery.md; incomplete prototype |
| P31 — Complete weekly schedules, date exceptions, blackouts and policy controls | Recovered WIP; dependencies in docs/delivery.md; incomplete prototype |
| P32 — Complete notes, audit, customer search, reconciliation and notification follow-up | Recovered WIP; dependencies in docs/delivery.md; incomplete prototype |
| P33 — Complete verified delivery-contact changes without transferring ownership | Recovered WIP; dependencies in docs/delivery.md;  |
| P34 — Complete controlled customer exports and audited corrections | Recovered WIP; dependencies in docs/delivery.md; incomplete prototype |
| P35 — Complete reviewed erasure/anonymization and restore-safe tombstones | Recovered WIP; dependencies in docs/delivery.md; review requests exist; execution incomplete |
| P36 — Complete notification audiences, revisions, reminders, suppression and retry scenarios | Recovered WIP; dependencies in docs/delivery.md; incomplete prototype |
| P37 — Complete private iCalendar exports and safe Google Calendar actions | Recovered WIP; dependencies in docs/delivery.md;  |
| P38 — Complete API acceptance traceability and two-process integrity/quota races | Recovered WIP; dependencies in docs/delivery.md; historical partial evidence; coverage incomplete |
| P39 — Complete all 15 browser/viewport cells, accessibility scans and keyboard checks | Recovered WIP; dependencies in docs/delivery.md; historical selected Chromium/Firefox journeys only |
| P40 — Complete backend and Angular coverage enforcement with meaningful tests | Recovered WIP; foundation merged https://github.com/Laymer/purpuragenda/pull/10 at bb7d4cf; final application-wide acceptance pending |
| P41 — Fix layout stability and pass the nine-run browser performance budget | Recovered WIP; dependencies in docs/delivery.md; historical CLS 0.1564 exceeds 0.1 |
| P42 — Complete the specified 20-user load run and integrity receipt | Recovered WIP; dependencies in docs/delivery.md; no completed load summary |
| P43 — Complete secret, dependency, SBOM, SAST and container scanning | Not started; dependencies in docs/delivery.md;  |
| P44 — Complete authenticated and unauthenticated ZAP verification | Not started; dependencies in docs/delivery.md;  |
| P45 — Build and verify hardened local, test, operational and restore Compose profiles | Recovered WIP; dependencies in docs/delivery.md; configuration WIP; historical Docker daemon unavailable |
| P46 — Complete encrypted backup scheduling, retention and backup-age monitoring | Recovered WIP; dependencies in docs/delivery.md; partial scripts; off-host verification pending |
| P47 — Complete fresh restore, credential revocation, reconciliation and reviewed delivery release | Recovered WIP; dependencies in docs/delivery.md; historical local encrypted restore only |
| P48 — Complete aggregate CI, branch protection and immutable release-image publication | Not started; dependencies in docs/delivery.md;  |
| P49 — Complete Windows/WSL, development, operations, recovery and release runbooks | Not started; dependencies in docs/delivery.md;  |
| P50 — Record owner acceptance and resolve live-use prerequisites | Blocked; requires external inputs and real-client checks |

Delivery prerequisites and concurrent task lanes are specified in docs/delivery.md. P40 coverage and P48 protection foundations are being authored in separate worktrees; their claims will be integrated with their PRs.

Online payments are deferred and are not unfinished version 1 implementation.

## Active task claims

- P12/contract-foundation: agent=Codex; branch=task/p12-contract-foundation; base=main; handoff=docs/handoffs/p12-contract-foundation.md

## Active handoff

- Agent and UTC checkpoint: Codex, 2026-10-25T08:12:00Z.
- Current task: P12/contract-foundation; branch: task/p12-contract-foundation; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/11.
- Last pushed implementation commit: 81890e8 initial tested slice; uncommitted changes: contract, generated SDK, CI and tests ready for initial draft.
- What works: P01 coordination and P40 coverage foundations merged; P48 live main rules active, verifier integration pending.
- What remains: reviewed public contract seed, deterministic generated Angular SDK and contract tests; Angular shell is next P12 slice.
- Decisions: adopt only current public service/availability seam, not recovered monolithic contract or feature implementations.
- Verification: 50 Python tests, full108+3 build, coverage99.64%/97.75%, two deterministic generations and whitespace passed; independent re-review approved after safe replacement fixes; exact-head prerequisite-integrated CI pending.
- Next concrete action: finish prerequisite integration, then implement contract generation and tests.
- Recovery: recovery/2026-10-25-prototype at 6f9405b; stacked PR dependencies: P48 foundation merge required before review.
