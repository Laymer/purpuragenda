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

## P01 concurrent delivery evidence

Reconciled remote main 8d7f62d, merged P01–P06, clean existing worktrees and sole preexisting draft P07 dd482e3. Added tested concurrent claims/handoffs and the approved dependency queue, coverage/commit/review/launch decisions. No recovered feature code adopted.

`python3 -m unittest discover -s scripts/ci -p 'test_*.py'` passed 15 synthetic ledger tests. `python3 scripts/ci/check-docs.py` and `git diff --check` passed. `bash scripts/ci/verify-build.sh` passed 108 domain and three architecture tests with zero failures/errors/skips; domain coverage 276/277 lines (99.64%) and 478/489 branches (97.75%). Independent agent review found and verified fixes for idle claims, mandatory dependency documentation and claim/state mismatch. Exact-head remote CI remains required. PostgreSQL/Angular/browser/security/recovery suites are not yet implemented; no application/release completion is claimed.

P48 integrated merged P01/concurrency15ba8b4, preserving its validator/tests, delivery dependency document and workflow documentation suite. Integrated verification passed 15 ledger tests, 23 delivery-policy tests and the full 108-domain/three-architecture build with unchanged coverage and no skips.

Coordinator applied live ruleset25814212; initial readback exposed GitHub-added required_reviewers=[] and require_extra_approval_for_unattributed_changes=true. Both are now explicit in managed configuration. A regression verifies provider defaults and refusal to relax the extra-approval requirement. Worker made no live settings changes.

## P40 coverage foundation evidence

Implemented inherited JaCoCo instrumentation/report/check for all Maven modules with independent backend production-module thresholds: domain/application 95% lines and 90% branches, adapters/bootstrap 90% lines and branches. One append-only execution file per module combines unit/integration forks without summing duplicate counters. The shared post-build validator rejects missing reports/counters and omitted source files; it also requires complete Angular coverage-final JSON once handwritten frontend sources exist. Generated exclusions require individual generator-manifest entries and version provenance.

`bash scripts/ci/verify-build.sh` passed locally on 2026-10-25: 108 domain tests, three architecture tests, zero failures/errors/skips; domain 276/277 lines (99.64%) and 478/489 branches (97.75%). The final full build included all 23 new synthetic gate tests, including zero-branch report edge cases. Negative cases include below-boundary line/branch thresholds, per-module masking, omitted production sources, missing reports/counters, invalid counters, Angular map truncation and generated-manifest path traversal. `git diff --check` passed. Final exact-head CI remains required.

Limits: application/adapters/bootstrap are empty scaffolds, and Angular is not yet adopted on this branch; no application or Angular coverage result is claimed. Real PostgreSQL, external acceptance-process coverage merging, browser, load, security and recovery suites remain their owning tasks. Full-project P40 completion remains pending.

P01 concurrency prerequisite merged at 15ba8b4 and was integrated without history rewriting. Its delivery dependency map, validator and test suite are preserved. Integrated local checks passed: `python3 -m unittest discover -s scripts/ci -p 'test_*.py'` ran 38 tests (15 ledger + 23 coverage); documentation validation, whitespace and the full implemented build passed (108 domain + 3 architecture, zero skips; domain 276/277 lines and 478/489 branches). Remote exact-head CI remains required.

P48 integrated P40 foundationbb7d4cf and replaced Python discovery entry points with a tested runner that rejects empty, skipped, failed and expected-failure suites. Guarded root suite runs 15 ledger plus 23 coverage tests; delivery-policy runs 31 tests. Live protection readback passed; coordinator recorded an administrator-verified id/version/config-hash receipt for read-only CI, which omits bypass metadata; absent/stale receipts fail.

P48 public CI exposed equivalent UTC/offset spellings of updated_at; version comparison now normalizes aware ISO timestamps to UTC and rejects missing/malformed/naive values. Millisecond-change and equivalent-offset regressions pass; the administrator receipt remains unchanged.

## P43 secret-scanning foundation evidence

Pinned Gitleaks8.30.1 official release: archive551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb matched both GitHub asset metadata and official checksum sidecar. Executable88f91962aa2f93ac6ab281d553b9e125f5197bbbce38f9f2437f7299c32e5509 was derived from the verified archive. Real canaries/full reachable history/current70tracked files passed with zero findings. Count-only reports omit source/token/path/author metadata; no raw scanner output is published.

