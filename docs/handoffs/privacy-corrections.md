# P34/corrections

- Agent and UTC checkpoint: privacy_corrections, 2026-10-29T19:16:00Z.
- Current task: P34/corrections; branch: task/p34-corrections; base: main 951c087; PR URL: pending tested draft checkpoint; worktree /tmp/purpuragenda-p34-corrections.
- Last pushed implementation commit: none; uncommitted changes: full correction port/application/store/migration020/HTTP/runtime/typed UI/contract/generated SDK/tests/docs implemented locally.
- What works: typed profile/note/intake previews, encrypted actor-bound one-hour proposals, exact version/digest confirmation, atomic export revocation, current intake projection and durable text-free replay facts.
- What remains: clean browser correction receipt, independent exact-head review and all hosted categories/coverage; later erasure/calendar/recovery/local-release gates.
- Decisions: one sequential lane; original intake stays explicitly historical; effective intake uses durable revision ordering; no identity/contact/financial mutations or normal-write invalidation trigger; strict validated return selectors retain correction context after MFA.
- Verification: focused14 correction PostgreSQL+4 real-MFA HTTP+13 customer search+18 Runtime cases and2 application/44 domain passed. Runtime exercises actual correction HTTP.653 Angular tests/build/CSP passed with2604/2609lines and2998/3151branches.368 Python helpers/SDK/docs pass. Initial errors: invalid application fingerprint fixture fixed; HTTP CSRF fixture lacked cookie prefix fixed; uppercase URL kinds rejected by existing lowercase command guard, now canonical lowercase without weakening guard; route inventory/manual label updated. Review found nullable SQL CHECK and prepare-receipt linkage issues; fixed with DB/replay regressions. Final confirm-replay durable-fact regression passed in focused14 PostgreSQL+2 application+44 domain rerun. No gate lowered; browser and hosted exact revision still pending.
- Next concrete action: push coherent draft PR, run clean browser acceptance and request exact-head independent review; coordinator merges only all-green head.
- Recovery: origin/recovery/2026-10-25-prototype at6f9405b; stacked PR dependencies: none.
