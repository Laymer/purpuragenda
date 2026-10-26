# P18 explicit enrollment network budget and HTTP

- Agent and UTC checkpoint: Codex-worker-a, 2026-10-27T00:55:00Z.
- Current task: P18/enrollment-http; branch: task/p18-enrollment-http; base: main; PR URL: not yet published.
- Last pushed implementation commit: none; uncommitted changes: claim and handoff only, stacked account HTTP fe5c3fb.
- What works: explicit enrollment backend66 and account proof/session73 merged; password81 and account HTTP84 are tested independent stack dependencies awaiting final gates.
- What remains: reserve a real network enrollment budget before Argon, release only after committed success, additive migration018, positive/boundary/replay/rollback/concurrency tests, then bounded explicit enrollment HTTP after contract slot release and all implemented checks/review.
- Decisions: coordinator reserves018;014 belongs to reminders. Append new migration at current changelog end without reordering applied entries. ACCOUNT_ENROLLMENT_IP budget five failures per fifteen minutes per canonical network (IPv6 /64). No invented email identity, no auth bypass, no session from enrollment. Contract held by coordinator for authorized reschedule availability; no OpenAPI/generated changes yet.
- Verification: inherited84 fe5c targeted17domain/5application/19realPG and18contract/31report/docs pass; full native still running. New slice not implemented or verified yet.
- Next concrete action: extend rate kind/network admission and explicit enrollment orchestration with tests, additive018 and actual PostgreSQL upgrade/budget races.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b preserved; old77 and unpublished HTTP prototype worktrees retained; stacked PR dependencies:81/84/80/76 must merge before final main integration. Existing volumes/secrets untouched.
