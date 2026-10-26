# Task ledger

This is the single task-status authority. Read alongside the working tree and GitHub before changes. Recovered WIP is unverified prototype code on the recovery branch, not adopted implementation. Historical reports do not certify this revision. Detailed evidence remains in docs/status.md.

| Task | Advancement |
| --- | --- |
| P01 — Establish shared status, agent handoffs and initial documentation checks | Merged; owner handoff https://github.com/Laymer/purpuragenda/pull/50 at1748cf5 and local-testing guide https://github.com/Laymer/purpuragenda/pull/65 at7f0c4a7 confirmed; earlier foundationPR14/37 retained in history |
| P02 — Adopt the pinned toolchain, Maven modules, wrapper, architecture checks and build CI | Merged; https://github.com/Laymer/purpuragenda/pull/2; d0c5978;  |
| P03 — Adopt money, service values, intake validation and reservation intervals | Merged; https://github.com/Laymer/purpuragenda/pull/3; fc3f0fa;  |
| P04 — Complete scheduling policies, availability windows, exceptions, DST and horizon rules | Merged; https://github.com/Laymer/purpuragenda/pull/4; 06f829a;  |
| P05 — Complete immutable booking snapshots and state transitions | Merged; https://github.com/Laymer/purpuragenda/pull/5; 1470d88;  |
| P06 — Complete external receipt, refund and reconciliation calculations | Merged; https://github.com/Laymer/purpuragenda/pull/6; 8d7f62d |
| P07 — Adopt PostgreSQL migrations, constraints, roles and insert-only seeds | Merged; https://github.com/Laymer/purpuragenda/pull/7; ff3b33b; actual-container CI passed |
| P08 — Complete JDBC scheduling transactions, locking, savepoints and idempotency storage | Merged; https://github.com/Laymer/purpuragenda/pull/35 at5909901; additive009 correction; https://github.com/Laymer/purpuragenda/pull/20 at98c7535; scheduling storage scope complete; transaction core PR18 at6c1f96f; all seven final checks passed |
| P09 — Complete availability, request creation, quotas and synchronous expiry reclamation | In progress; availability/expiry/quotas/command boundary and typed application PR43 merged39001ae; atomic customer authority/creation https://github.com/Laymer/purpuragenda/pull/46 confirmed merged bcc9fec after native470Java/6Angular/203Python and all13checks; HTTP acceptance remains |
| P10 — Complete atomic cancellation, rescheduling and appointment outcomes | In progress; applicationPR47 merged27e2df1 and concrete adapter https://github.com/Laymer/purpuragenda/pull/51 mergedfa9e071; HTTP and administrator outcomes remain |
| P11 — Complete administrator creation, overrides and configuration conflict assessment | In progress; administrator creation https://github.com/Laymer/purpuragenda/pull/55 merged e238cd1 and override hygiene, meeting receipt and conflict assessment https://github.com/Laymer/purpuragenda/pull/58 merged 9caa056; HTTP exposure remains P22 |
| P12 — Establish contract generation, the Angular application shell and typed API access | Merged; https://github.com/Laymer/purpuragenda/pull/16; c1151b2; contract foundation PR11 bc64f01 |
| P13 — Complete cryptographic primitives, protected key handling and RFC-vector tests | Merged; https://github.com/Laymer/purpuragenda/pull/12; 34836ed |
| P14 — Complete durable SMTP delivery, encrypted authentication envelopes and fenced leases | In progress; queue/SMTP/auth persistence and worker https://github.com/Laymer/purpuragenda/pull/36 merged526290f; heartbeat/fenced callbacks/expiry sweep verified; operational composition remains P45 |
| P15 — Complete guest email proof, scoped management access and recovery | In progress; redemption PR40 merged765cbf5; management recovery https://github.com/Laymer/purpuragenda/pull/44 merged9ac2f8d; protected HTTP/session integration remains P17 |
| P16 — Complete private administrator bootstrap, password/TOTP authentication and recovery | In progress; private bootstrap PR27, MFA PR29, recovery isolation PR33 and persisted recovery https://github.com/Laymer/purpuragenda/pull/41 merged0cbfc88; operator and secure HTTP integration remain |
| P17 — Complete JDBC sessions, CSRF, canonical-origin checks, throttles and cleanup | In progress; sessions/origin/CSRF/throttles/cleanup and guest proof HTTP https://github.com/Laymer/purpuragenda/pull/68 confirmed merged8f346ad; administrator login PR71 confirmed merged d96e0a6 after all required gates; recovery HTTP remains |
| P18 — Complete optional accounts, immutable login identity and separate profile storage | In progress; explicit optional enrollment https://github.com/Laymer/purpuragenda/pull/66 confirmed merged5d7d8be with migrations015/016; account authentication draft https://github.com/Laymer/purpuragenda/pull/73 implements017 proofs/epochs; current-main verification pending; profile/history and HTTP remain |
| P19 — Complete public service and availability HTTP contracts | Merged; public services/advisory availability https://github.com/Laymer/purpuragenda/pull/61 confirmedf654c53; full browser/load acceptance remains separate |
| P20 — Complete protected booking creation and customer-change HTTP contracts | In progress; queryPR69 confirmed mergedf99a8dd; coordinator claims stacked customer-http slice |
| P21 — Complete administrator receipt, confirmation and refund commands and APIs | In progress; receiptsPR56 merged2f9c894, approvalPR59 mergeda322914, refunds https://github.com/Laymer/purpuragenda/pull/62 merged0128f21; HTTP remains P22 |
| P22 — Complete administrator booking, service and schedule APIs with version checks | Recovered WIP; dependencies in docs/delivery.md;  |
| P23 — Complete public service selection and timezone-aware time selection | In progress; Codex-worker-c on task/p23-service-time, separate public UI slice; no mock backend |
| P24 — Complete verification, intake, review, submission and conflict recovery UI | Recovered WIP; dependencies in docs/delivery.md;  |
| P25 — Complete customer booking status, cancellation and rescheduling UI | Recovered WIP; dependencies in docs/delivery.md;  |
| P26 — Complete account enrollment, history, profile and session-recovery UI | Recovered WIP; dependencies in docs/delivery.md;  |
| P27 — Complete administrator agenda, pending queue, meeting entry and approval UI | Recovered WIP; dependencies in docs/delivery.md;  |
| P28 — Complete administrator manual creation, outcomes and explicit override UI | Recovered WIP; dependencies in docs/delivery.md; incomplete prototype |
| P29 — Complete administrator external receipt and refund UI | Recovered WIP; dependencies in docs/delivery.md;  |
| P30 — Complete service creation, editing and archiving UI | Recovered WIP; dependencies in docs/delivery.md; incomplete prototype |
| P31 — Complete weekly schedules, date exceptions, blackouts and policy controls | In progress; schedule/policy backend https://github.com/Laymer/purpuragenda/pull/60 merged56336ac; HTTP/UI remain; CONFIGURATION_CONFLICT notifications must stay internal |
| P32 — Complete notes, audit, customer search, reconciliation and notification follow-up | Recovered WIP; dependencies in docs/delivery.md; incomplete prototype |
| P33 — Complete verified delivery-contact changes without transferring ownership | Recovered WIP; dependencies in docs/delivery.md;  |
| P34 — Complete controlled customer exports and audited corrections | Recovered WIP; dependencies in docs/delivery.md; incomplete prototype |
| P35 — Complete reviewed erasure/anonymization and restore-safe tombstones | Recovered WIP; dependencies in docs/delivery.md; review requests exist; execution incomplete |
| P36 — Complete notification audiences, revisions, reminders, suppression and retry scenarios | Draft PR; https://github.com/Laymer/purpuragenda/pull/64 atf4480f5 source reviewed,871Java/6Angular/234Python and all14hosted checks pass on priorbase; current-main integration and fresh gates remain;014 reserved for reminders |
| P37 — Complete private iCalendar exports and safe Google Calendar actions | Recovered WIP; dependencies in docs/delivery.md;  |
| P38 — Complete API acceptance traceability and two-process integrity/quota races | Recovered WIP; dependencies in docs/delivery.md; historical partial evidence; coverage incomplete |
| P39 — Complete all 15 browser/viewport cells, accessibility scans and keyboard checks | Recovered WIP; dependencies in docs/delivery.md; historical selected Chromium/Firefox journeys only |
| P40 — Complete backend and Angular coverage enforcement with meaningful tests | Recovered WIP; foundation merged https://github.com/Laymer/purpuragenda/pull/10 at bb7d4cf; final application-wide acceptance pending |
| P41 — Fix layout stability and pass the nine-run browser performance budget | Recovered WIP; dependencies in docs/delivery.md; historical CLS 0.1564 exceeds 0.1 |
| P42 — Complete the specified 20-user load run and integrity receipt | Recovered WIP; dependencies in docs/delivery.md; no completed load summary |
| P43 — Complete secret, dependency, SBOM, SAST and container scanning | Recovered WIP; SAST merged https://github.com/Laymer/purpuragenda/pull/21 at0454505 with12 green checks; secret/dependency foundations merged; full SBOM/container/config pending |
| P44 — Complete authenticated and unauthenticated ZAP verification | Not started; dependencies in docs/delivery.md;  |
| P45 — Build and verify hardened local, test, operational and restore Compose profiles | In progress; ComposePR48 merged658ea9b and private admin operator https://github.com/Laymer/purpuragenda/pull/54 merged3c9b6de; durable AUTH/cleanup lifecycle PR67 confirmed merged7216986; two-backend/operational/restore remain |
| P46 — Complete encrypted backup scheduling, retention and backup-age monitoring | Recovered WIP; dependencies in docs/delivery.md; partial scripts; off-host verification pending |
| P47 — Complete fresh restore, credential revocation, reconciliation and reviewed delivery release | Recovered WIP; dependencies in docs/delivery.md; historical local encrypted restore only |
| P48 — Complete aggregate CI, branch protection and immutable release-image publication | Recovered WIP; protection foundation merged PR8 0c3ae04; suite inventory merged https://github.com/Laymer/purpuragenda/pull/15 at f3a4841; release publication pending |
| P49 — Complete Windows/WSL, development, operations, recovery and release runbooks | In progress; native Intel Mac/pinned Python toolchain https://github.com/Laymer/purpuragenda/pull/38 merged6616575; Windows preflight PR25 and Mac bootstrap PR22 merged; full runbooks remain |
| P50 — Record owner acceptance and resolve live-use prerequisites | Blocked; requires real-client/host acceptance and external settings; Intel i9 macOS MacBook host, automatic Tailscale setup requested, public medinavie.com ingress unprovisioned, no existing public Linux server; registrar Squarespace confirmed read-only, Google Domains nameservers; current Linux computer proposed encrypted off-host backup; project sender/contact hello@medinavie.com, domain acquired through Google Workspace (Squarespace) per owner; Zoom/live SMTP settings remain pending |

