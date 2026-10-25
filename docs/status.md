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
