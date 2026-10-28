# P32/notification-retry

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-28T18:14:39Z.
- Current task: P32/notification-retry; branch: task/p32-notification-retry; base: main ea7de35; PR URL: pending draft creation.
- Last pushed implementation commit: c7aaeed (PR137, merged ea7de35); uncommitted changes: tested notification retry foundation and ledger ready for first commit.
- What works: fresh-MFA audited idempotent requeue of failed booking jobs, expected attempt count, reason, operator suppression, expiry reclamation and strict effect/replay validation; ordinary worker preserves Message-ID and rechecks relevance. PR137 confirmed merged ea7de35 after 15 hosted checks and independent review.
- What remains: push draft, exact-head review and all hosted gates; HTTP/UI remain separate slices.
- Decisions: fresh persisted MFA before replay; failed booking jobs only, expected attempt count and reason; preserve attempt history, queue one explicit retry and let worker recheck relevance. Authentication jobs and operator-suppressed delivery cannot be retried. No direct SMTP call or new notification content.
- Verification: local326 application tests/coverage,18 real PostgreSQL tests and50 Python mandatory-report tests passed; new adapter41/41 lines and76/80 branches. Initial application explicit-code inventory required the two new business codes; initialPG corruption test attempted immutable-audit update, corrected to separate synthetic audit records. Full reruns passed. Independent working-tree review clean; docs/diff before commit, hosted categories pending.
- Next concrete action: run docs/diff checks, commit/push task/p32-notification-retry, create draft PR and confirm exact-head independent review plus all15 hosted checks.
- Recovery: origin/recovery/2026-10-25-prototype at6f9405b (legacy origin/recovery/2026-10-25-prototype6f9405b); stacked PR dependencies: none.
