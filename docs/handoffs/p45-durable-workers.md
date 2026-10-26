# Durable runtime worker composition

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-26T14:43:26Z.
- Current task: P45/durable-workers; branch: task/p45-durable-workers; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/67.
- Last pushed implementation commit: Claude main f654c53, documentation PR65 confirmed merged7f0c4a7 after independent review and all14checks; uncommitted changes: reconciled ledger/claims and durable-worker implementation claim, runtime worker lifecycle, independent AUTH/cleanup lanes and real SMTP restart tests implemented.
- What works: merged PostgreSQL scheduling/customer changes, public services/availability, session/origin/CSRF, throttles/cleanup, private admin operator, finance and schedule commands. Claude local proof/account and notification checkpoints are preserved in three separate worktrees.
- What remains: automatic AUTH delivery and bounded security cleanup composition, lifecycle/failure tests and all required categories; protected business HTTP, workflow UI and remaining fullv1 are still incomplete.
- Decisions: resume approved localv1, not the old handoff task; preserve secrets/volumes/history. WorkerB owns guest-auth OpenAPI, workerA account015/016, workerC notifications with014 reserved; root owns bootstrap lifecycle composition. SMTP remains localMailpit.
- Verification: PR65 exact96384ee independent review and CI40336183781 all14green, GitHub confirms merge7f0c4a7 at2026-10-26T14:42:11Z. 8lifecycle and10actual runtime PostgreSQL cases pass, including real pinnedMailpit delivery after restart and restore suppression. Full native/hosted/Compose/review remain pending.
- Next concrete action: compose bounded AUTH worker ticks and cleanup with graceful lifecycle, add deterministic lifecycle/failure and actualPostgreSQL delivery tests, then full gates and independent review.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and all worktrees/stashes preserved; auth32675ea local, accountsae55b4b local, notificationsPR64 at5309317; stacked PR dependencies: none for initial AUTH composition.
