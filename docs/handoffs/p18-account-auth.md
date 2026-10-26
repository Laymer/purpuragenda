# P18 account authentication and credential rotation

- Agent and UTC checkpoint: Codex-worker-a, 2026-10-26T15:50:00Z.
- Current task: P18/account-auth; branch: task/p18-account-auth; base: main; PR URL: not yet created.
- Last pushed implementation commit: predecessor PR66 exacta8862aa confirmed merged5d7d8be; uncommitted changes: this claim before account authentication implementation.
- What works: explicit enrollment/credentials plus additive015/016 are merged after899Java/10Angular/235Python, all14hosted, localCompose6 and independentreview. Old enrollment worktree stays clean/preserved.
- What remains: atomic account authentication/session rotation and password-change revocation backend, positive/boundary/authorization/rollback/race coverage, all categories and independent review. Profile/history and HTTP remain separate.
- Decisions: coordinator allocated017;014 remains reserved for notifications,015/016 unchanged. Normalize Input.email before credential lookup and throttle. Use browser/request-bound proof and persisted credential epoch; no standalone boolean creates authority. No OpenAPI or frontend edits while contract owner works. Extend customer read/mutation/session authority for epochs; P20 query source must integrate afterPR69 lands before final account-auth merge.
- Verification: no new account-auth implementation or test pass claimed yet. Predecessor results are evidence only of enrollment.
- Next concrete action: define typed inward authentication port and017 persisted proof/session epoch, implement replay/revocation-safe proof exchange and credential rotation; keep core slices bounded and request review.
- Recovery: origin/recovery/2026-10-25-prototype at6f9405b; predecessorworktree/private/tmp/purpuragenda-p21-finance at a8862aa; current/private/tmp/purpuragenda-p18-account-auth; stacked PR dependencies: customer query PR69 must be integrated for complete epoch validation before merge.
