# Implementation evidence

Baseline inspected 2026-10-24: main at48b544dac501b9da5e304e9ba57451a668a83f06; README.md and .gitignore only; no applicable ancestor AGENTS.md. Original content preserved.

## Evidence authority

Task advancement is maintained only in ../STATUS.md. The records below are historical observations from the recovered prototype, not verified results for adopted PRs. No deployment, customer communication or supplier purchase occurred.

## Historical environment observations

Docker CLI 28.5.2 existed but could not connect to a daemon. Java 25.0.3, Maven 3.9.12, Node 24.19.0 and npm 11.19.0 were observed. Network artifact access worked after an environment permission update. These observations require fresh verification when their task is adopted.

## P01 evidence

Recovery commit 6f9405b preserves 201 files / 18251 inserted lines on recovery/2026-10-25-prototype. The staged gitleaks scan reported no leaks across approximately 954546 bytes. Ignored secrets and generated local outputs were excluded. P01 is based on original main 48b544d; recovery is not an implementation PR.

P01 local verification: `python3 scripts/ci/check-docs.py` passed (50 tasks, valid states and required handoff). Six isolated negative mutations were rejected: extra column, duplicate ID, invalid state, missing handoff field, merged status without PR and missing required document. The first negative-test harness incorrectly replaced prose rather than a state cell; corrected harness passed all six. `git diff --check` passed. No Java, Angular, database or browser implementation is adopted by P01, so their historical results are not passing evidence for this PR.

## P02 evidence

`bash scripts/dev/install-toolchain.sh` verified and extracted JDK 25.0.5+8, Node 24.22.1 and Maven 3.9.16 from the locked cached archives. The wrapper SHA256 independently matched maven.zip. An isolated corrupted-cache test failed with `Checksum mismatch: maven` as required.

`bash scripts/ci/verify-build.sh` passed: all six module artifacts built and three architecture tests passed, with zero failures/errors/skips and required XML reports checked. Documentation validation and `git diff --check` passed. There are no adopted production classes or feature suites yet; empty artifacts are scaffolding. Bytecode boundaries become nonempty as feature tasks introduce classes. No feature, coverage, database, browser or operational gate is certified by P02.

Failures corrected during P02: copied wrapper initially lacked executable permission; initial sandbox build could not write ~/.m2 and was rerun with approved cache/network access; initial fail-if-no-tests setting rejected empty scaffold modules and was replaced by a production-source/test guard plus mandatory implemented-suite reports; an ArchUnit negative assertion initially used `.get()` on an already singular JavaClass and was corrected. A shell edit command also failed parsing before changes; corrected delimiter executed successfully. Final build exit status was zero.

The staged whitespace check also caught the upstream Windows wrapper's CRLF lines before Git normalization. Added explicit LF/CRLF attributes; the normalized staged check is authoritative. The wrapper scripts are upstream generated content (484 lines) and the resolved lock is 60 lines, separate from handwritten build changes.

## P03 evidence

Selectively adopted six pure-JDK domain types from recovery: safe business failures, money, service values, normalized customer inputs, immutable intake and half-open intervals. Repaired the reservation factory to return a safe domain failure for null/unrepresentable starts while always reserving 120 elapsed minutes. Generic interval lengths remain available for schedule windows and blackouts.

`bash scripts/ci/verify-build.sh` passed the full implemented build: 44 domain tests and three architecture tests, zero failures/errors/skips. JaCoCo reported 54/55 lines (98.18%) and 91/92 branches (98.91%), exceeding enforced 95%/90% domain gates. Coverage XML is now explicitly required, with nonempty LINE/BRANCH counters. Synthetic tests cover money/currency/price bounds, NFC/code-point lengths, IDNA expansion and alias separation, intake limits, Brussels month-end date boundary, DST elapsed reservation length, adjacency and overflow. `git diff --check` passed.

Earlier P03 failures: handoff field capitalization did not match the validator; fixed. Initial 43-case domain run had two fixture errors: two-character `a@` hit the general minimum-length error before mailbox validation, and the first expanded IDNA example stayed within 254 characters. Replaced them with `ab@` and three 57-character Unicode labels respectively; rerun passed. No domain threshold was weakened. P02 exact-head CI passed all three jobs and merge d0c5978 was confirmed before moving P03 onto main.

