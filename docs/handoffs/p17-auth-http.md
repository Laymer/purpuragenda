# Protected authentication HTTP

- Agent and UTC checkpoint: Claude-workerD, 2026-10-26T14:00:00Z.
- Current task: P17/auth-http; branch: task/p17-auth-http; base: main; PR URL: recorded on GitHub (draft slices).
- Last pushed implementation commit: see the draft PR head; uncommitted changes: none intended.
- What works: POST /api/v1/guest/create-proofs, /guest/management-proofs and /guest/redemptions in GuestAuthController behind ApiGuardFilter, with GuestProofs charging the email limits before any lookup, 202 identical for known and unknown contacts, a fixed minimum latency, strict JSON bodies, atomic session rotation through the existing exchange, OpenAPI plus generated SDK, MockMvc and real PostgreSQL tests.
- Not started (honest status): administrator login endpoint (attempt reservation before factor work, server-internal MFA proof, same-handler exchange), administrator recovery request/redemption with persisted binding, and the Angular landing page that strips the email fragment. Only guest proofs are implemented in this slice.
- What remains: slice 2 administrator login (attempt reservation before factor work, server-internal proof and same-handler exchange) and slice 3 recovery request/redemption with persisted binding; the Angular landing page that strips the email fragment and posts the proof is frontend work (P24/P26).
- Decisions: the proof is redeemed only from a POST body; the emailed link keeps it in the URL fragment that never reaches the server; no test bypass or public registration.
- Verification: see docs/status.md and the draft PR.
- Next concrete action: independent review of the exact head, then administrator login slice.
- Recovery: worktree /private/tmp/purpuragenda-p17-throttles on branch task/p17-auth-http; no stashes; stacked PR dependencies: none (PR53, PR57, PR61 merged).
