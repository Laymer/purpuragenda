# Guest email redemption

- Agent and UTC checkpoint: Codex-worker-a, 2026-10-25T13:53:02Z.
- Current task: P15/email-redemption; branch: task/p15-email-redemption; base: main; PR URL: not yet published.
- Last pushed implementation commit: none; uncommitted changes: guest access port/adapter, additive011,13real PostgreSQL cases,2unit cases and mandatory-report regression.
- What works: baseline main 00cdd57 contains atomic encrypted authentication delivery and fenced worker components; GitHub PR37 merged.
- What remains: production-master integration after reserved010, full pinned/all-category checks and independent review; management recovery issuance and P17 session integration follow.
- Decisions: CREATE proves normalized stable identity; MANAGE uses the booking owner and current delivery address without transferring identity; ACCOUNT/CONTACT are not guest authority. Coordinator allocated011;010 remains P16-owned. Derived MANAGE preserves original CREATE provenance and is capped at original issuance+2h. No public endpoint or session authority. Temporary test-only execution of011 awaits production-master ordering.
- Verification: actual local DockerDesktop29.6.2/PostgreSQL18.6 all13guest cases pass, including two20-way races;108domain+54application pass on hostJava25.0.2/Maven3.9.12 as supplemental evidence only.12mandatory-report regressions/docs/whitespace pass. Initial011extra-parenthesis SQLSTATE42601 failure fixed; sanitized receipt artifacts/p15-failure/guest-migration-syntax.xml. Supplemental full host Java108domain/54application/155adapters/3architecture and all backend coverage passed. Native pinned Angular6cases100%, contracts,185strict Python/31policy, live protection and inventory passed; full native combined build and hosted categories remain pending.
- Next concrete action: publish draft checkpoint, integrate010then011 through production Liquibase, run all implemented checks and obtain independent review.
- Recovery: recovery/2026-10-25-prototype at6f9405b; stacked PR dependencies: none; P17 and P09 must integrate the persisted grant atomically.
