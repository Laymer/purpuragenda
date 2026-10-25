# Task ledger

This is the single task-status authority. Read alongside the working tree and GitHub before changes. Recovered WIP is unverified prototype code on the recovery branch, not adopted implementation. Historical reports do not certify this revision. Detailed evidence remains in docs/status.md.

| Task | Advancement |
| --- | --- |
| P01 — Establish shared status, agent handoffs and initial documentation checks | Merged; https://github.com/Laymer/purpuragenda/pull/1; d4f78ac |
| P02 — Adopt the pinned toolchain, Maven modules, wrapper, architecture checks and build CI | Merged; https://github.com/Laymer/purpuragenda/pull/2; d0c5978;  |
| P03 — Adopt money, service values, intake validation and reservation intervals | Draft PR; https://github.com/Laymer/purpuragenda/pull/3; P02 merged;  |
| P04 — Complete scheduling policies, availability windows, exceptions, DST and horizon rules | Recovered WIP; depends on P03;  |
| P05 — Complete immutable booking snapshots and state transitions | Recovered WIP; depends on P04;  |
| P06 — Complete external receipt, refund and reconciliation calculations | Recovered WIP; depends on P05;  |
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
| P48 — Complete aggregate CI, branch protection and immutable release-image publication | Not started; depends on P47;  |
| P49 — Complete Windows/WSL, development, operations, recovery and release runbooks | Not started; depends on P48;  |
| P50 — Record owner acceptance and resolve live-use prerequisites | Blocked; requires external inputs and real-client checks |

Online payments are deferred and are not unfinished version 1 implementation.

## Active handoff

- Agent and UTC checkpoint: Codex, 2026-10-25T06:30:00Z.
- Current task: P03; branch: task/p03-domain-values; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/3.
- Last pushed implementation commit: 7a494919dfbef5fbc52eb505b1168c7e03d3fadf; uncommitted changes: ledger checkpoint only before this commit; worktree /tmp/purpuragenda-p03.
- What works: P02 local build passed; P02 exact-head CI passed and merged at d0c5978. What remains: inspect exact-head CI/reviews and squash-merge when green.
- Decisions: retain generic half-open intervals for blackouts; reserve factory always creates exactly 120 elapsed minutes. No scheduling policy/state code adopted yet.
- Verification: bash scripts/ci/verify-build.sh passed 44 domain tests and three architecture tests; domain 54/55 lines (98.18%) and 91/92 branches (98.91%) exceed 95%/90%. Coverage XML required. git diff --check passed. Corrected fixture failures retained in docs/status.md.
- Next concrete action: gh pr checks 3; gh pr view 3 --json headRefOid,statusCheckRollup,reviews,reviewDecision.
- Recovery: recovery/2026-10-25-prototype at 6f9405b. stacked PR dependencies: none; P03 rebased onto merged P02 before first push.