Delivery prerequisites and concurrent task lanes are specified in docs/delivery.md. P40 coverage foundation merged at bb7d4cf; P48 protection foundation merged at0c3ae04; P43 secret scanning foundation merged at8f5ed62; P48 suite inventory merged at f3a4841. Full P40 application-wide acceptance and P48 release publication remain pending.

Online payments are deferred and are not unfinished version 1 implementation.

## Active task claims

- P45/booking-workers: agent=Codex-coordinator; branch=task/p45-booking-workers; base=main; handoff=docs/handoffs/p45-booking-workers.md
- P17/admin-login: agent=Codex-worker-b; branch=task/p17-admin-login; base=main; handoff=docs/handoffs/p17-admin-login.md
- P18/account-auth: agent=Codex-worker-a; branch=task/p18-account-auth; base=main; handoff=docs/handoffs/p18-account-auth.md

- P23/service-time: agent=Codex-worker-c; branch=task/p23-service-time; base=main; handoff=docs/handoffs/p23-service-time.md

## Active handoff

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-26T22:01:00Z.
- Current task: P45/booking-workers; branch: task/p45-booking-workers; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/79.
- Last pushed implementation commit: d109082; uncommitted changes: five-lane runtime composition, lifecycle and actual Mailpit/restart/expiry/restore regressions.
- What works: query69 is confirmed merged; notification64 confirmed merged669bc5b after all required gates. Public context74 targets and source review pass, full final gates pending.
- What remains: current-main integration, full native/coverage/hosted/Compose/exact review; no full local milestone claimed.
- Decisions: separate maintenance port shares the same resource lock, one database time and transaction without creating artificial customer idempotency receipts. Runtime work lanes remain independent, readiness-gated, fixed-delay, restore-suppressed and gracefully stopped. No live SMTP or authentication bypass. Runtime Order14 reserved; admin HTTP worker owns15.
- Verification: maintenance target47Java (17domain/1application/29actual PostgreSQL) passes, including20-way two-adapter race, rollback, post-lock database time, maintenance fail-closed and no synthetic idempotency records.Five-lane lifecycle12 and real runtime12 cases now pass, with17domain/1application/6maintenancePG (48targetJava total,72seconds).25strict report regressions pass. Core d109082 independently source approved by workerB; full composition review/gates pending.
- Next concrete action: publish composition checkpoint, integrate current main and obtain exact review/all implemented gates.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and all worktrees/secrets/volumes preserved; stacked PR dependencies: notificationPR64 before this slice merges; current-main integration and every gate/review remain mandatory.
