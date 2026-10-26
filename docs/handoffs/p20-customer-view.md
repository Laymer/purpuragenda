# Customer booking query integration

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-26T18:42:00Z.
- Current task: P20/customer-view; branch: task/p20-customer-view; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/69.
- Last pushed implementation commit: c02ddb3; uncommitted changes: append-only main integration with confirmed enrollment66 and administrator login71; production query unchanged.
- What works: PR67 durable AUTH, PR68 guest HTTP, PR66 enrollment and PR71 administrator login are confirmed merged. Query19 actual PostgreSQL cases and4application cases previously passed at c02ddb3.
- What remains: fresh integrated query gates, stacked customer HTTP, account authentication, notification delivery and remaining local v1 workflows/acceptance.
- Decisions: preserve every authority report guard and both evidence histories; no migration or contract change in this query slice. Customer HTTP remains separately stacked and unpublished.
- Verification: old-head c02ddb3 native876Java/6Angular/234Python, all14hosted checks, clean localCompose6 and exact independent review passed. These are not evidence for the new integration head. PR71 exact ba8e1f0 fresh native921Java/11Angular/237Python plus all14hosted/Compose6/review passed before confirmed merged d96e0a6.
- Next concrete action: publish integration, run all implemented categories and obtain fresh exact-head review before merge.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and all worktrees/secrets/volumes preserved; stacked PR dependencies: none for query; HTTP depends on this query and merged PR71; account-auth017 also stacks query.
