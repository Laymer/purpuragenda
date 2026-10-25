# Task ledger

This is the single task-status authority. Read alongside the working tree and GitHub before changes. Recovered WIP is unverified prototype code on the recovery branch, not adopted implementation. Historical reports do not certify this revision. Detailed evidence remains in docs/status.md.

| Task | Advancement |
| --- | --- |
| P01 — Establish shared status, agent handoffs and initial documentation checks | Merged; https://github.com/Laymer/purpuragenda/pull/14; standing authorization04282fa; prior concurrency PR9 at15ba8b4; handoff-integrity follow-up https://github.com/Laymer/purpuragenda/pull/37 |
| P02 — Adopt the pinned toolchain, Maven modules, wrapper, architecture checks and build CI | Merged; https://github.com/Laymer/purpuragenda/pull/2; d0c5978;  |
| P03 — Adopt money, service values, intake validation and reservation intervals | Merged; https://github.com/Laymer/purpuragenda/pull/3; fc3f0fa;  |
| P04 — Complete scheduling policies, availability windows, exceptions, DST and horizon rules | Merged; https://github.com/Laymer/purpuragenda/pull/4; 06f829a;  |
| P05 — Complete immutable booking snapshots and state transitions | Merged; https://github.com/Laymer/purpuragenda/pull/5; 1470d88;  |
| P06 — Complete external receipt, refund and reconciliation calculations | Merged; https://github.com/Laymer/purpuragenda/pull/6; 8d7f62d |
| P07 — Adopt PostgreSQL migrations, constraints, roles and insert-only seeds | Merged; https://github.com/Laymer/purpuragenda/pull/7; ff3b33b; actual-container CI passed |
| P08 — Complete JDBC scheduling transactions, locking, savepoints and idempotency storage | Merged; https://github.com/Laymer/purpuragenda/pull/35 at5909901; additive009 correction; https://github.com/Laymer/purpuragenda/pull/20 at98c7535; scheduling storage scope complete; transaction core PR18 at6c1f96f; all seven final checks passed |
| P09 — Complete availability, request creation, quotas and synchronous expiry reclamation | In progress; availability PR24, expiry PR26 and quota https://github.com/Laymer/purpuragenda/pull/28 atf72bae2 merged; creation remains |
| P10 — Complete atomic cancellation, rescheduling and appointment outcomes | Recovered WIP; dependencies in docs/delivery.md;  |
| P11 — Complete administrator creation, overrides and configuration conflict assessment | Recovered WIP; dependencies in docs/delivery.md;  |
| P12 — Establish contract generation, the Angular application shell and typed API access | Merged; https://github.com/Laymer/purpuragenda/pull/16; c1151b2; contract foundation PR11 bc64f01 |
| P13 — Complete cryptographic primitives, protected key handling and RFC-vector tests | Merged; https://github.com/Laymer/purpuragenda/pull/12; 34836ed |
| P14 — Complete durable SMTP delivery, encrypted authentication envelopes and fenced leases | In progress; queue/SMTP/auth persistence and worker https://github.com/Laymer/purpuragenda/pull/36 merged526290f; heartbeat/fenced callbacks/expiry sweep verified; operational composition remains P45 |
| P15 — Complete guest email proof, scoped management access and recovery | Recovered WIP; dependencies in docs/delivery.md;  |
| P16 — Complete private administrator bootstrap, password/TOTP authentication and recovery | In progress; bootstrap PR27 and MFA PR29 merged; recovery-isolation foundation https://github.com/Laymer/purpuragenda/pull/33 merged79dff5a; persisted recovery/reenrollment remains |
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
| P43 — Complete secret, dependency, SBOM, SAST and container scanning | Recovered WIP; SAST merged https://github.com/Laymer/purpuragenda/pull/21 at0454505 with12 green checks; secret/dependency foundations merged; full SBOM/container/config pending |
| P44 — Complete authenticated and unauthenticated ZAP verification | Not started; dependencies in docs/delivery.md;  |
| P45 — Build and verify hardened local, test, operational and restore Compose profiles | Recovered WIP; dependencies in docs/delivery.md; configuration WIP; historical Docker daemon unavailable |
| P46 — Complete encrypted backup scheduling, retention and backup-age monitoring | Recovered WIP; dependencies in docs/delivery.md; partial scripts; off-host verification pending |
| P47 — Complete fresh restore, credential revocation, reconciliation and reviewed delivery release | Recovered WIP; dependencies in docs/delivery.md; historical local encrypted restore only |
| P48 — Complete aggregate CI, branch protection and immutable release-image publication | Recovered WIP; protection foundation merged PR8 0c3ae04; suite inventory merged https://github.com/Laymer/purpuragenda/pull/15 at f3a4841; release publication pending |
| P49 — Complete Windows/WSL, development, operations, recovery and release runbooks | Draft PR; native-toolchain https://github.com/Laymer/purpuragenda/pull/38; Windows preflight PR25 merged7d7f19e; Mac bootstrap PR22 merged4233afd with real host-prerequisite acceptance; actual WSL/full runbooks pending |
| P50 — Record owner acceptance and resolve live-use prerequisites | Blocked; requires real-client/host acceptance and external settings; Intel i9 macOS MacBook host, automatic Tailscale setup requested, public medinavie.com ingress unprovisioned, no existing public Linux server; registrar Squarespace confirmed read-only, Google Domains nameservers; current Linux computer proposed encrypted off-host backup; existing Zoom/SMTP settings later |

