# P14 authentication delivery handoff

- Agent and UTC checkpoint: Codex-p14, 2026-10-25T11:13:02Z.
- Current task: P14/auth-delivery; branch: task/p14-auth-delivery; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/32.
- Last pushed implementation commit: f07ee85; uncommitted changes: current-main integration and resumed ledger reconciliation.
- What works: queue and SMTP foundations merged through PR30 fc06c21; quota008 merged f72bae2.
- What remains: independent review and actual PostgreSQL/coverage CI; worker and queued-expiry sweep follow separately.
- Decisions: fixed15m hashed256-bit tokens; encrypted typed envelope only; explicit purpose/browser/booking binding; no token return; no network or scheduling lock in store.
- Verification: four auth-report regressions, docs/inventory/whitespace pass. Root Python176 fails8+8 and delivery31 fails10errors under unsupported macOS Python3.9; pinned installer rejects macOS/Linux-only archives; verify-build fails toolchain before tests; Docker absent.12 PostgreSQL cases and coverage require Linux CI.
- Next concrete action: complete current-main integration, run all checks, publish and require exact-head CI/independent approval.
- Recovery: recovery/2026-10-25-prototype at6f9405b; stacked PR dependencies: queue19/23, SMTP30 and quota28 merged; P16 recovery draft33 independent.
