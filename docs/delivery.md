# Remaining v1 delivery

Approved 2026-10-25. STATUS.md alone records advancement. This document records dependencies and acceptance; requirements are not completion evidence. Reconcile GitHub and clean/local work before claiming work. Original P01–P06 are merged; P07 PR #7 is review-only until migration acceptance passes. Preserve recovery 6f9405b and historical branches.

## Ownership and small PRs

The coordinator owns shared ledger reconciliation. Up to three worker subagents run in separate worktrees, each owning one task/slice and draft PR. Claim the task before code changes. One active owner per task and one task per PR; split broad tasks into numbered slices, normally 200–800 handwritten changed lines. Identify generated clients and lockfiles separately. Keep published history append-only; merge current main rather than force-pushing rebases. Do not delete recovery branches or data volumes.

Each new commit adds or meaningfully updates a relevant automated test, including documentation and handoff work. Consolidate checkpoints into tested implementation commits. A mechanical merge may include test changes from its merged first-parent diff. Historical commits remain historical. Independent agent review checks semantic test relevance, resolves findings and records evidence; the coordinator squash-merges only green, current PR heads. GitHub merge state must be reconciled before marking Merged. A completed foundation slice does not complete P40/P48.

Active claims use `- PNN/slice: agent=name; branch=task/name; base=main; handoff=docs/handoffs/name.md` in STATUS.md. Each file carries all existing handoff fields, exact task/branch/base, UTC checkpoint, results/limits, next command and recovery. Keep the coordinator handoff too. When no work remains active, use the explicit claim section value `None.`. Release a claim only after recording recovery and reconciling the remote PR. A new follow-up slice may reopen an earlier merged task; retain the earlier PR/merge evidence.

## Prerequisite foundations

P01/concurrency establishes this coordination model and validator. P40/coverage-foundation and P48/merge-protection can be authored concurrently, then integrated and independently reviewed before expanding feature work. P43 scanning begins early; P45 test infrastructure begins once runnable bootstrap artifacts exist. Final task acceptance remains outstanding for each foundation task.

Coverage is at least 90% lines and branches per backend production module and Angular; domain/application retain 95% lines. Never use aggregate coverage to hide a failing module. Generated-code exclusions must be identifiable and reviewed. Require complete reports and source correspondence. Unit/integration execution data may combine against identical compiled classes. No threshold reduction or integrity exclusions.

Every PR runs every implemented automated category, including documentation changes. Activate applicable categories with implementation, not after it. Missing dependencies/suites/reports and skipped/cancelled checks fail. An explicit inventory distinguishes not-yet-implemented categories from enabled mandatory categories; release requires all categories. Main requires PRs, up-to-date base and successful aggregate verify without bypass. Full browser/load/security/restore suites remain mandatory once implemented despite runtime cost.

## Dependency queue

Run independent eligible tasks concurrently; an arrow below means prerequisite completion, not permission to combine tasks into one PR. Separate application use cases, identity adapters and admin components to avoid concurrently adopting recovery monoliths. Centralize migration numbering and serialize shared contract/client edits. Keep domain/application framework-free; scheduling mutations share the resource lock and captured authoritative database time.

- P07 migrations, P12 OpenAPI/client/shell, P13 cryptography: independent after foundations.
- P07 -> P08 transactions/locking/savepoints/idempotency -> P09 availability/create/quotas/expiry.
- P07 + P13 -> P14 queue/SMTP/encrypted auth delivery; P07 + P13 -> P16 private admin/MFA.
- P14 -> P15 guest proof/scopes/recovery; integrate one-use grants with P09.
- P15 + P16 -> P17 persistent sessions/CSRF/origin/throttles/cleanup.
- P09 -> P10 cancellation/reschedule/outcomes and independently P11 admin creation/overrides/configuration.
- P15 + P17 -> P18 optional accounts/profile/history.
- P09 + P12 + P17 -> P19 public HTTP -> P23 public service/time UI.
- P10 + P12 + P15 + P17 -> P20 protected customer HTTP.
- P20 + P23 -> P24 verification/intake/review/conflict UI and independently P25 booking management UI.
- P09 + P12 + P16 + P17 -> P21 receipts/approval/refund commands/APIs.
- P11 + P12 + P16 + P17 -> P22 admin booking/service/schedule APIs.
- P18 + shell -> P26 account UI.
- P21 + P22 -> P27 agenda/approval UI; P22 + P10 -> P28 manual creation/outcomes/overrides UI.
- P21 -> P29 finance UI; P22 -> P30 services UI and independently P31 schedule/policy UI.
- P14 + P21 + P22 -> P32 notes/audit/search/reconciliation/retry, including backend operations.
- P10 + P14 + P15 + P17 -> P33 verified contact changes without ownership transfer.
- P18 + P22 + audit operations -> P34 controlled exports/corrections -> P35 erasure/tombstones; final policy-dependent acceptance needs owner retention input.
- P10 + P14 + P21 + P33 -> P36 notification audiences/revisions/reminders/suppression/retries.
- Authorized booking views + P12 + P17 -> P37 private calendars; integrate attachments with P36.

