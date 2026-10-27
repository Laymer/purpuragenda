# P49/run-script (1.0.0 local release lane)

- Agent and UTC checkpoint: Claude-lane-a, 2026-10-27T14:00:00Z.
- Current task: P49/run-script; branch: task/p49-run-script; base: main; PR URL: draft PR for task/p49-run-script (see STATUS.md P49 row).
- Last pushed implementation commit: first commit of task/p49-run-script; uncommitted changes: none.
- What works: root purpuragenda.sh (help, doctor, setup, up, smoke, admin, down, status, logs, demo-seed, test, verify, e2e), root verify.sh (all/list/help) over scripts/ci/suites.json via scripts/dev/verify_local.py, and scripts/dev/demo_seed.py using only session bootstrap, guest create proof, Mailpit, redemption, booking context, availability and idempotent booking creation. Hermetic Docker-free tests: test_purpuragenda, test_verify_local, test_demo_seed.
- What remains: hosted checks and independent exact-head review; a manual run of demo-seed against a real local stack (no Docker on the authoring host); e2e stays a failing placeholder until the P38/P39 core journey slice; README rewrite is a later PR.
- Decisions: 2026-10-27 local 1.0.0 release scope in docs/decisions.md. verify.sh fails closed for suites a host cannot run (sast and windows-preflight are hosted-only, macos-bootstrap needs Darwin); hosted CI remains the merge gate. down never passes -v/--volumes. demo-seed accepts only http://localhost:8080 with Mailpit on http://localhost:8025.
- Verification: local Python suites for the new modules pass; bash -n passes; shellcheck was unavailable on the authoring host. Hosted results are recorded per PR in docs/status.md.
- Next concrete action: gh pr checks <PR> --watch, then request independent review.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and every existing branch preserved; stacked PR dependencies: none (based on main cc3fe39).
