# Task ledger

This is the single task-status authority. Read alongside the working tree and GitHub before changes. Recovered WIP is unverified prototype code on the recovery branch, not adopted implementation. Historical reports do not certify this revision. Detailed evidence remains in docs/status.md.

| Task | Advancement |
| --- | --- |
| P01 — Establish shared status, agent handoffs and initial documentation checks | Merged; https://github.com/Laymer/purpuragenda/pull/1; d4f78ac |
| P02 — Adopt the pinned toolchain, Maven modules, wrapper, architecture checks and build CI | Merged; https://github.com/Laymer/purpuragenda/pull/2; d0c5978;  |
| P03 — Adopt money, service values, intake validation and reservation intervals | Merged; https://github.com/Laymer/purpuragenda/pull/3; fc3f0fa;  |
| P04 — Complete scheduling policies, availability windows, exceptions, DST and horizon rules | Merged; https://github.com/Laymer/purpuragenda/pull/4; 06f829a;  |
| P05 — Complete immutable booking snapshots and state transitions | Merged; https://github.com/Laymer/purpuragenda/pull/5; 1470d88;  |
| P06 — Complete external receipt, refund and reconciliation calculations | Draft PR; https://github.com/Laymer/purpuragenda/pull/6; P05 merged;  |
| P07 — Adopt PostgreSQL migrations, constraints, roles and insert-only seeds | Recovered WIP; depends on P06;  |
| P08 — Complete JDBC scheduling transactions, locking, savepoints and idempotency storage | Recovered WIP; depends on P07;  |
| P09 — Complete availability, request creation, quotas and synchronous expiry reclamation | Recovered WIP; depends on P08;  |
| P10 — Complete atomic cancellation, rescheduling and appointment outcomes | Recovered WIP; depends on P09;  |
| P11 — Complete administrator creation, overrides and configuration conflict assessment | Recovered WIP; depends on P10;  |
| P12 — Establish contract generation, the Angular application shell and typed API access | Recovered WIP; depends on P11;  |
| P13 — Complete cryptographic primitives, protected key handling and RFC-vector tests | Recovered WIP; depends on P12;  |
| P14 — Complete durable SMTP delivery, encrypted authentication envelopes and fenced leases | Recovered WIP; depends on P13;  |
| P15 — Complete guest email proof, scoped management access and recovery | Recovered WIP; depends on P14;  |
| P16 — Complete private administrator bootstrap, password/TOTP authentication and recovery | Recovered WIP; depends on P15;  |
| P17 — Complete JDBC sessions, CSRF, canonical-origin checks, throttles and cleanup | Recovered WIP; depends on P16;  |
| P18 — Complete optional accounts, immutable login identity and separate profile storage | Recovered WIP; depends on P17;  |
| P19 — Complete public service and availability HTTP contracts | Recovered WIP; depends on P18;  |
| P20 — Complete protected booking creation and customer-change HTTP contracts | Recovered WIP; depends on P19;  |
| P21 — Complete administrator receipt, confirmation and refund commands and APIs | Recovered WIP; depends on P20;  |
| P22 — Complete administrator booking, service and schedule APIs with version checks | Recovered WIP; depends on P21;  |
| P23 — Complete public service selection and timezone-aware time selection | Recovered WIP; depends on P22;  |
| P24 — Complete verification, intake, review, submission and conflict recovery UI | Recovered WIP; depends on P23;  |
| P25 — Complete customer booking status, cancellation and rescheduling UI | Recovered WIP; depends on P24;  |
| P26 — Complete account enrollment, history, profile and session-recovery UI | Recovered WIP; depends on P25;  |
| P27 — Complete administrator agenda, pending queue, meeting entry and approval UI | Recovered WIP; depends on P26;  |
| P28 — Complete administrator manual creation, outcomes and explicit override UI | Recovered WIP; depends on P27; incomplete prototype |
| P29 — Complete administrator external receipt and refund UI | Recovered WIP; depends on P28;  |
| P30 — Complete service creation, editing and archiving UI | Recovered WIP; depends on P29; incomplete prototype |
| P31 — Complete weekly schedules, date exceptions, blackouts and policy controls | Recovered WIP; depends on P30; incomplete prototype |
| P32 — Complete notes, audit, customer search, reconciliation and notification follow-up | Recovered WIP; depends on P31; incomplete prototype |
| P33 — Complete verified delivery-contact changes without transferring ownership | Recovered WIP; depends on P32;  |
| P34 — Complete controlled customer exports and audited corrections | Recovered WIP; depends on P33; incomplete prototype |
| P35 — Complete reviewed erasure/anonymization and restore-safe tombstones | Recovered WIP; depends on P34; review requests exist; execution incomplete |
| P36 — Complete notification audiences, revisions, reminders, suppression and retry scenarios | Recovered WIP; depends on P35; incomplete prototype |
| P37 — Complete private iCalendar exports and safe Google Calendar actions | Recovered WIP; depends on P36;  |
| P38 — Complete API acceptance traceability and two-process integrity/quota races | Recovered WIP; depends on P37; historical partial evidence; coverage incomplete |
| P39 — Complete all 15 browser/viewport cells, accessibility scans and keyboard checks | Recovered WIP; depends on P38; historical selected Chromium/Firefox journeys only |
| P40 — Complete backend and Angular coverage enforcement with meaningful tests | Recovered WIP; depends on P39; historical Angular branch coverage 56.33%; gate failing |
| P41 — Fix layout stability and pass the nine-run browser performance budget | Recovered WIP; depends on P40; historical CLS 0.1564 exceeds 0.1 |
| P42 — Complete the specified 20-user load run and integrity receipt | Recovered WIP; depends on P41; no completed load summary |
| P43 — Complete secret, dependency, SBOM, SAST and container scanning | Not started; depends on P42;  |
| P44 — Complete authenticated and unauthenticated ZAP verification | Not started; depends on P43;  |
| P45 — Build and verify hardened local, test, operational and restore Compose profiles | Recovered WIP; depends on P44; configuration WIP; historical Docker daemon unavailable |
| P46 — Complete encrypted backup scheduling, retention and backup-age monitoring | Recovered WIP; depends on P45; partial scripts; off-host verification pending |
| P47 — Complete fresh restore, credential revocation, reconciliation and reviewed delivery release | Recovered WIP; depends on P46; historical local encrypted restore only |
| P48 — Complete aggregate CI, branch protection and immutable release-image publication | Draft PR; https://github.com/Laymer/purpuragenda/pull/8; foundation slice; release publication remains pending |
| P49 — Complete Windows/WSL, development, operations, recovery and release runbooks | Not started; depends on P48;  |
| P50 — Record owner acceptance and resolve live-use prerequisites | Blocked; requires external inputs and real-client checks |

Online payments are deferred and are not unfinished version 1 implementation.

## Active task claims

- P48/merge-protection: agent=Codex-p48; branch=task/p48-merge-protection; base=main; handoff=docs/handoffs/p48-merge-protection.md

## Active handoff

- Agent and UTC checkpoint: p48_protection, 2026-10-25T08:06:44Z.
- Current task: P48 merge-protection foundation; branch: task/p48-merge-protection; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/8.
- Last pushed implementation commit: 74aa161; uncommitted changes: readback regression test and publication handoff pending commit.
- What works: P01–P06 merged per coordinator reconciliation. What remains: tested protection configuration and per-commit test-change CI.
- Decisions: no live protection changes by this worker; coordinator applies reviewed settings. Existing deletion/non-fast-forward restrictions preserved.
- Verification: 21 delivery-policy tests passed; full build passed 108 domain + three architecture tests, no skips; domain coverage 99.64% lines / 97.75% branches; git diff --check passed. Live rules remain unapplied.
- Next concrete action: independent review PR8, coordinator applies reviewed protection and reconciles current main; require all checks before merge.
- Recovery: recovery/2026-10-25-prototype at 6f9405b; stacked PR dependencies: none.
