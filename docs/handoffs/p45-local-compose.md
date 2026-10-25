# Local Compose initialization and persistence

- Agent and UTC checkpoint: Codex-worker-c, 2026-10-25T16:30:48Z.
- Current task: P45/local-compose; branch: task/p45-local-compose; base: main; PR URL: not yet created. Starting revision6b4544e.
- Last pushed implementation commit: none for this slice; predecessor PR42 exactea712c6 merged6b4544e; uncommitted changes: new claim and predecessor evidence reconciliation.
- What works: executable app/migration runtime and actual PostgreSQL/HTTP readiness, distinct roles/keys and local/restore SMTP restrictions are merged; native tools and Docker are available.
- What remains: Compose images, private idempotent initialization, local.sh up/smoke and mandatory isolated persistence gate. Private operator CLI, automatic AUTH ticks and full test/operational/restore profiles remain separate.
- Decisions: coordinator grants exclusive .github/suites inventory ownership for persistence activation. Preserve named volumes/secrets; loopback8080/8025; no Docker socket in runtime; internal network, nonroot/read-only/dropcaps services. Official registry verified recovery Ubuntu/Caddy/Mailpit digest bytes before selective adoption.
- Verification: predecessor exactea712c6 native383Java/6Angular/200Python, allcoverage and hostedCI40208576893 passed after independent review. New slice not yet implemented or verified.
- Next concrete action: implement secret initialization and Compose/runtime images, add meaningful negative/idempotence and real restart/persistence tests, then run every implemented category and independent exact-head review.
- Recovery: recovery/2026-10-25-prototype at6f9405b; previous bootstrap and native-toolchain worktrees preserved; current/private/tmp/purpuragenda-p45-local-compose; stacked PR dependencies: none.
