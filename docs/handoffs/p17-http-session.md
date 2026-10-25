# HTTP session, origin and CSRF foundation

- Agent and UTC checkpoint: Claude-worker-b, 2026-10-25T19:30:00Z.
- Current task: P17/http-session; branch: task/p17-http-session; base: main; PR URL: recorded on the draft PR for this branch.
- Last pushed implementation commit: none yet; uncommitted changes: first implementation checkpoint (application SessionAccess, REST adapters, bootstrap composition, contract, tests, docs).
- What works: POST /api/v1/session/bootstrap, GET /api/v1/session and CSRF-protected POST /api/v1/session/logout over the merged JDBC sessions (migration 012, no new migration); a fail-closed filter guards every unsafe method; trusted client IP resolution exists but nothing consumes it yet.
- What remains: login and email-issuance endpoints, PostgreSQL throttles (anonymous bootstrap creation is unthrottled until then), scheduled cleanup, Compose wiring of APP_SECURITY_TRUSTED_PROXY_PEERS (the Caddy peer address is the scripts/Compose worker's decision) and Angular consumption of the generated session client.
- Decisions: exact configured Origin only (never forwarded headers); single Origin header, null or repeated rejected; Sec-Fetch-Site must be absent or same-origin; only bootstrap skips session and CSRF; CSRF digest-only so reload re-bootstraps and rotates only the CSRF proof of a live session; cookie __Host-purpuragenda (Secure, HttpOnly, SameSite=Lax, Path=/, no Domain), local modes use purpuragenda-local without Secure; session-only paths lock only the app_session row, never grants or administrators; problems are fixed codes with a random correlation id and no-store.
- Verification: see docs/status.md section P17 HTTP session foundation.
- Next concrete action: run the full native verification, publish the draft PR, wait for hosted checks and independent review; the coordinator merges.
- Recovery: origin/recovery/2026-10-25-prototype at 6f9405b preserved; worktree /private/tmp/purpuragenda-p17-http; stacked PR dependencies: none (PR45 sessions and PR42 bootstrap are merged).
