# Task ledger

This is the single task-status authority. Read alongside the working tree and GitHub before changes. Recovered WIP is unverified prototype code on the recovery branch, not adopted implementation. Historical reports do not certify this revision. Detailed evidence remains in docs/status.md.

| Task | Advancement |
| --- | --- |
| P01 — Establish shared status, agent handoffs and initial documentation checks | In progress; owner-requested prompt.txt handoff authored in https://github.com/Laymer/purpuragenda/pull/50, final publication/check/merge state recorded on GitHub; prior foundation merged; https://github.com/Laymer/purpuragenda/pull/14; standing authorization04282fa; prior concurrency PR9 at15ba8b4; handoff-integrity follow-up https://github.com/Laymer/purpuragenda/pull/37 |
| P02 — Adopt the pinned toolchain, Maven modules, wrapper, architecture checks and build CI | Merged; https://github.com/Laymer/purpuragenda/pull/2; d0c5978;  |
| P03 — Adopt money, service values, intake validation and reservation intervals | Merged; https://github.com/Laymer/purpuragenda/pull/3; fc3f0fa;  |
| P04 — Complete scheduling policies, availability windows, exceptions, DST and horizon rules | Merged; https://github.com/Laymer/purpuragenda/pull/4; 06f829a;  |
| P05 — Complete immutable booking snapshots and state transitions | Merged; https://github.com/Laymer/purpuragenda/pull/5; 1470d88;  |
| P06 — Complete external receipt, refund and reconciliation calculations | Merged; https://github.com/Laymer/purpuragenda/pull/6; 8d7f62d |
| P07 — Adopt PostgreSQL migrations, constraints, roles and insert-only seeds | Merged; https://github.com/Laymer/purpuragenda/pull/7; ff3b33b; actual-container CI passed |
| P08 — Complete JDBC scheduling transactions, locking, savepoints and idempotency storage | Merged; https://github.com/Laymer/purpuragenda/pull/35 at5909901; additive009 correction; https://github.com/Laymer/purpuragenda/pull/20 at98c7535; scheduling storage scope complete; transaction core PR18 at6c1f96f; all seven final checks passed |
| P09 — Complete availability, request creation, quotas and synchronous expiry reclamation | In progress; availability/expiry/quotas/command boundary and typed application PR43 merged39001ae; atomic customer authority/creation https://github.com/Laymer/purpuragenda/pull/46 confirmed merged bcc9fec after native470Java/6Angular/203Python and all13checks; HTTP acceptance remains |
| P10 — Complete atomic cancellation, rescheduling and appointment outcomes | In progress; application commands https://github.com/Laymer/purpuragenda/pull/47 merged 27e2df1; concrete PostgreSQL customer-change adapter in draft slice task/p10-postgres-changes (code and actual-container tests implemented, review and hosted checks pending); HTTP and administrator outcomes remain |
| P11 — Complete administrator creation, overrides and configuration conflict assessment | In progress; slice 1 administrator creation https://github.com/Laymer/purpuragenda/pull/55 merged e238cd1; slice 2 (override hygiene, meeting uniqueness receipt, configuration conflict assessment) in draft branch task/p11-conflict-assessment; HTTP is P22 |
| P12 — Establish contract generation, the Angular application shell and typed API access | Merged; https://github.com/Laymer/purpuragenda/pull/16; c1151b2; contract foundation PR11 bc64f01 |
| P13 — Complete cryptographic primitives, protected key handling and RFC-vector tests | Merged; https://github.com/Laymer/purpuragenda/pull/12; 34836ed |
| P14 — Complete durable SMTP delivery, encrypted authentication envelopes and fenced leases | In progress; queue/SMTP/auth persistence and worker https://github.com/Laymer/purpuragenda/pull/36 merged526290f; heartbeat/fenced callbacks/expiry sweep verified; operational composition remains P45 |
| P15 — Complete guest email proof, scoped management access and recovery | In progress; redemption PR40 merged765cbf5; management recovery https://github.com/Laymer/purpuragenda/pull/44 merged9ac2f8d; protected HTTP/session integration remains P17 |
| P16 — Complete private administrator bootstrap, password/TOTP authentication and recovery | In progress; private bootstrap PR27, MFA PR29, recovery isolation PR33 and persisted recovery https://github.com/Laymer/purpuragenda/pull/41 merged0cbfc88; operator and secure HTTP integration remain |
| P17 — Complete JDBC sessions, CSRF, canonical-origin checks, throttles and cleanup | In progress; JDBC sessions/atomic factor-proof handoff additive012 https://github.com/Laymer/purpuragenda/pull/45 confirmed merged a6a62fa after426Java/6Angular/202Python and all13checks; HTTP/CSRF/origin/throttles/cleanup remain; HTTP session/origin/CSRF foundation slice claimed by Claude-worker-b in draft https://github.com/Laymer/purpuragenda/pull/53 (native 578Java/7Angular/220Python and all coverage passed at the pushed head; hosted state on GitHub); login/email endpoints, throttles and cleanup remain |
| P18 — Complete optional accounts, immutable login identity and separate profile storage | Recovered WIP; dependencies in docs/delivery.md;  |
| P19 — Complete public service and availability HTTP contracts | In progress; public catalog and advisory availability GET endpoints with throttle, contract and generated SDK in draft task/p19-public-http (PR pending); dependencies in docs/delivery.md |
| P20 — Complete protected booking creation and customer-change HTTP contracts | Recovered WIP; dependencies in docs/delivery.md;  |
| P21 — Complete administrator receipt, confirmation and refund commands and APIs | In progress; Claude-workerF slice 1 receipts/reconciliation merged https://github.com/Laymer/purpuragenda/pull/56 at 2f9c894; approval/confirmation in draft slice task/p21-approval-confirmation; refunds remain; no migration; HTTP remains P22/P29 |
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
| P45 — Build and verify hardened local, test, operational and restore Compose profiles | In progress; Claude takeover after Codex freeze; executable bootstrap https://github.com/Laymer/purpuragenda/pull/42 merged6b4544e; local Compose https://github.com/Laymer/purpuragenda/pull/48 at70a1b875 frozen, startup/smoke and211Python pass; fresh persistence/current-main/full gates/review pending; operator/two-backend/operational/restore remain |
| P46 — Complete encrypted backup scheduling, retention and backup-age monitoring | Recovered WIP; dependencies in docs/delivery.md; partial scripts; off-host verification pending |
| P47 — Complete fresh restore, credential revocation, reconciliation and reviewed delivery release | Recovered WIP; dependencies in docs/delivery.md; historical local encrypted restore only |
| P48 — Complete aggregate CI, branch protection and immutable release-image publication | Recovered WIP; protection foundation merged PR8 0c3ae04; suite inventory merged https://github.com/Laymer/purpuragenda/pull/15 at f3a4841; release publication pending |
| P49 — Complete Windows/WSL, development, operations, recovery and release runbooks | In progress; native Intel Mac/pinned Python toolchain https://github.com/Laymer/purpuragenda/pull/38 merged6616575; Windows preflight PR25 and Mac bootstrap PR22 merged; full runbooks remain |
| P50 — Record owner acceptance and resolve live-use prerequisites | Blocked; requires real-client/host acceptance and external settings; Intel i9 macOS MacBook host, automatic Tailscale setup requested, public medinavie.com ingress unprovisioned, no existing public Linux server; registrar Squarespace confirmed read-only, Google Domains nameservers; current Linux computer proposed encrypted off-host backup; project sender/contact hello@medinavie.com, domain acquired through Google Workspace (Squarespace) per owner; Zoom/live SMTP settings remain pending |