Guarded Python suites passed58 root tests (38 existing+20security) and31delivery-policy tests; full build passed108domain+3architecture, zero skips, domain276/277lines(99.64%) and478/489branches(97.75%). Missing/tampered tools, shallow history, missing/stale/malformed/inconsistent reports, canary failures and sensitive report shapes fail. Archive member/checksum and link-text-only tracked copying are tested. Whitespace passed. This completes only a foundation implementation, not P43 acceptance: dependency/SAST/SBOM/container/config categories remain pending. Exact-head CI and independent review remain required.

P43 independent review found symlinked tracked-file ancestors could redirect reads; the wrapper now rejects them before reading/copying, while leaf links remain link-text-only. A synthetic outside-marker regression proves no external bytes are copied. The final58root/31policy/full108+3build and real zero-finding scan passed. Safe receipt upload pins official upload-artifactv4.6.2 at ea165f8d65b6e75b540449e92b4886f43607fa02, verified from its official GitHub tag.

P12 prerequisite integration: P48 PR8 merge0c3ae04 reconciled; all50 root+31 delivery Python tests, full108domain+3architecture build and whitespace passed. Independent reviewed SDK safety fixes retained. Exact-head combined CI required before merge.

## P13 crypto implementation evidence

Selectively adopted bounded AES256-GCM Secrets and a small six-digit/30-second TOTP primitive. Keys require exactly32 raw bytes and safe bounded IDs; final symlinks are refused, temporary key/plaintext buffers cleared, malformed/tampered/oversized envelopes rejected with fixed cause-free errors. Independent TOTP/delivery keys remain caller configuration. No identity, SMTP or password-storage flow is claimed. BC1.86 is test-scoped for RFC9106 and the approved64MiB/3/p1 baseline; OTP1.0.0 is production-scoped. Exact Maven Central POM publisher/SCM/license provenance was checked.

`bash scripts/ci/verify-build.sh` passed108 domain+10 adapter+3 architecture tests, zero skips, plus23 coverage-gate regressions. Domain276/277 lines and478/489 branches; adapters70/73 lines(95.89%) and52/52 branches(100%). Python discovery separately passed38 ledger/coverage tests. Tests cover18 RFC6238 vectors, RFC9106 Argon2id, envelope and key validation/tampering, token/hash shape, adjacent/replayed steps and the library's epoch-millisecond boundary. Argon2 receipt records only parameters and timing, no secrets.

Initial sandbox build stopped at an unavailable write to the Maven cache; approved rerun downloaded dependencies and passed. Independent coordinator review found that java-otp calls Instant.toEpochMilli; replaced the initial Instant-range guard with a bound ensuring all three candidate steps fit epoch milliseconds and added exact last-safe/unsupported tests. The complete rerun above includes that fix. No secret values are included in assertion diagnostics. P48 integration, final independent approval and remote exact-head CI remain outstanding.

P13 delivery checkpoint: integrated P48 at0c3ae04 preserving its strict runners, live protection receipt and31 policy tests. Strict ledger/coverage38 tests, policy31 tests, commit policy and read-only live protection passed; integrated full build retained108+10+3 tests and95.89%/100% adapter coverage. Draft PR12 created; coordinator independently approved security code and millisecond-boundary fix. Added a further regression proving the loaded JCE key remains stable after its file is replaced/removed; final full rerun passed108 domain+11 adapter+3 architecture tests,38 ledger/coverage tests, zero skips; adapter coverage remains70/73 lines52/52 branches.

P43 integrated P12 contract foundation bc64f01 and P13 cryptography34836ed, preserving contracts and delivery-policy jobs alongside mandatory secrets. Integrated checks passed:70 root tests,31policy tests,108domain+11adapter+3architecture, deterministic contract regeneration, full-history/current110tracked-file zero-finding scan and whitespace. Domain coverage99.64%/97.75%; adapters95.89%/100%. Independent re-review approved the ancestor-symlink fix and CI wiring. Exact-head CI remains required.

## P01 standing authorization evidence

Recorded the owner's standing permission for routine approved implementation, tests, dependency downloads, GitHub configuration, task PRs/reviews/pushes/green merges. Tool controls remain binding; deployment, DNS/ingress activation, purchases and live customer communication retain separate manual decisions. Agent instruction validation now requires durable decision/delivery references; a new negative regression checks both files.

