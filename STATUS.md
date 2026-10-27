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
| P24 — Complete verification, intake, review, submission and conflict recovery UI | In progress; verified booking-flow draft https://github.com/Laymer/purpuragenda/pull/83 subsumes shared memory-only session/CSRF/intent layerPR78; current with main cc3fe39, 110Angular tests/build pass locally and prior head963d25b passed all14hosted checks; independent exact-head review and real browser acceptance pending |
| P25 — Complete customer booking status, cancellation and rescheduling UI | Recovered WIP; dependencies in docs/delivery.md;  |
| P26 — Complete account enrollment, history, profile and session-recovery UI | Recovered WIP; dependencies in docs/delivery.md;  |
| P27 — Complete administrator agenda, pending queue, meeting entry and approval UI | In progress; administrator MFA login/logout UI draft https://github.com/Laymer/purpuragenda/pull/87 stacked on PR83; 123Angular tests/build pass locally after merging PR83 head9f26df2; agenda, pending queue and approval UI remain |
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

- P27/admin-auth-ui: agent=Claude-lane-b; branch=task/p27-admin-auth-ui; base=main; handoff=docs/handoffs/p27-admin-auth-ui.md

- P24/booking-flow: agent=Claude-lane-b; branch=task/p24-booking-flow; base=main; handoff=docs/handoffs/p24-booking-flow.md


- P18/password-change: agent=Codex-worker-a; branch=task/p18-password-change; base=main; handoff=docs/handoffs/p18-password-change.md
- P22/admin-read-http: agent=Codex-worker-b; branch=task/p22-admin-read-http; base=main; handoff=docs/handoffs/p22-admin-read-http.md

## Active handoff

- Agent and UTC checkpoint: Claude-lane-b, 2026-10-27T06:45:00Z.
- Current task: P24/booking-flow; branch: task/p24-booking-flow; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/83.
- Last pushed implementation commit: 963d25b; uncommitted changes: restored main's P17 ledger row lost in an earlier merge resolution, claim reassigned to Claude-lane-b, bootstrap-failure test added.
- What works: real typed email issuance/redemption, memory-only fragment capture before router startup, intake validation, authoritative plaintext review/consent, immutable submission with explicit uncertain retries, and actual booking status rendering are implemented. PR78 shared access is included byte-identically and is closed as subsumed.
- What remains: independent exact-head review, then coordinator squash merge. Customer management/account/admin UI remain separate slices; P27 admin auth UI PR87 is stacked on this branch.
- Decisions: root owns main/app routes; use generated API and shared access, no mocked backend. Capture and scrub verification fragments before router startup; keep tokens/intake in memory only. No migration or contract edits.
- Verification: branch already contains main cc3fe39 (no merge needed). Local verify-frontend passes110Angular tests and production build; handwritten coverage407/409lines393/416branches. Prior head963d25b passed all14hosted checks. Docker unavailable locally, so Compose/backend runs rely on hosted CI; local system Python3.14 runs248/249 CI tests, the unchanged native-toolchain tar-link case needs the pinned Python and passes hosted. Earlier sanitized failure counts (99tests/7failures, 91/1, 100/1) retained in docs/status.md.
- Next concrete action: gh pr checks 83 --watch on the new head, then request independent review.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and every existing worktree/secret/volume retained; stacked PR dependencies: none unmerged (74, 75, 72, 70, 79 merged); PR87 depends on this PR.
