# P18/enrollment-routes

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-28T12:09:09Z.
- Current task: P18/enrollment-routes; branch: task/p18-enrollment-routes; base: main 133be60; PR URL: pending draft creation.
- Last pushed implementation commit: 83e8cdf (PR120, merged133be60); uncommitted changes: tested enrollment routes, contract/SDK and runtime integration ready for draft PR; prior failed-run evidence retained.
- What works: PR120 confirmed merged133be60 after15 hosted checks and independent review; account profile/history HTTP and SDK complete; enrollment application/database and hashing budget foundations merged.
- What remains: hosted database/runtime tests and current-head review; P26 UI follows.
- Decisions: enrollment remains explicit and does not grant a session; use existing browser-bound ACCOUNT challenge and password hashing budget; new route branch avoids preserved historical PR90 worktree task/p18-enrollment-http.
- Verification: PR120 run40683541375 all15 green and zero scanning alerts; independent exact-head review83e8cdf clean. Prior run40683293977 failed four real-enrollment placeholder assertions; fixed and recorded. Local6 HTTP/66 Python/396 Angular tests and build/coverage passed; PostgreSQL/runtime only CI. Main9875b47 run40682329947 green. New local289 application tests/coverage,5 HTTP unit tests,68 contract/report tests,396 Angular tests/build/CSP/coverage passed; adapter/bootstrap tests compile; independent source review clean.
- Next concrete action: push task/p18-enrollment-routes, create draft PR and verify hosted checks plus exact-head independent review.
- Recovery: verified origin/recovery/2026-10-25-prototype at6f9405b (legacy reference origin/recovery/2026-10-25-prototype6f9405b); stacked PR dependencies: none.

Claim released: PR121 confirmed mergedd0fe1ef after15 hosted checks (40686481210), zero scanning alerts and clean exact-head independent review7f11032. P18 complete; next claim P26/account-access.