All51 root+31 policy Python tests passed; full build108 domain+11 security+3 architecture passed without failures/errors/skips, coverage276/277 lines478/489 branches domain and70/73 lines52/52 branches adapters. Deterministic contract drift, docs and whitespace passed. Independent agent review approved. Exact-head CI remains required.

P43 integrated standing authorization04282fa without history rewriting. Final resolved-index verification passed71root+31policy tests,108domain+11adapter+3architecture, deterministic contracts and zero-finding full-history/105tracked-file scan. Earlier in-progress merge scan counts included duplicate index entries; final105 count is from the resolved staged tree. Domain/adapters coverage unchanged; exact-head CI remains required.

## P43 dependency foundation evidence

Official OSV-Scanner2.7.0 Linux x64 digest160c6253e2922e9e0a6a9771c5c4c40a4b3c6090b34a3cdd5e80c96cc2e4218b matched both GitHub release metadata and its official SHA256SUMS. CycloneDX Maven2.9.3 was verified against Maven Central metadata and executed to produce20 components/21 graph nodes. Real public-feed canary detected known Log4j findings; complete project20package scan passed with zero vulnerabilities. Outputs retain only sanitized Java coordinates/graph and validated advisory IDs.

89 root tests passed, including18 new dependency/installer regressions for source/graph correspondence, exact scanned package coverage, missing/stale/malformed reports, live canary enforcement and safe receipt failures. Current baseline has no adopted PostgreSQL suite; final integration must run every subsequently merged category and record local Docker limits honestly. SAST, Maven plugin/tool classpaths, full release/frontend/container SBOMs and final P43 acceptance remain pending.

## P48 mandatory suite inventory evidence

Added explicit implemented/planned categories and owning task mapping, exact workflow/aggregate prerequisite/results agreement, source activation checks and a release guard that rejects unimplemented categories. Independent review caught job-step/aggregate-step condition and continue-on-error bypasses; regressions now reject ordinary/first-key/flow-style forms while retaining always-run receipt upload.

Local pre-P07 integration verification:83 root+31 policy Python tests,108 domain+11 security+3 architecture full build, coverage276/277 lines478/489 branches domain and70/73 lines52/52 branches adapters, deterministic contracts/docs/whitespace passed. --release deliberately fails while planned automated categories remain. P07 has since merged with real PostgreSQL CI evidence; integration must activate postgres-migrations and obtain fresh combined CI, with localDocker still unavailable. No full-release or P48 publication claim.

## P07 review checkpoint

P06 final head 0318f1a passed all three implemented CI jobs; merge 8d7f62d confirmed. Migration review findings and the next adoption/test commands are preserved in handoffs/p07-migrations.md. No P07 SQL or PostgreSQL suite has been adopted at this checkpoint; existing passing unit/architecture evidence is not migration acceptance. Local Docker daemon remains unavailable.

## P07 implementation checkpoint

Adopted historical SQL changesets 001–006 byte-for-byte from recovery and appended 007. Additive corrections preserve actual-money amounts independently of service pricing, require finite booking times/pending ceilings/confirmation history, validate required snapshot fields and elapsed policy agreement, and prevent deleting or reassigning occupancy rows. Upgrade revalidates existing booking history. Role bootstrap uses separate migration/application credentials, default DML grants and revoked application access to Liquibase metadata.

Exact Liquibase 5.0.4, PostgreSQL JDBC 42.7.14 and Testcontainers 2.0.5 Maven Central POM URLs returned 200 before adoption. `./mvnw --batch-mode --no-transfer-progress -pl backend/adapters -am test-compile` passed with the pinned toolchain. The new MigrationTest covers fresh/upgrade/rerun seeds, rejected inconsistent historical snapshots, immutable facts, real role denials/default grants, overlap/adjacency/state occupancy, finite bounds, snapshot policy/nulls, DST elapsed days and container restart persistence. Resource-only adapters now require reports.

The mandatory full build failed locally inside and outside the sandbox at sanitized POSTGRES_TEST_START_FAILED because Docker has no reachable daemon; 108 domain tests passed before that failure. There is no passing full-build or migration-suite claim. Separate manual SQL checks used an isolated cached PostgreSQL18.6 cluster under /tmp/p07-postgres: migration syntax/seeds and synthetic pending ceiling, null snapshot, overlap, occupancy deletion and DST elapsed-day checks passed. These manual checks do not certify Testcontainers, Compose or the full Java migration suite. Required actual-container CI and independent review remain pending.

