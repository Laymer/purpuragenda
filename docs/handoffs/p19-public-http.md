# Public services and availability HTTP

- Agent and UTC checkpoint: Claude-workerD, 2026-10-26T09:00:00Z.
- Current task: P19/public-http; branch: task/p19-public-http; base: main; PR URL: recorded on GitHub (draft).
- Last pushed implementation commit: see the draft PR head; uncommitted changes: none intended.
- What works: GET /api/v1/services (no-cache plus strong ETag and 304) and GET /api/v1/availability (no-store, explicit wire mapping, 120/min/client throttle with Retry-After, safe problem codes) in PublicController; OpenAPI and the generated Angular SDK regenerated; MockMvc tests with DST, month-end, horizon, range, spoof and throttle cases; real PostgreSQL window-edge and header-spoof tests.
- What remains: customer-facing Angular use (P23), booking review binding of service/policy versions (P20), scheduling the SecurityMaintenance hook (left out: it needs a lifecycle owner and shutdown semantics), account/admin routes.
- Decisions: availability is never cacheable and throttle storage failure fails closed (503); malformed requests are charged to the throttle; Compose already sets APP_SECURITY_TRUSTED_PROXY_PEERS to the Caddy proxy address and Caddy overwrites X-Purpuragenda-Client-IP, so no Compose change was needed.
- Review follow-up: throttle filter matches on a normalized path (see docs/status.md); /api/v1/services intentionally unthrottled for v1 (two-row read, no migration available, revisit at P42).
- Verification: see docs/status.md and the draft PR.
- Next concrete action: independent review of the exact head, then coordinator merge when all checks are green.
- Recovery: worktree /private/tmp/purpuragenda-p17-throttles on branch task/p19-public-http; no stashes; stacked PR dependencies: none (PR53 and PR57 merged).