Delivery prerequisites and concurrent task lanes are specified in docs/delivery.md. P40 coverage foundation merged at bb7d4cf; P48 protection foundation merged at0c3ae04; P43 secret scanning foundation merged at8f5ed62; P48 suite inventory merged at f3a4841. Full P40 application-wide acceptance and P48 release publication remain pending.

Online payments are deferred and are not unfinished version 1 implementation.

## Active task claims

- P49/native-toolchain: agent=Codex-worker-c; branch=task/p49-native-toolchain; base=main; handoff=docs/handoffs/p49-native-toolchain.md

## Active handoff

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-25T13:04:12Z.
- Current task: P01/handoff-integrity; branch: task/p01-handoff-integrity; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/37.
- Last pushed implementation commit: fa618f0 (P14, merged526290f) and078214e (handoff guard); uncommitted changes: tested current-main merge and final ledger/evidence pending checkpoint publication.
- What works: P08 correction PR35 merged5909901 and P14 worker PR36 merged526290f after exact-head independent review and all13checks. Claimed handoff ancestry links are rejected before the claimed file is opened.
- What remains: P15 guest proof/redemption, P16 persisted recovery/reenrollment and P17 access protections; P09 creation remains. P14 operational key/profile/runner composition remains P45; booking event expansion/reminders remain P36. No active implementation owners remain.
- Decisions: preserve applied001–008 plus additive009; recovery010 remains reserved; leave P50 PR31 paused; no deployment or liveSMTP. Static claimed-handoff link validation is not a race-proof guarantee for all document reads.
- Verification: PR36 exactfa618f0 CI40195543498 all13green,10realPG/SMTP worker cases,108domain/52application/142adapters/3architecture without skips;183rootPython/31policy and allcoverage gates pass.19local ledger cases/docs/inventory/whitespace pass. Current checkpoint requires fresh combined CI and independent review; local platform limits remain recorded.
- Next concrete action: claim P15/email-proof and P16/recovery-persistence as independent slices, serializing schema changes with010 reserved for recovery; keep public flows behind P17 protections before booking creation/HTTP integration.
- Recovery: recovery/2026-10-25-prototype at6f9405b; clean implementation worktrees/tmp/purpuragenda-p08-deferred-booking and/tmp/purpuragenda-p14-auth-worker; coordinator/tmp/purpuragenda-handoff-integrity; stacked PR dependencies: none, both application prerequisite slices merged.
