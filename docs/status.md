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

## P48 merge-protection foundation evidence

Implemented a managed main ruleset, default-read-only API checker/application command,
per-commit test-source gate and mandatory delivery-policy CI job. The worker did not
apply live rules. Existing nodelete rules remain untouched; live enforcement is a
coordinator action after review. P48 publication and final inventory remain unfinished.

`python3 -m unittest discover -s scripts/ci/tests -p 'test_*.py' -v` passed 21 tests.
`bash scripts/ci/verify-build.sh` passed 108 domain and three architecture tests,
zero skips, domain 276/277 lines (99.64%) and 478/489 branches (97.75%).
`git diff --check` passed. No PostgreSQL, browser, load, security or restore suite
was added or certified. An ignored local toolchain symlink supplies pinned tools.

The initial negative rename test exposed an unchanged-rename loophole, fixed with
rename-aware old/new content comparison. The initial pagination fixture matched
`page=1` inside `per_page=100`, looped, and was interrupted; matching the final page
query fixed the test. Both corrected cases pass. Historical commits through exact
P07 head dd482e3 are grandfathered; all later commits require test-source changes.
Semantic test relevance still requires independent review.

P48 foundation published as draft PR8 at 74aa161; follow-up adds an ineffective-API-write readback regression. Live rules remain unapplied by this worker.

P48 independent review found that explicit application could remove extra required
checks despite preserving stronger review counts. The guard now preserves each
existing required check and source-app pin, strict checks, branch-creation checks,
merge restrictions and stronger/unknown managed parameters before writing. Added
eight negative API subcases and a positive unpinned-to-pinned strengthening case.

After the review fix, 23 delivery-policy tests and the full 108-domain/three-architecture build passed with no skips; domain coverage remains 99.64% lines/97.75% branches.
