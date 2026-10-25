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
| P09 — Complete availability, request creation, quotas and synchronous expiry reclamation | In progress; availability PR24, expiry PR26, quotas PR28 and command boundary PR39 merged b776269; typed customer command/authorized port checkpoint8f7c03b local; concrete session/grant transaction pending P17 |
| P10 — Complete atomic cancellation, rescheduling and appointment outcomes | Recovered WIP; dependencies in docs/delivery.md;  |
| P11 — Complete administrator creation, overrides and configuration conflict assessment | Recovered WIP; dependencies in docs/delivery.md;  |
| P12 — Establish contract generation, the Angular application shell and typed API access | Merged; https://github.com/Laymer/purpuragenda/pull/16; c1151b2; contract foundation PR11 bc64f01 |
| P13 — Complete cryptographic primitives, protected key handling and RFC-vector tests | Merged; https://github.com/Laymer/purpuragenda/pull/12; 34836ed |
| P14 — Complete durable SMTP delivery, encrypted authentication envelopes and fenced leases | In progress; queue/SMTP/auth persistence and worker https://github.com/Laymer/purpuragenda/pull/36 merged526290f; heartbeat/fenced callbacks/expiry sweep verified; operational composition remains P45 |
| P15 — Complete guest email proof, scoped management access and recovery | In progress; email redemption https://github.com/Laymer/purpuragenda/pull/40 merged765cbf5; exact booking management recovery claimed; P17 session attachment remains |
| P16 — Complete private administrator bootstrap, password/TOTP authentication and recovery | In progress; private bootstrap PR27, MFA PR29, recovery isolation PR33 and persisted recovery https://github.com/Laymer/purpuragenda/pull/41 merged0cbfc88; operator and secure HTTP integration remain |
| P17 — Complete JDBC sessions, CSRF, canonical-origin checks, throttles and cleanup | In progress; JDBC session/proof foundation claimed by Codex-worker-b; reserved migration012; HTTP protections and cleanup remain |
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
| P45 — Build and verify hardened local, test, operational and restore Compose profiles | In progress; executable bootstrap https://github.com/Laymer/purpuragenda/pull/42 draft; local Docker Desktop verified; Compose/operator/test stack remain |
| P46 — Complete encrypted backup scheduling, retention and backup-age monitoring | Recovered WIP; dependencies in docs/delivery.md; partial scripts; off-host verification pending |
| P47 — Complete fresh restore, credential revocation, reconciliation and reviewed delivery release | Recovered WIP; dependencies in docs/delivery.md; historical local encrypted restore only |
| P48 — Complete aggregate CI, branch protection and immutable release-image publication | Recovered WIP; protection foundation merged PR8 0c3ae04; suite inventory merged https://github.com/Laymer/purpuragenda/pull/15 at f3a4841; release publication pending |
| P49 — Complete Windows/WSL, development, operations, recovery and release runbooks | In progress; native Intel Mac/pinned Python toolchain https://github.com/Laymer/purpuragenda/pull/38 merged6616575; Windows preflight PR25 and Mac bootstrap PR22 merged; full runbooks remain |
| P50 — Record owner acceptance and resolve live-use prerequisites | Blocked; requires real-client/host acceptance and external settings; Intel i9 macOS MacBook host, automatic Tailscale setup requested, public medinavie.com ingress unprovisioned, no existing public Linux server; registrar Squarespace confirmed read-only, Google Domains nameservers; current Linux computer proposed encrypted off-host backup; existing Zoom/SMTP settings later |

Delivery prerequisites and concurrent task lanes are specified in docs/delivery.md. P40 coverage foundation merged at bb7d4cf; P48 protection foundation merged at0c3ae04; P43 secret scanning foundation merged at8f5ed62; P48 suite inventory merged at f3a4841. Full P40 application-wide acceptance and P48 release publication remain pending.

Online payments are deferred and are not unfinished version 1 implementation.

## Active task claims

- P09/authorized-context: agent=Codex-coordinator; branch=task/p09-authorized-context; base=main; handoff=docs/handoffs/p09-authorized-context.md
- P15/management-recovery: agent=Codex-worker-a; branch=task/p15-management-recovery; base=main; handoff=docs/handoffs/p15-management-recovery.md
- P17/session-proofs: agent=Codex-worker-b; branch=task/p17-session-proofs; base=main; handoff=docs/handoffs/p17-session-proofs.md
- P45/executable-bootstrap: agent=Codex-worker-c; branch=task/p45-executable-bootstrap; base=main; handoff=docs/handoffs/p45-executable-bootstrap.md

## Active handoff

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-25T14:30:34Z.
- Current task: P09/authorized-context; branch: task/p09-authorized-context; base: main; PR URL: not yet created.
- Last pushed implementation commit: 7906cfc merged b776269 in PR39; local checkpoint8f7c03b committed, uncommitted changes: current-main765cbf5 integration and reconciled ledger awaiting publication.
- What works: PR39, P49 PR38 and P16 PR41 merged after exact-head independent reviews and all 13 checks. P15 PR40 merge confirmed 765cbf5. Native tools and Docker work. Typed creation and next-date availability regressions pass; both PostgreSQL availability cases pass after the reproduced fix.
- What remains: current-main integration and independent exact-head review/CI; concrete CustomerBookingUnitOfWork session/grant adapter after P17 schema012. No public creation endpoint or atomic-storage acceptance is claimed for the unwired application port.
- Decisions: derive identity and idempotency scope from locked persisted authority, never caller fields. Preserve immutable snapshots, separate quota charges, expected-failure reclamation and non-overridable overlap. Preserve original CREATE and exact-booking derived MANAGE grants. P50 remains paused; deployment/live SMTP/purchases remain manual.
- Verification: full pinned native build passed 108 domain/75 application/162 adapters/3 architecture, 6 Angular and 197 strict Python tests with all coverage gates. The subsequently added pending-deadline equality regression passed in fresh 108 domain/76 application verification. The public regression failed as expected before correction, with sanitized counts in artifacts/p09-creation-failure. Prior PR39 CI40199288716 and P49 CI40199619970 passed all 13 checks; P16 CI40200392949 and P15 CI40201462721 likewise passed.
- Next concrete action: publish the tested application checkpoint, integrate main 765cbf5, then run all implemented categories and obtain exact-head independent review. Implement concrete locked authorization after schema012.
- Recovery: recovery/2026-10-25-prototype at 6f9405b; root worktree and preserved P09 handoff stash; shared native tools in /private/tmp/purpuragenda-p49-native-toolchain/.toolchain; stacked PR dependencies: none for unwired application logic; concrete authority requires P15 schema011 and P17 schema012.
