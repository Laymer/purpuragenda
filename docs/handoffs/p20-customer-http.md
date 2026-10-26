# Customer booking HTTP

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-26T18:45:00Z.
- Current task: P20/customer-http; branch: task/p20-customer-http; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/72.
- Last pushed implementation commit: 1f4995b; uncommitted changes: append-only confirmed query/main integration and reviewed conflict-refresh fixture correction.
- What works: protected create/GET/cancel/reschedule composition and explicit strict wire models; real proof/session/CSRF/transaction tests; four OpenAPI operations and regenerated typed client.
- What remains: full integrated native/coverage/hosted/Compose/exact review; public reviewed terms and all customer UI remain separate required slices. Query PR69 is confirmed merged f99a8dd; notification64 final integration precedes final HTTP gates.
- Decisions: runtime combines customerOrder11 and administratorOrder12 with strict12-case guard. Credentials/intake remain in memory and request-only models. Generated allowedActions uses Array matching JSON; no unhydrated Set. Existing coverage thresholds and source completeness remain unchanged.
- Verification: targeted5wire/3controller/5actualPGHTTP and12runtimePG pass after Docker recovery; fresh14Angular tests/build pass with handwritten22/22lines30/30branches.14contract cases and regeneration pass. Earlier Docker interruption and contract assertion/generator mismatch remain documented failures; full current-head gates pending. PR71 confirmed merged d96e0a6 after921Java/11Angular/237Python,14hosted/Compose6/exact review.
- Next concrete action: publish draft, run all implemented categories and exact review; integrate confirmed query main before final acceptance.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and all worktrees/secrets/volumes preserved; stacked PR dependencies: PR69 and PR71 confirmed merged; notification64 integration pending before final verification. No full local milestone claimed.