Delivery prerequisites and concurrent task lanes are specified in docs/delivery.md. P40 coverage foundation merged at bb7d4cf; P48 protection foundation merged at0c3ae04; P43 secret scanning foundation merged at8f5ed62; P48 suite inventory merged at f3a4841. Full P40 application-wide acceptance and P48 release publication remain pending.

Online payments are deferred and are not unfinished version 1 implementation.

## Active task claims

- P19/public-http: agent=Claude-workerD; branch=task/p19-public-http; base=main; handoff=docs/handoffs/p19-public-http.md
- P11/conflict-assessment: agent=Claude-workerE; branch=task/p11-conflict-assessment; base=main; handoff=docs/handoffs/p11-conflict-assessment.md
- P45/followup: agent=Claude-workerC; branch=task/p45-followup-v2; base=main; handoff=docs/handoffs/p45-followup.md
- P21/finance-commands: agent=Claude-workerF; branch=task/p21-approval-confirmation; base=main; handoff=docs/handoffs/p21-finance-commands.md

## Active handoff

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-25T17:20:00Z.
- Current task: P01/claude-handoff; branch: task/p01-claude-handoff-v2; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/50 (supersedes closed PR49, whose history failed the secrets gate).
- Last pushed implementation commit: 102445ff3a55e79a9fd70da40d15e3d211e0c15f, confirmed PR46 merge bcc9fecc18f8b6a7b5cc6197ab34f5414cd02302; uncommitted changes: prompt.txt, consolidated status/handoffs, CLAUDE.md pointer and documentation validator/tests, all included in this handoff commit. No uncommitted application source.
- What works: extensive Claude continuation prompt records merged foundations, frozen drafts, exact refs/evidence, local data, constraints and remaining workflow/gate implementation. PR46 native470Java/6Angular/203Python, all coverage, CI40215138889 all13green and exact independent workerB approval; GitHub confirms merged at17:15:16Z. Full localv1 remains unfinished.
- What remains: this handoff's own publication/checks/review/merge are verified on GitHub after authoring; if reading this commit from main, resume application tasks instead of repeating handoff work. Draft47 P10 and48 Compose need current-main integration/fresh gates; P17HTTP and remaining features are deferred to Claude.
- Decisions: all Codex feature workers stopped and their claims released; handoff authoring complete, remaining activity only verification/publication. Preserve every worktree/volume/secret/recovery ref. Project contact hello@medinavie.com, domain acquired through Google Workspace (Squarespace); local Mailpit only.
- Verification: handoff206strictPython and31delivery-policy tests, contracts, protection, inventory and diff checks pass. Independent workerA found no blockers and separately ran22documentation cases and actual ledger validation. Full native handoff verification is running; exact published-head review/hosted gates are required before merge. Prior PR46 coverage domain276/277lines478/489branches, application370/371339/340, adapters995/1006831/914, bootstrap91/9392/92, Angular22/2230/30.
- Next concrete action: coordinator completes this handoff's all-category gates and exact review, squash-merges green/current head, then leaves main clean. Claude reconciles remote state, claims frozen PR48 or47 / next eligible P17 slice, and follows prompt.txt through full local functionality. No new feature is started during wrap-up.
- Recovery: origin/recovery/2026-10-25-prototype at6f9405b, every worktree/stash preserved. Frozen PR47 head9f97ad742015c9d306b33de5100b5ff56d01942d has three metadata-only local edits consolidated here; frozen PR48 head70a1b875c74c54d903cd2d8138326fcce292442b is clean with healthy local stack/volumes preserved. PR31 remains paused. stacked PR dependencies: none for handoff; both continuation drafts require current-main integration.
