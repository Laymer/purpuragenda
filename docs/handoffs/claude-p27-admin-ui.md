# P27/admin-ui (1.0.0 local release lane): administrator re-authentication follow-up

- Agent and UTC checkpoint: Claude-lane-b, 2026-10-27T13:30:00Z.
- Current task: P27/admin-ui; branch: task/p27-admin-reauth; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/99.
- Last pushed implementation commit: 621e83d (main base, PR95 merged); uncommitted changes: reauthenticate hand-off, honest reload messages, comma fix, specs.
- What works: a 403 from the agenda, detail read or approve invalidates the stale session and opens the sign-in form (query reason=reauth plus a validated return target); sign-in returns to the booking; failed reloads after 412/428/409 no longer claim the latest details are shown; approval success survives a failed reload message; Brussels display strips every comma.
- What remains: independent review and hosted gates; confirm-external-payment UI and rejection UI (no API endpoint) are follow-ups.
- Decisions: return targets are limited to /admin and /admin/bookings/<uuid>; the server stays authoritative and the guard is routing convenience only.
- Verification: local verify-frontend passes 182 Angular tests, production build and report checks; coverage 705/708 lines 652/698 branches. Docker unavailable locally.
- Next concrete action: gh pr checks 99 --watch --interval 45.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and every existing branch preserved; stacked PR dependencies: none (PR95 merged).
