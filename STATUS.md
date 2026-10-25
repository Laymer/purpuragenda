# Task ledger

This is the single task-status authority. Read alongside the working tree and GitHub before changes. Recovered WIP is unverified prototype code on the recovery branch, not adopted implementation. Historical reports do not certify this revision. Detailed evidence remains in docs/status.md.

| Task | Advancement |
| --- | --- |
| P01 — Establish shared status, agent handoffs and initial documentation checks | Merged; https://github.com/Laymer/purpuragenda/pull/14; standing authorization04282fa; prior concurrency PR9 at15ba8b4 |
| P02 — Adopt the pinned toolchain, Maven modules, wrapper, architecture checks and build CI | Merged; https://github.com/Laymer/purpuragenda/pull/2; d0c5978;  |
| P03 — Adopt money, service values, intake validation and reservation intervals | Merged; https://github.com/Laymer/purpuragenda/pull/3; fc3f0fa;  |
| P04 — Complete scheduling policies, availability windows, exceptions, DST and horizon rules | Merged; https://github.com/Laymer/purpuragenda/pull/4; 06f829a;  |
| P05 — Complete immutable booking snapshots and state transitions | Merged; https://github.com/Laymer/purpuragenda/pull/5; 1470d88;  |
| P06 — Complete external receipt, refund and reconciliation calculations | Merged; https://github.com/Laymer/purpuragenda/pull/6; 8d7f62d |
| P07 — Adopt PostgreSQL migrations, constraints, roles and insert-only seeds | Merged; https://github.com/Laymer/purpuragenda/pull/7; ff3b33b; actual-container CI passed |
| P08 — Complete JDBC scheduling transactions, locking, savepoints and idempotency storage | Merged; https://github.com/Laymer/purpuragenda/pull/20 at98c7535; scheduling storage scope complete; transaction core PR18 at6c1f96f; all seven final checks passed |
| P09 — Complete availability, request creation, quotas and synchronous expiry reclamation | Recovered WIP; availability PR24 merged8d2ecf6; expiry/financial-effects PR26 merged04d982d with12 green checks; quotas/creation remain |
| P10 — Complete atomic cancellation, rescheduling and appointment outcomes | Recovered WIP; dependencies in docs/delivery.md;  |
| P11 — Complete administrator creation, overrides and configuration conflict assessment | Recovered WIP; dependencies in docs/delivery.md;  |
| P12 — Establish contract generation, the Angular application shell and typed API access | Merged; https://github.com/Laymer/purpuragenda/pull/16; c1151b2; contract foundation PR11 bc64f01 |
| P13 — Complete cryptographic primitives, protected key handling and RFC-vector tests | Merged; https://github.com/Laymer/purpuragenda/pull/12; 34836ed |
| P14 — Complete durable SMTP delivery, encrypted authentication envelopes and fenced leases | Recovered WIP; queue PR19 e917ee3, correction PR23 6afe0e1 and SMTP PR30 fc06c21 merged; encrypted auth envelopes/worker remain |
| P15 — Complete guest email proof, scoped management access and recovery | Recovered WIP; dependencies in docs/delivery.md;  |
| P16 — Complete private administrator bootstrap, password/TOTP authentication and recovery | Recovered WIP; private bootstrap PR27 202fbc7 and atomic MFA PR29 d34a735 merged with13 checks; recovery/scoped reenrollment remain; no HTTP/CLI/session wiring |
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
| P49 — Complete Windows/WSL, development, operations, recovery and release runbooks | Recovered WIP; Mac bootstrap PR22 at4233afd actual host ready; Windows preflight PR25 at7d7f19e with13 green checks; actual WSL acceptance/full runbooks pending |
| P50 — Record owner acceptance and resolve live-use prerequisites | In progress; owner-input draft PR31 paused at owner request; privacy core wording approved, complete notice/retention/terms and external/live-use gates pending; Squarespace registrar confirmed, no public ingress server |

Delivery prerequisites and concurrent task lanes are specified in docs/delivery.md. P40 coverage foundation merged at bb7d4cf; P48 protection foundation merged at0c3ae04; P43 secret scanning foundation merged at8f5ed62; P48 suite inventory merged at f3a4841. Full P40 application-wide acceptance and P48 release publication remain pending.

Online payments are deferred and are not unfinished version 1 implementation.

## Active task claims

- P50/release-inputs: agent=Codex; branch=task/p50-release-inputs; base=main; handoff=docs/handoffs/p50-release-inputs.md

## Active handoff

- Agent and UTC checkpoint: Codex, 2026-10-25T10:58:22Z.
- Current task: P50/release-inputs; branch: task/p50-release-inputs; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/31.
- Last pushed implementation commit: 6ae93e5; uncommitted changes: reviewed exact-step enforcement fix/tests and owner-requested pause checkpoint; will be committed/pushed together.
- What works: Mac/Tailscale/Docker host prerequisites accepted; Squarespace registrar/public DNS verified; owner approved privacy core wording including necessary providers; existing Zoom/SMTP settings later; no public ingress server exists.
- What remains: machine-checked input register separating owner-reported/approved core from complete launch gates; full notice/retention/terms, ingress/DNS/HTTPS, supplier settings, backup/recovery, real-client and deployment acceptance remain pending.
- Decisions: preserve exact approved core text; no full-notice/legal-completeness claim or public deployment/purchase authorization inferred. Evidence must be safe and revision-bound for release.
- Verification:183 strict root+31 delivery Python tests, input/docs/suite inventory and whitespace passed locally; release rejects missing full notice/acceptance. All existing implemented categories remain mandatory; exact-head CI and independent review pending.
- Next concrete action: on owner resume, integrate main f72bae2 into this draft, re-review exact-step enforcement fix, require all current-base CI; do not merge before gates. Owner requested pause; no further work until resumed.
- Recovery: recovery/2026-10-25-prototype at6f9405b; reserved009 deferred booking current-row fix is NOT STARTED (known limitation remains); P16recovery migration010 waits009; stacked PR dependencies: none; P49 preflight7d7f19e merged with13 checks.
