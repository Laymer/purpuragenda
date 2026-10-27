# P25/management-ui (1.0.0 local release lane)

- Agent and UTC checkpoint: Claude-lane-b, 2026-10-27T14:00:00Z.
- Current task: P25/management-ui; branch: task/p25-management-ui; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/98.
- Last pushed implementation commit: 26c5969 (main base); then merged main 621e83d; uncommitted changes: /manage request page, /manage/booking status and two-step cancel, shared booking status component, MANAGE verify continuation, specs.
- What works: management link request with a neutral reply; MANAGE proof redemption continues to /manage/booking; GET /guest/booking with ETag as If-Match; cancel through a FrozenCommand with a UUID Idempotency-Key, explicit retry after uncertain results, 412/428/409 reload and safe messages for 401/403/404/429/5xx; booking flow reuses the shared status component.
- What remains: independent review and hosted gates; reschedule UI after PR88 (availability) merges, then merge main again; PR85 management target stays with lane C integration.
- Decisions: in-memory only (SessionAccess/CommandAccess), no storage or innerHTML; the guard-less pages rely on server authorization; /verify offers an explicit link to /manage/booking rather than auto-navigating.
- Verification: local verify-frontend passes 152 Angular tests, production build and report checks; coverage 614/617 lines 588/633 branches. Docker unavailable locally; backend relies on hosted CI.
- Next concrete action: gh pr checks 98 --watch --interval 45.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and every existing branch preserved; stacked PR dependencies: PR88 (availability) before the reschedule slice.
