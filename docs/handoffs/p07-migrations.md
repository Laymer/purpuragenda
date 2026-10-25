# P07 migration adoption handoff

This is a review checkpoint, not adopted SQL or PostgreSQL acceptance evidence. P01–P06 are merged. Preserve recovery/2026-10-25-prototype at 6f9405b and adopt only reviewed migrations/resources from it. Keep changesets 001–006 and their existing contents/paths intact for databases that already applied them; repair with additive 007+ changesets. No data volume may be deleted.

The recovered master changelog includes six SQL changesets (153 SQL lines). It creates booking/occupancy/snapshots, independent financial facts, security/outbox/privacy tables and immutable-fact triggers. The two service seeds are insert-only; reruns must preserve owner edits. The recovered operational role-init script is a reference for the later Compose task, not permission to use live credentials or deploy.

Findings requiring implementation and tests:

- payment_entry.amount_minor currently has a 100000000 ceiling. P06 correctly keeps that ceiling on service prices only. Add a migration that preserves positive actual payment amounts without the service-price ceiling; retain applied history.
- The pending check compares expires_at to nullable expiry_ceiling. PostgreSQL CHECK permits unknown; require a non-null ceiling for pending records and verify the snapshot-derived lifetime/buffer agreement.
- verify_booking_occupancy compares JSON fields with `<>`. Missing JSON members yield null and can evade an IF condition. Use explicit required shape checks and `IS DISTINCT FROM` for required snapshot agreement. Check finite starts/reservation bounds, exact elapsed duration, session duration, Brussels date and confirmation history.
- Preserve the exclusion constraint as an independent overlap barrier. Test half-open adjacency, pending/confirmed/completed/no-show occupancy, terminal release, invalid ranges and attempted administrator overlap. Do not add clock-dependent index predicates.
- Preserve restrictive foreign keys and immutable snapshots/policies/receipts/charges/audit. Test attempted mutation and deletion using the application role.
- Test distinct DDL migration and DML application roles, schema privileges, default grants for future tables, and denied application access to Liquibase metadata. No password or arbitrary SQL/provider error may be stored in reports.

Add a real PostgreSQL migration suite and make it mandatory in every subsequent PR. Recovered dependencies are Liquibase 5.0.4, PostgreSQL JDBC 42.7.14, Testcontainers 2.0.5; review and pin them before adoption. The recovered PostgreSQL image is postgres:18.6-bookworm@sha256:bbf83594798e144bff365a9c191c5d779f181fb3135902064ab8ff11918020fc. Preserve digest provenance and verify an actual pull/run. Do not use H2.

Required acceptance includes fresh migration, upgrade from 001–006, repeated migration without resetting edited seeds, persistence across reconnect/restart, constraints/role denials and sanitized XML reports with zero skipped tests. Require reports for resource-only adapters too: the current build report loop identifies quality and modules with production Java; migration tests must extend that detection. Existing documentation, domain, architecture and 95%/90% domain coverage gates must keep running.

`docker version` currently exits 1, both sandboxed and approved, because no daemon is reachable at unix:///var/run/docker.sock. This is a local limitation, not proof that CI PostgreSQL cannot run. Use real CI Testcontainers evidence when available; any optional isolated local PostgreSQL run must remain explicitly distinct from Docker/Testcontainers/Compose evidence. Missing dependencies/suites/reports must fail rather than skip.

Next concrete action after reconciling GitHub and the ledger:

```sh
git restore --source=recovery/2026-10-25-prototype --worktree backend/adapters/src/main/resources/db/changelog
```

Review the restored files, implement additive fixes and the acceptance suite, then run all implemented categories. The recovered JDBC adapter will also need P05's private Meeting construction API repaired in P08; do not adopt that adapter prematurely. Later transaction races, HTTP/security/browser/coverage/load/scanning/restore/launch tasks remain uncompleted.

## Current P07 handoff


- Agent and UTC checkpoint: Codex-p07, 2026-10-25T08:13:28Z.
- Current task: P07/migrations; branch: task/p07-migrations; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/7.
- Last pushed implementation commit: dd482e3 review checkpoint; local implementation c10568c; pushed implementation eb28055; uncommitted changes: standing authorization integration; parser/report fixes in local cf09add; no force-push.
- What works: P01/P40/P48 foundations and P13 crypto merged; P07 existing review findings retained. What remains: latest-main integration, actual-container CI and independent review.
- Decisions: preserve recovered changesets 001–006 byte-for-byte; add corrections in 007+, use distinct DDL/DML roles and retain absolute overlap barrier.
- Verification: 38 Python gate/ledger tests and full build passed (108 domain + 3 architecture, zero skips; domain 276/277 lines and 478/489 branches). Docker daemon unavailable, no migration passing claim.
- Next concrete action: push exact-digest parser fix and mandatory migration report gate, integrate latest main, require actual-container CI.
- Recovery: recovery/2026-10-25-prototype at 6f9405b; stacked PR dependencies: none, prerequisites merged; preserve private ignored local state.

P07 current checks: dependency/test compilation and isolated PostgreSQL18.6 manual SQL checks passed; mandatory full build fails locally at POSTGRES_TEST_START_FAILED (Docker daemon unavailable), so no migration-suite passing claim.
