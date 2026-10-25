# P45 follow-up: exact secrets, Caddy contract and private admin-setup

- Agent and UTC checkpoint: Claude-workerC, 2026-10-25T20:30:00Z.
- Current task: P45/followup; branch: task/p45-followup-v2; base: main (27e2df1); PR URL: recorded on GitHub (draft).
- Last pushed implementation commit: see the draft PR; uncommitted changes: none once pushed.
- What works: exact per-service secret sets in verify-compose, Docker-less Caddyfile contract test, tolerant frontend marker, receipt without host state path, `local.sh admin-setup` with the profile-gated `operator` service and `AdminSetup` operator workflow (database tests with synthetic credentials), pseudo-terminal `admin-setup` category in verify-compose.
- What remains: root `verify.sh` skeleton, automatic AUTH delivery ticks, two-backend, operational and restore profiles, HTTP login (P17/P22). Retained stack `purpuragenda-local` untouched.
- Decisions: the existing private use cases lacked a runnable form; the smallest bootstrap command (`AdminSetup`, `OperatorApplication`, `admin-setup` argument) was added. Setup is strict: an existing administrator, even unenrolled, is refused.
- Verification: see docs/status.md and the PR for exact results.
- Next concrete action: independent review, hosted checks, coordinator merge.
- Recovery: worktree /private/tmp/purpuragenda-p45-followup; stacked PR dependencies: none.
