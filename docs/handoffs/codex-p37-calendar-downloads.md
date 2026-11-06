# P37/calendar-downloads

- Agent and UTC checkpoint: codex, 2026-11-06T10:42:40Z.
- Current task: P37/calendar-downloads slice2; branch: task/p37-calendar-downloads; base: main 6ad38f0; PR URL: pending first push.
- Last pushed implementation commit: none for slice2; uncommitted changes: application download use case, fixed private customer/owner HTTP routes, runtime composition, OpenAPI/generated clients, authorization/cache/expiry tests and prior slice1 closure notes.
- What works: current persisted account/guest/MFA authority reused, fixed audience, captured database DTSTAMP, expired pending projection without mutation, no-store/attachment/privacy headers, no304/partial caching, authorized HEAD and privacy barrier. Three real authorization suites are mandatory in PostgreSQL reports.
- Decisions: no schema or notification change; UI/Google actions and unified calendar reporting remain sequential P37 work. Generated SDK97 changed lines separate from handwritten code/contracts/tests.
- Verification: focused57 Java/runtime tests pass with no skips; frontend668 tests/build/CSP and unchanged coverage2671/2676lines,3128/3292branches pass;373 pinned Python checks and contract regeneration/drift verification pass; check-docs and diff-check pass. Working-source independent calendar_download_review found no blocking issue. Full hosted categories/coverage and exact committed-head review remain required.
- Limits and fixes: first Python run found the expected operation allowlist missing both new endpoints and the new report suites absent from nested-testcase validation; added registrations and regressions, all373 now pass. No dependency, gate or threshold weakened. No full local-build claim.
- What remains: push draft PR, final-head review, all hosted gates and expected-head squash merge; then UI/Google and unified calendar reporting. P37 remains incomplete and real-client imports remain manual launch gates.
- Next concrete action: push tested checkpoint, open draft PR, review exact head and monitor all hosted categories.
- Recovery: task/p37-calendar-model at03da16b, base main6ad38f0; prototype6f9405b reference only; stacked PR dependencies: none.