Before the first P03 push, staging exposed that the inherited `.toolchain/` ignore rule did not ignore a local symlink. Removed that symlink from the index while preserving it locally and changed the ignore rule to cover either a directory or symlink. No tool installation or symlink is published.

## P04 evidence

Adopted SchedulingPolicy, AvailabilityCalendar and SchedulingRules selectively from recovery. Calendar construction normalizes intersecting/adjacent windows and copies all collections; ADD exceptions merge with inherited windows, REPLACE/CLOSED keep their distinct meaning. Invalid grids now fail with INVALID_POLICY in both candidate enumeration and direct start validation. Removed the prototype's undocumented daily-capacity ceiling of 1000; nonnegative integer capacity remains configurable, including zero.

`bash scripts/ci/verify-build.sh` passed: 61 domain tests plus three architecture tests, no failures/errors/skips; coverage 139/140 lines (99.29%) and 219/220 branches (99.55%), with unchanged 95%/90% enforcement and required reports. Tests include 481 generated overlap offsets, gap omission and both autumn offsets, month-end/leap horizon clamps and DST gap resolution, inclusive notice/horizon/cutoff/expiry boundaries, immutable normalized windows, every date-exception mode, independent quota violations, named blackout overrides and absolute overlap prohibition. `git diff --check` passed. No P04 test failures occurred; the final rerun verifies removal of the undocumented capacity ceiling and precomputation of effective windows.

These are domain-level scheduling rules, not a claim that availability APIs, atomic mutations or database integrity races are implemented. P03 exact-head CI passed and merge fc3f0fa was confirmed before rebasing P04 onto main.

## P05 evidence

Adopted Booking and its immutable snapshot/state transitions from recovery. Strengthened reconstructed-record invariants: future-from-creation starts, finite reservation end, confirmation-history agreement and bounded pending expiry. Meeting is now a final value with private construction via validated parsing, explicit IP rejection and redacted diagnostics; no URI is fetched. The future JDBC adoption must replace recovery's direct Meeting constructor with configured validated parsing.

`bash scripts/ci/verify-build.sh` passed 100 domain tests plus three architecture tests, zero failures/errors/skips. Domain coverage: 228/229 lines (99.56%), 378/385 branches (98.18%), exceeding unchanged 95%/90% gates. New tests cover all terminal-state mutations, inclusive expiry/cutoff boundaries, immutable snapshots/identity through moves and delivery-contact changes, confirmed calendar revisions, independent confirmation basis, early outcomes, invalid stored-record combinations, and rejected/allowed meeting URL forms using synthetic data. No P05 test failures occurred. No application/database/payment/authentication behavior is certified by this domain-only task.

## P06 evidence

Adopted the append-only external-payment ledger and repaired three prototype defects: enforce one booking per ledger, use a structured duplicate-reference key rather than delimiter concatenation, and show retained sufficient funds as PAID_EXTERNAL after a late receipt's refund settles. Outstanding obligations retain REFUND_DUE precedence; all actual funds returned yields REFUNDED_EXTERNAL even without a policy obligation. Service prices keep their 100000000-cent bound, while external amounts preserve independently verified nonnegative long values without inheriting that price ceiling. Checked arithmetic fails rather than wrapping a total.

`bash scripts/ci/verify-build.sh` passed 108 domain tests plus three architecture tests with no failures/errors/skips. Coverage: 276/277 lines (99.64%), 478/489 branches (97.75%), above unchanged 95%/90% gates. Synthetic tests cover partial/multiple currencies, refund balances, late receipts and retained nonrefundable money, zero-price facts, malformed entries/obligations, duplicate keys/IDs, delimiter collisions, immutable collections, cross-booking rejection, actual amounts above service-price bounds and overflow. No P06 test failures occurred. Durable financial idempotency, global reference uniqueness and reconciliation commands remain later application/database tasks.

Fresh operational dependency check: `docker version` exited 1 both inside and outside the sandbox; Docker client 28.5.2 exists but cannot connect to unix:///var/run/docker.sock. No local Docker/Testcontainers or Compose passing claim follows from unit-test evidence. CI may provide independent PostgreSQL evidence once the suite is adopted. P05 exact-head CI passed and merge 1470d88 was confirmed.

