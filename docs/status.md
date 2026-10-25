# Implementation evidence

Baseline inspected 2026-10-24: main at48b544dac501b9da5e304e9ba57451a668a83f06; README.md and .gitignore only; no applicable ancestor AGENTS.md. Original content preserved.

## Evidence authority

Task advancement is maintained only in ../STATUS.md. The records below are historical observations from the recovered prototype, not verified results for adopted PRs. No deployment, customer communication or supplier purchase occurred.

## Historical environment observations

Docker CLI 28.5.2 existed but could not connect to a daemon. Java 25.0.3, Maven 3.9.12, Node 24.19.0 and npm 11.19.0 were observed. Network artifact access worked after an environment permission update. These observations require fresh verification when their task is adopted.

## P01 evidence

Recovery commit 6f9405b preserves 201 files / 18251 inserted lines on recovery/2026-10-25-prototype. The staged gitleaks scan reported no leaks across approximately 954546 bytes. Ignored secrets and generated local outputs were excluded. P01 is based on original main 48b544d; recovery is not an implementation PR.

P01 local verification: `python3 scripts/ci/check-docs.py` passed (50 tasks, valid states and required handoff). Six isolated negative mutations were rejected: extra column, duplicate ID, invalid state, missing handoff field, merged status without PR and missing required document. The first negative-test harness incorrectly replaced prose rather than a state cell; corrected harness passed all six. `git diff --check` passed. No Java, Angular, database or browser implementation is adopted by P01, so their historical results are not passing evidence for this PR.
