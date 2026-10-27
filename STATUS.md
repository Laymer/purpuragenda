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
| P17 — Complete JDBC sessions, CSRF, canonical-origin checks, throttles and cleanup | In progress; sessions/origin/CSRF/throttles/cleanup and guest proof HTTP https://github.com/Laymer/purpuragenda/pull/68 confirmed merged8f346ad; administrator login PR71 confirmed merged d96e0a6 after all required gates; restricted recovery HTTP https://github.com/Laymer/purpuragenda/pull/75 confirmed merged538e335 after all required gates; broader authentication acceptance remains |
| P18 — Complete optional accounts, immutable login identity and separate profile storage | In progress; explicit optional enrollment https://github.com/Laymer/purpuragenda/pull/66 confirmed merged5d7d8be with migrations015/016; account authenticationPR73 confirmed mergedcde517c after1013Java/11Angular/242Python/all14hosted/Compose6/review;017 proofs/epochs implemented; profile/history and HTTP remain |
| P19 — Complete public service and availability HTTP contracts | Merged; public services/advisory availability https://github.com/Laymer/purpuragenda/pull/61 confirmedf654c53; booking-context https://github.com/Laymer/purpuragenda/pull/74 confirmed mergedaffe433 after all required gates; full browser/load acceptance remains separate |
| P20 — Complete protected booking creation and customer-change HTTP contracts | In progress; query PR69 confirmed merged f99a8dd; customer HTTPPR72 confirmed merged8702161 after1032Java/30Angular/245Python/all14hosted/Compose6/review |
| P21 — Complete administrator receipt, confirmation and refund commands and APIs | In progress; receiptsPR56 merged2f9c894, approvalPR59 mergeda322914, refunds https://github.com/Laymer/purpuragenda/pull/62 merged0128f21; HTTP remains P22 |
| P22 — Complete administrator booking, service and schedule APIs with version checks | In progress; authorized queryPR76 and stacked admin-read-http; command APIs remain |
| P23 — Complete public service selection and timezone-aware time selection | Merged; public service/time selection https://github.com/Laymer/purpuragenda/pull/70 confirmed6fc7e0a after1013Java/27Angular/243Python/all14hosted/Compose6/review; full browser/performance matrix remains separate |
| P24 — Complete verification, intake, review, submission and conflict recovery UI | In progress; shared memory-only session/CSRF/intent layerPR78; full booking UI remains |
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
| P36 — Complete notification audiences, revisions, reminders, suppression and retry scenarios | In progress; notification slice1 https://github.com/Laymer/purpuragenda/pull/64 confirmed merged669bc5b; reminder deadline/grace draft https://github.com/Laymer/purpuragenda/pull/82 source approvedbef42c9 and baseline native/hosted passed; main 79 and 81 (68f898b) integrated by merge, fresh hosted checks and exact-head review pending |
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

- P31/configuration-authority: agent=Codex-worker-b; branch=task/p31-configuration-authority; base=main; handoff=docs/handoffs/p31-configuration-authority.md
- P36/reminders: agent=Codex-worker-c; branch=task/p36-reminders; base=main; handoff=docs/handoffs/p36-reminders.md


- P45/booking-workers: agent=Codex-coordinator; branch=task/p45-booking-workers; base=main; handoff=docs/handoffs/p45-booking-workers.md
- P18/password-change: agent=Codex-worker-a; branch=task/p18-password-change-v2; base=main; handoff=docs/handoffs/p18-password-change.md
- P22/admin-read-http: agent=Codex-worker-b; branch=task/p22-admin-read-http; base=main; handoff=docs/handoffs/p22-admin-read-http.md
- P24/shared-access: agent=Codex-worker-c; branch=task/p24-shared-access; base=main; handoff=docs/handoffs/p24-shared-access.md

## Active handoff

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-26T23:30:00Z.
- Current task: P45/booking-workers; branch: task/p45-booking-workers; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/79.
- Last pushed implementation commit: e163fe0; uncommitted changes: append-only confirmed context74/mainaffe433 integration, preserving RR public reads and RC locked maintenance.
- What works: query69 is confirmed merged; notification64 confirmed merged669bc5b after all required gates. Public context74 targets and source review pass, full final gates pending.
- What remains: current-main integration, full native/coverage/hosted/Compose/exact review; no full local milestone claimed.
- Decisions: separate maintenance port shares the same resource lock, one database time and transaction without creating artificial customer idempotency receipts. Runtime work lanes remain independent, readiness-gated, fixed-delay, restore-suppressed and gracefully stopped. No live SMTP or authentication bypass. Runtime Order14 reserved; admin HTTP worker owns15, account16, finance17; coordinator reserves18 for two-context isolation.
- Verification: e163fe0 full native1060Java/31Angular/248Python and all coverage/hosted14 checks pass; exact independent review5660288867 approved. Fresh Compose was not reused after main74 advanced. Earlier evidence: baseline7e5fa84 passes1044Java/30Angular/246Python, all14hosted, clean localCompose6 and independent exact-head review5660221901; coverage domain276/277lines478/489branches, application1050/1055853/884, adapters2102/21331630/1799, bootstrap257/273215/230, Angular119/11987/88. This is baseline evidence after main advances. Earlier targets: maintenance target47Java (17domain/1application/29actual PostgreSQL) passes, including20-way two-adapter race, rollback, post-lock database time, maintenance fail-closed and no synthetic idempotency records.Five-lane lifecycle12 and real runtime13 cases now pass, with17domain/1application/6maintenancePG (49targetJava total,122seconds after deterministic port-isolation correction).25strict report regressions pass. Core d109082 independently source approved by workerB; full composition review/gates pending.
- Next concrete action: publish public-context integration and repeat all exact-revision gates/review before merge.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and all worktrees/secrets/volumes preserved; stacked PR dependencies: notificationPR64 before this slice merges; current-main integration and every gate/review remain mandatory.