## P01 concurrent delivery evidence

Reconciled remote main 8d7f62d, merged P01–P06, clean existing worktrees and sole preexisting draft P07 dd482e3. Added tested concurrent claims/handoffs and the approved dependency queue, coverage/commit/review/launch decisions. No recovered feature code adopted.

`python3 -m unittest discover -s scripts/ci -p 'test_*.py'` passed 15 synthetic ledger tests. `python3 scripts/ci/check-docs.py` and `git diff --check` passed. `bash scripts/ci/verify-build.sh` passed 108 domain and three architecture tests with zero failures/errors/skips; domain coverage 276/277 lines (99.64%) and 478/489 branches (97.75%). Independent agent review found and verified fixes for idle claims, mandatory dependency documentation and claim/state mismatch. Exact-head remote CI remains required. PostgreSQL/Angular/browser/security/recovery suites are not yet implemented; no application/release completion is claimed.

## P40 coverage foundation evidence

Implemented inherited JaCoCo instrumentation/report/check for all Maven modules with independent backend production-module thresholds: domain/application 95% lines and 90% branches, adapters/bootstrap 90% lines and branches. One append-only execution file per module combines unit/integration forks without summing duplicate counters. The shared post-build validator rejects missing reports/counters and omitted source files; it also requires complete Angular coverage-final JSON once handwritten frontend sources exist. Generated exclusions require individual generator-manifest entries and version provenance.

`bash scripts/ci/verify-build.sh` passed locally on 2026-10-25: 108 domain tests, three architecture tests, zero failures/errors/skips; domain 276/277 lines (99.64%) and 478/489 branches (97.75%). The final full build included all 23 new synthetic gate tests, including zero-branch report edge cases. Negative cases include below-boundary line/branch thresholds, per-module masking, omitted production sources, missing reports/counters, invalid counters, Angular map truncation and generated-manifest path traversal. `git diff --check` passed. Final exact-head CI remains required.

Limits: application/adapters/bootstrap are empty scaffolds, and Angular is not yet adopted on this branch; no application or Angular coverage result is claimed. Real PostgreSQL, external acceptance-process coverage merging, browser, load, security and recovery suites remain their owning tasks. Full-project P40 completion remains pending.

P01 concurrency prerequisite merged at 15ba8b4 and was integrated without history rewriting. Its delivery dependency map, validator and test suite are preserved. Integrated local checks passed: `python3 -m unittest discover -s scripts/ci -p 'test_*.py'` ran 38 tests (15 ledger + 23 coverage); documentation validation, whitespace and the full implemented build passed (108 domain + 3 architecture, zero skips; domain 276/277 lines and 478/489 branches). Remote exact-head CI remains required.

## P12 contract foundation evidence

Selectively adopted public service/availability schemas from recovery into an OpenAPI3.1 contract, requiring safe problem metadata and explicit time-offset labels. Added pinned OpenAPI Generator7.26.0 strict generation, same-origin Angular SDK and byte-complete drift verification. Generator artifact was executed from the existing approved versioned Maven cache. Generated files are reported separately from handwritten changes. No HTTP endpoint or Angular shell implementation is certified.

Local checks on2026-10-25: 46 Python tests passed (15 ledger,24 coverage including generated-only scaffolding,7 contract); full build passed108 domain+3 architecture tests with zero failures/errors/skips and276/277 lines,478/489 branches. Fresh SDK generation and independent second generation byte comparison passed. Initial generator config warned that validateSpec was unknown; inspected the plugin's own descriptor and replaced it with explicit skipValidateSpec=false, then regenerated successfully. Generated-only source exemption retains a negative test requiring a report as soon as any handwritten TypeScript exists. Exact-head CI and independent review remain pending.

P12 independent review found and resolved SDK write-path failures: reject symlinked ancestors/destination and invalid/empty fresh output before modifying the committed SDK; stage replacement and restore the prior SDK if installation fails. Four regression tests cover preservation/escape/stale removal/rollback; all50 Python tests and full108+3 build passed. Independent re-review approved. Initial PR11 CI passed all then-implemented categories before prerequisite P48 integration.
