# P18 optional accounts

- Agent and UTC checkpoint: Codex-worker-a, 2026-10-26T09:00:00Z.
- Current task: P18/accounts; branch: task/p18-accounts-v2 (slice 1, explicit enrollment and credential); base: main (56336ac); PR URL: recorded on the draft PR.
- Last pushed implementation commit: see the draft PR; uncommitted changes: none once pushed.
- What works: `AccountEnrollment` hashes a policy-valid password (15 to 128 code points, the administrator policy and Argon2id scheme) before the transaction and `PostgresAccountEnrollment` consumes a browser-bound ACCOUNT email proof, links or creates the verified stable identity, inserts the account with an immutable login identity (the verified mailbox), the credential and an empty profile, and audits it, all atomically. An existing account is never replaced and its proof stays unconsumed. Guest proofs never create accounts.
- Not started (parked by the owner's wrap-up request): slice 2 login, session creation, password change and session revocation, safe session recovery; slice 3 profile storage and account-only history reads. Nothing of these is implemented or partially committed.
- What remains: slice 2 login (throttled with `RequestThrottle.accountAttempt`, an admin-style reserved attempt, then session creation through the atomic proof pattern), password change with session revocation and safe recovery; slice 3 profile storage and account-only history reads. HTTP and OpenAPI are owned elsewhere.
- Decisions: migration 015 is owned by P18 (014 is reserved for the P36 notifications worker; Liquibase tolerates the gap and the changelog-count tests will need a one-line update when 014 merges). It adds credential columns, an immutability trigger (login identity equals the verified identity email; identity, enrollment time and credential history cannot be rewritten; a new hash needs a higher credential_version) and the ACCOUNT_ATTEMPT throttle policy (five per fifteen minutes). Enrollment does not itself open a session.
- Verification: see docs/status.md for exact native results and limits.
- Next concrete action: independent review and merge of slice 1; then implement slice 2 (login with RequestThrottle.accountAttempt, atomic proof exchange to an ACCOUNT session, password change that revokes sessions) from a fresh branch off main.
- Recovery: origin/recovery/2026-10-25-prototype at 6f9405b; worktree /private/tmp/purpuragenda-p21-finance; stacked PR dependencies: none.

Coordinator takeover 2026-10-26T14:43:26Z: Codex-worker-a owns P18/accounts; branch: task/p18-accounts-v2; base: main. Existing Claude checkpoint is preserved; review/fixes/fresh gates required before merge.