P07 integrated P48 protection and P13 cryptography from main34836ed. Preserved both adapter dependency sets and the strict Python test runner. Actual-container CI remains pending.

P07 first actual-container CI40176534341 at eb28055 failed at MigrationTest bootstrap in0.139s before SQL; domain108/security11 and documentation/delivery checks passed. Added controlled phase/exception-class diagnostics and mandatory Docker server/API/pinned-image preflight; no arbitrary provider message logging or weakened dependency gate. Integrated P12 contract foundationbc64f01 and its mandatory contract category.

P07 bootstrap root cause reproduced offline: Testcontainers2.0.5 parses a combined `postgres:18.6-bookworm@sha256:...` string as repository `postgres:18.6-bookworm`, incompatible with PostgreSQLContainer. Use digest-only `postgres@sha256:bbf83594798e144bff365a9c191c5d779f181fb3135902064ab8ff11918020fc`, preserving the exact immutable image. Two pure parser regression tests pass; the real fresh-schema test requires server_version_num180006 and fsync=on (overriding Testcontainers' fsync-off default). Added a dedicated required MigrationTest report gate so unrelated adapter tests cannot mask an absent/incomplete suite; three synthetic report tests pass. Root Python suite now53 tests; test compilation passes. Actual-container CI after this correction remains pending.

P07 integrated standing authorization main04282fa, preserving current owner launch choices and instructions. Targeted parser test invocation initially stopped at strict domain/empty-scaffold selector checks; rerun includes actual domain suites and permits only empty scaffolds to have no selected tests. No CI threshold or suite gate was weakened.

P07 actual-container CI40176952153 at8803d53 ran all11 migration tests,9 passed and2 failed: restart readiness and a later connection setup. Docker can reallocate an ephemeral published port during restart while Testcontainers retains its initial inspect data. The existing restart persistence test now inspects current running state/bindings and reconnects through the fresh endpoint. Added no raw provider diagnostics. Integrated mandatory secret scanning from main8f5ed62. Corrected exact-head container rerun remains required.

P07 final reconciliation: PR7 mergedff3b33b after CI40177367926 at exact head524aa51 passed every implemented category. Real pinned PostgreSQL18.6/fsync-on suite passed11 migration cases including restart; adapters24tests (11security+2image parser+11migration), domain108 and architecture3; coverage unchanged99.64/97.75domain95.89/100adapters. Local Docker remains unavailable; container evidence is CI, distinct from isolated manual SQL evidence.

P43 independent review found npm manifest/lock correspondence missing; the gate now requires exact root identity, dependency/dev/optional declarations and direct pinned resolved versions. Added omission/stale-version/root regression cases. Maven generation now explicitly selects the source root POM so caller working directory cannot change the scanned reactor.

P43 integrated P07ff3b33b and P48inventoryf3a4841, activating dependencies while retaining full SBOMplanned.105 root+31 policy Python tests, deterministic contracts, documentation/inventory/whitespace, full-history127tracked zero-finding secret scan and live protection passed. Real OSV scanned all44 resolved packages with zero vulnerabilities after its live detection canary. Full local build failed as required at POSTGRES_TEST_START_FAILED:CONTAINER_START:IllegalStateException because Docker is unavailable;108domain+11security+2image-parser tests passed before reactor failure. No PostgreSQL passing claim or local build success is made; exact-head CI must run real PostgreSQL and every category before merge.

P43 independent review also found receipt cleanup could follow an artifacts ancestor symlink outside the repository. Both dependency and existing secret gates now use a shared directory-fd/no-follow helper for removal and atomic writes. Three synthetic regressions prove outside-repository sentinels survive ancestor/final symlinks, and hard-link writes replace only the receipt name.108 root tests passed.

## P12 Angular shell evidence

Implemented only the standalone Material shell, real home/not-found routes, keyboard skip destination, system-font purple theme and typed generatedSDK wiring at same-origin /api/v1. No unavailable booking/account/admin links or startup API request. All exported SDK types compile through the test import. Six Angular tests cover shell navigation/landmarks, SDK HTTP/query/error behavior and bootstrap success/failure.

Full `bash scripts/ci/verify-build.sh` passed fresh npmci, six Angular tests, production build,108 domain+11 security+3 architecture tests and63 strict root Python tests with no failures/errors/skips. Source-complete coverage: Angular22/22 lines30/30 branches(100%); domain276/277 lines478/489 branches; adapters70/73 lines52/52 branches. Angular initial bundle307.50kB raw/77.68kB estimated transfer, below750kB error budget; no P41 performance claim. Twelve synthetic report/cleanup regressions reject absent/empty/failed/skipped reports, missing executed cases, missing production output/coverage, omitted bootstrap, phantom case counts and symlinked cleanup roots/targets. Outside sentinel bytes remain preserved.

Initial npmci could not write its cache inside the sandbox; approved retry succeeded. The first Angular run had one synthetic query assertion failure: generated serializer stores Europe%2FBrussels in HttpParams internally. Changed the test to inspect the decoded wire URL, preserving generated code; subsequent full run passed. The initial malformed workspace-construction Python command stopped before repository scaffold writes; corrected command completed. Dependency audit reported0 vulnerabilities. No secret data was used. Mandatory secret-scan/main integration and final independent/remote exact-head review remain outstanding.

Independent coordinator review approved semantic header/landmark assertions, generated Problem fixture alignment, descriptor-anchored cleanup and report-count integrity fixes. Cleanup refuses ancestor/destination symlinks before deleting either artifact tree and never follows outside targets.

P12 PR16 publication checkpoint: P43 secret foundation8f5ed62 and P07 migrationsff3b33b integrated. Secret scan passed complete history+127tracked files, deterministic SDK regeneration passed and31 strict policy tests passed. Local root Python87 tests passed after the nested-symlink cleanup regression. The unchanged mandatory full build after P07 adoption failed at `POSTGRES_TEST_START_FAILED:CONTAINER_START:IllegalStateException` because the Docker daemon is unavailable, with zero skips; it must pass actual PostgreSQL18.6 in remote CI. That same local command passed fresh npmci,6 Angular tests, production output,100% Angular coverage,108 domain+11security+2 PostgreSQL image tests. No combined local full-build passing claim follows. Added pinned upload of only synthetic Angular JUnit and coverageJSON receipts under the mandatory build job.

Final P12 integration: P48 suite-inventory foundationf3a4841 merged; Angular activated atomically as implemented/build while release still rejects planned categories.99 strict root Python+31 delivery-policy tests, inventory/docs/commit policy/live protection and whitespace passed. Final secret scan passed full history+145 tracked files with zero findings. Only AngularJUnit and coverageJSON are retained by a pinned always-running upload step; mandatory build execution is unconditional. ExistingMacIntel/Tailscale/public-ingress and proposedLinuxbackup decisions were preserved. Local actual-container limitation remains as recorded above; require exact-head remote build before merge.

P43 integrated P12 Angularc1151b2. Real end-to-end dependency gate scanned all468 resolved Java/npm packages with zero known vulnerabilities after live detection canary; npm source/lock identity and exact direct-version correspondence passed.121 root+31 policy tests and docs/inventory/whitespace passed. Full local build again failed only PostgreSQL startup with zero skips, after6 Angular tests/production build/100% Angular coverage plus108 domain/11security/2image tests. No local full-build or PostgreSQL passing claim; exact-head combined CI remains required.

Final P43 resolved-tree secret scan passed complete history+150 tracked files with zero findings using the shared anchored artifact helper. Deterministic contracts and live main protection passed; independent re-review approved artifact and manifest fixes. Final PR change remains within800 handwritten lines; exact-head CI is the required PostgreSQL/full-build authority.

## P08 transaction-core checkpoint

Introduced a framework-free SchedulingUnitOfWork command/time/savepoint port with validated actor/operation/target scopes, canonical length-prefixed normalized-field SHA256 fingerprints and closed-code scalar receipts. The JDBC adapter owns READ COMMITTED/REQUIRES_NEW transactions, locks resource1 before one microsecond database-clock capture, replays/completes receipts atomically, validates deferred constraints before successful attempt completion, and translates only the named practitioner overlap barrier. Expected attempt failure rolls back its savepoint; unexpected invariants abort the full transaction. Deadlocks retry at most3 transactions; lock/statement timeouts return fixed cause-free retry failures. No injected operational clock or external provider operation exists.

Pinned Spring Boot4.1.2 BOM was checked at Maven Central and resolves Spring7.0.10. Explicit JUnit6.1.3 import precedes the Boot BOM to preserve its approved version. Targeted domain/application verification passed108+4 tests; application coverage29/30 lines (96.67%) and48/48 branches (100%), with no exclusions. Strict root Python87 and delivery-policy31 tests, documentation, suite inventory and whitespace passed. The full local build compiled the adapter and passed application tests, then failed both mandatory PostgreSQL suites at sanitized unavailable-Docker bootstrap; no full-build or transaction-coverage passing claim. Nine real PostgreSQL core tests and its dedicated required-report guard are ready for CI, alongside all11 original migration tests. Test lifecycle shares the migration fixture under an exclusive JUnit resource lock; no duplicate secret fixtures.

Integrated P12 Angular shell c1151b2 after its ledger conflict initially prevented GitHub from starting PR18 checks. Root independent review found no core blocker and requested the additional real PostgreSQL deadlock-inside-attempt regression; added it alongside the existing outside-attempt bounded retry test. Core report now requires all10 transaction cases. Expected P09 domain business receipts remain a later interface extension; unknown failures abort intentionally.

P08 integrated local verification: strict100 root+31 delivery Python tests, documentation/inventory/whitespace and Java test compilation passed. Fresh Angular install initially failed writing its sandboxed global cache; approved script rerun passed6 real tests, production build and22/22lines30/30branches. Existing domain/application targeted108+4 passing results and96.67%/100% application coverage remain as recorded. Actual10-case transaction PostgreSQL and adapter coverage evidence remains pending remote CI.

First P08 real-container CI40179163547 at50826a9 ran all10 core cases but failed their common prepared receipt lookup; the original11 migration,11 security and2 image tests passed. Offline probing of the exact PostgreSQL42.7.14 Parser confirmed JSONB `?&` is treated as a third JDBC placeholder, while the query supplied only scope/key. Replaced it with `jsonb_exists_all`, preserving required receipt shape checks and exactly2 parameters. Added a real opaque-key/typed-replay regression; all11 transaction cases are now mandatory. [Official pgJDBC query documentation](https://jdbc.postgresql.org/documentation/query/) confirms question-mark operator handling. No provider message was exposed or expected-failure gate relaxed. Corrected actual-container rerun remains required.

P43 integrated P08 transaction/idempotency core6c1f96f. Its resolved Spring7.0.10 dependencies passed the real live OSV gate:472 Java/npm packages, zero vulnerabilities after detection canary.122 root+31 policy tests, documentation/inventory and whitespace passed. Full local build still fails both mandatory PostgreSQL fixtures at unavailable Docker;6 Angular tests/build/100% coverage,108domain+4application+11security+2image-parser tests passed first. Exact-head actual-container CI remains mandatory; no local transaction/migration passing claim.

P08-integrated final secret scan passed full reachable history+155 tracked files with zero findings; deterministic contract regeneration passed. No handwritten feature changes beyond approved dependency/artifact gate fixes; current-main merge carries P08 tests and preserves strict inventory.

## P14 queue foundation evidence

Added a framework-free NotificationQueue lease port with redacted diagnostics and inclusive expiry, plus a JDBC queue adapter for durable claims,60-second leases, per-booking/audience advisory serialization and existing003 exclusion barrier, fenced renewal/completion, attempt recording and six fixed retry delays. No SMTP, event expansion, envelope decryption or P36 reminders adopted. Claim explicitly reclaims expired/malformed leases; row mutations lock before capturing database time. No applied SQL migration changed.

`./mvnw test-compile` passed all seven modules before report guard addition. The unchanged `bash scripts/ci/verify-build.sh` passed fresh frontend install/build and6 Angular tests(22/22 lines30/30 branches),108 domain tests,2 application lease tests,11 security tests,2 PostgreSQL image tests and1 safe-storage test, but failed both mandatory PostgreSQL suite starts at POSTGRES_TEST_START_FAILED:CONTAINER_START:IllegalStateException because local Docker is unavailable. Zero tests skipped. Application coverage3/3 lines with no executable branches; no queue adapter coverage or real integrity passing claim.

101 strict root Python tests passed, including missing/truncated/failed/skipped queue report rejection. The outbox foundation activates atomically in the implemented suite inventory under build, with exact-class/at-least-ten-executed-case evidence required. Ten real PostgreSQL scenarios are compiled for same-audience20-worker races, unrelated-group progress, SKIPLOCKED/advisory contention, restart/fencing/retry/suppression and post-lock clock authority. They reuse the P07 fixture with the shared JUnit resource lock. Independent review and exact-head real-container CI remain required.

P14 independent review follow-up: explicitly set READ_COMMITTED for fresh-snapshot group rechecks rather than trusting datasource defaults; a real-connection default-REPEATABLE_READ test observes SHOWtransaction_isolation at all three commits. The expiry wait test now confirms the worker is blocked through pg_stat_activity using a synthetic unique application name before setting the later expiry and releasing the lock. Revised all-module test-compile and101 root Python checks pass; actual PostgreSQL scenarios remain CI-only until Docker is available.

Coordinator independently approved the explicit isolation/actual-lock barrier follow-up and scoped queue foundation. Real-container queue acceptance and adapter coverage remain pending remote CI; SMTP/auth-delivery remains a separate P14 slice.

P14 initial exact-head actual-container CI40180605912 at2c6fce4 passed all seven required jobs. Real pinned PostgreSQL ran10 queue scenarios plus11 migration and11 transaction-core cases; adapters46 tests, application6, domain108 and architecture3, zero failures/errors/skips. Coverage: domain276/277lines478/489branches; application32/33lines48/48branches; adapters202/205lines151/166branches(98.54%/90.96%); Angular22/22lines30/30branches. Mandatory queue report guard passed. Local dependency scan472 resolved packages reported zero vulnerabilities;124rootPython,31policy, inventory/docs/commit/live checks and full-history164tracked-file secret scan passed. Local Docker limit remains distinct from passing real CI evidence. Final publication checkpoint adds a successful-delivery regression that original due_at is retained, plus the PR19 handoff; final exact-head rerun remains required.

## P08 persistence implementation checkpoint

Added a framework-free read view for catalog/policy/calendar, individual bookings and bounded overlapping reservation queries. Read-only PostgreSQL transactions and mutation transactions share the exact DataSource/JdbcTemplate. Domain-validated JSON reconstruction uses Boot-managed Jackson2.21.7, including numeric legacy and ISO duration forms, strict creator fields and fixed cause-free failures; meeting URLs use the private validated factory and stored-ID agreement. Persisted shape/occupancy records must match derived domain values. Existing snapshots retain their original JSONB representation; semantic snapshot/identity mutation, stale versions and nonmicrosecond writes fail rather than being silently normalized or ignored. Pending ceilings derive from frozen policy; occupancy releases use the captured transaction time. Completed/no-show retain full120-minute occupancy; payment facts are untouched.

Escaped/completed or cross-thread views/transactions fail before database access. No booking use case, quota/charge, financial, contact or configuration mutation was adopted. Ten named real-PostgreSQL cases cover roundtrip/history, legacy snapshots, catalog edits, confirmation/cancellation/outcomes, overlap/move rollback, malformed stored data/missing children, meeting validation and read-only/context guards. Two codec tests cover duration representations and redacted failures; the dedicated suite-report guard requires all10 persistence cases.

Local targeted verification passed108 domain+4 application+15 adapter unit tests (security11/parser2/codec2); strict101 root+31 delivery Python tests, docs/inventory/whitespace and test compilation passed. Actual persistence PostgreSQL and adapter-coverage acceptance remain pending CI; no local full-build passing claim while Docker is unavailable. The P43 dependency category must scan these new Jackson/runtime dependencies before merge.

P08 persistence integrated dependency foundation2c896ff and queue foundatione917ee3 before publication; preserved their mandatory dependencies/outbox guards and all established suite inventory categories. New Jackson runtime modules remain included in the complete resolved dependency scan; no ignore or coverage exclusion introduced.

Integrated persistence prerequisites verified: strict125 root+31 delivery Python tests, docs/suite inventory/whitespace and Java test compilation passed. The complete live OSV gate scanned475 resolved Java/npm packages, including new Jackson modules, with zero known vulnerabilities after its positive canary. No findings were ignored. Actual10-case persistence and per-module coverage acceptance remain mandatory remote-CI evidence.

The integrated targeted rerun also passed the merged queue unit suites:108 domain+6 application+16 adapter tests, zero failures/errors/skips. Final resolved-index secret scan passed complete history+169 tracked sources with zero findings. Actual persistence database checks remain pending.
