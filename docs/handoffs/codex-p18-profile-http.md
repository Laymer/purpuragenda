# P18/profile-http

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-28T11:45:13Z.
- Current task: P18/profile-http; branch: task/p18-profile-http; base: main 9875b47; PR URL: https://github.com/Laymer/purpuragenda/pull/120.
- Last pushed implementation commit: 9b4c27d (PR120); uncommitted changes: ledger reflects draft and clean exact-head review.
- What works: PR119 confirmed merged9875b47 after15 hosted checks and independent review; account profile/history application and PostgreSQL adapter implemented.
- What remains: hosted PostgreSQL/runtime tests, coverage and current-head review; P26 UI follows separately.
- Decisions: separate read and command controllers; account-only scope, strict bodies/query cursors, no-store responses and versioned idempotent profile writes.
- Verification: PR119 run40680576779 all15 green, zero scanning alerts; receipt-binding review finding fixed and exact-fix review8c8f812 clean. Local286 application tests/coverage,39 report-gate tests and adapter compilation passed. PostgreSQL tests passed only in CI. Main6a4d7ff run40678731710 green. HTTP local6 unit tests,66 contract/report tests,396 Angular tests/build/CSP and coverage passed; adapter/bootstrap test compilation passed; independent exact-head review of9b4c27d clean; later integration inspection found legacy enrollment empty-profile placeholder, fixed with narrow version1 compatibility and regressions; fix review pending.
- Next concrete action: run `gh pr checks 120`; require all15 checks and no scanning alerts before merge. Then add missing enrollment HTTP/wiring before P26 UI.
- Recovery: verified origin/recovery/2026-10-25-prototype at6f9405b (legacy reference origin/recovery/2026-10-25-prototype6f9405b); stacked PR dependencies: none.
