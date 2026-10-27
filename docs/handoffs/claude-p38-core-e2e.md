# P38/core-e2e (1.0.0 local release lane): Chromium core journey on an isolated real stack

- Agent and UTC checkpoint: Claude-lane-a, 2026-10-27T16:00:00Z.
- Current task: P38/core-e2e; branch: task/p38-core-e2e; base: main; PR URL: draft pull request opened from this branch.
- Last pushed implementation commit: none before the first push; uncommitted changes: acceptance-tests Playwright module with unit-tested helpers (TOTP, Mailpit link extraction, sanitizer, settings), compose.e2e.yaml, scripts/ci/verify-e2e.py and e2e_support.py, the e2e-core suite and mandatory CI job, verify_local mapping, purpuragenda.sh e2e and docs/local-testing.md.
- What works: Docker-free checks only: 16 Java unit tests, the Python suites (new test_e2e plus the updated suite, local-verify and run-script tests) and the suite/workflow gate. The browser journey itself has not run anywhere yet because Docker is unavailable on the authoring host; hosted CI is its first run.
- What remains: first hosted run and iteration on the real UI, independent exact-head review, then stage 2 (customer management link, cancellation and rescheduling) after the P25 management UI merges.
- Decisions: Java Playwright in the existing acceptance-tests module, with the ordinary build excluding the e2e tag and the orchestrator selecting it. Chromium only; the 15-cell matrix stays P39. Each run owns its Compose project, secrets, network and two free loopback ports, with the backend public origin following the first port. The administrator is enrolled through the real private admin-setup terminal flow and the sign-in code is computed from the key it prints, after waiting for the next time step. Output is scrubbed and failure evidence is a step name plus visible headings, never screenshots, traces, tokens or intake.
- Verification: see docs/status.md; limits are no local Docker and no local browser run of the journey.
- Next concrete action: gh pr checks --watch on the draft PR, then gh run view --log-failed for the e2e-core job.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and all worktrees/volumes retained; stacked PR dependencies: none.
