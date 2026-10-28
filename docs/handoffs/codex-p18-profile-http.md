# P18/profile-http

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-28T11:45:13Z.
- Current task: P18/profile-http; branch: task/p18-profile-http; base: main 9875b47; PR URL: pending draft creation.
- Last pushed implementation commit: 8c8f812 (PR119, merged9875b47); uncommitted changes: tested HTTP, runtime and generated-client integration ready for draft PR.
- What works: PR119 confirmed merged9875b47 after15 hosted checks and independent review; account profile/history application and PostgreSQL adapter implemented.
- What remains: hosted PostgreSQL/runtime tests, coverage and current-head review; P26 UI follows separately.
- Decisions: separate read and command controllers; account-only scope, strict bodies/query cursors, no-store responses and versioned idempotent profile writes.
- Verification: PR119 run40680576779 all15 green, zero scanning alerts; receipt-binding review finding fixed and exact-fix review8c8f812 clean. Local286 application tests/coverage,39 report-gate tests and adapter compilation passed. PostgreSQL tests passed only in CI. Main6a4d7ff run40678731710 green. HTTP local6 unit tests,66 contract/report tests,396 Angular tests/build/CSP and coverage passed; adapter/bootstrap test compilation passed; independent source review clean.
- Next concrete action: push task/p18-profile-http, create draft PR and verify hosted checks plus exact-head independent review.
- Recovery: verified origin/recovery/2026-10-25-prototype at6f9405b (legacy reference origin/recovery/2026-10-25-prototype6f9405b); stacked PR dependencies: none.