Split P12 contract/generation from shell; P14 queue/fencing from auth envelopes; P17 sessions from HTTP protections; P21 commands from HTTP; P22 booking from configuration APIs; P28/P32 by feature; P34 exports from corrections; P35 execution from tombstone replay; P36 events/audiences from reminder/supersession scenarios. Each slice has meaningful tests; no parent task completes before all slices pass.

## Final verification and operations

Features add tests immediately; P38–P44 close completeness gaps rather than introduce testing late.

- P38: executable T01–T23 API traceability, two real backend processes and independent-connection 20-way overlap/quota/idempotency races.
- P39: Chromium/Firefox/WebKit/stable Chrome/stable Edge across 390x844, 768x1024, 1440x900; actual full journeys, accessibility scans and recorded manual keyboard acceptance. No placeholder-content fallback.
- P40 final: complete per-module/Angular coverage and reviewed meaningful boundary/failure/authorization tests.
- P41: nine cold phone runs at specified throttling, p75 LCP <=2.5s, CLS <=0.1, local feedback p95 <=100ms.
- P42: 20 users, 2-minute warmup + 5-minute measured run; availability p95 <500ms, mutation <1s, unexpected 5xx <1%, no integrity breach.
- P43 final: secrets/dependencies/SBOM/SAST/container/config scans, pinned provenance and resolved high/critical findings.
- P44: authenticated and unauthenticated ZAP against isolated real application, including admin MFA.
- P45: least-privilege local/test/operational/restore Compose, persistence/readiness/delivery isolation; exclude test controls operationally.
- P46: encrypted off-host backups, retention/key separation, failure and age-alert tests.
- P47: isolated fresh restore, revoked credentials, tombstone replay, integrity/financial reconciliation and reviewed notification release; measure RPO <=1h/RTO <=4h.
- P48 final: all mandatory categories enforced, verified protection, immutable images/SBOM/provenance published only from passing revision.
- P49: exercised Windows/WSL, development, operations, recovery, release and rollback/forward-fix runbooks.

Use isolated databases/networks/identities/artifacts per job and dedicated capacity for performance/load. Preserve sanitized failure evidence. No credentials, tokens, cookies, TOTP secrets, intake or arbitrary provider errors in logs/reports. Never weaken a gate because local Docker is unavailable; real CI evidence remains required.

## P50 owner and launch gates

Start the owner checklist early: hosting/DNS/HTTPS, SMTP/sender identity, Zoom entitlement, off-host backup/key recovery, reviewed notices/retention/business policies, real Safari/iPhone/calendar acceptance. Inputs are unverified until evidence is recorded. No purchase or deployment is authorized by implementation alone.

Release requires P07–P49 merged with every slice and full exact-revision gates; owner demonstrations of unpaid manual approval/calendars, races/quotas and changes/SMTP/restore; reviewed immutable artifacts/migration compatibility/recovery receipts; explicit owner deployment and purchase decisions; then synthetic live smoke/notification/backup/readiness verification and owner acceptance. Real external gates cannot be replaced by simulated CI results.

English, single practitioner, external payment facts and manual Zoom remain v1 scope. Checkout and automatic Zoom remain excluded.
