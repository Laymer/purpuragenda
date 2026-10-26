# P36 slice 1: outbox expansion and booking-notification delivery

- Agent and UTC checkpoint: Claude-workerE, 2026-10-26T12:00:00Z.
- Current task: P36/notifications; branch: task/p36-notifications; base: main (56336ac); PR URL: recorded on the draft PR once opened.
- Last pushed implementation commit: none yet; uncommitted changes: NotificationPlanner/Content/OutboxExpander/BookingNotificationWorker, OutboxStore and BookingNotificationStore ports, Postgres adapters, claimBooking/suppressed queue operations, tests and documentation.
- What works: every outbox type has an explicit handler (CONFIGURATION_CONFLICT creates no job, unknown types become a visible FAILED owner job and never block others). Expansion creates durable CUSTOMER and OWNER jobs with the event version as revision; delivery claims booking jobs only (never AUTH), reads CURRENT booking, contact, meeting and owner at send time, suppresses stale/superseded/expired jobs with fixed codes, sends outside any database transaction with a stable Message-ID (job id) and uses the existing fenced lease and 1m/5m/30m/2h/6h/12h retry then FAILED policy. Bodies carry only reference, service, time, state and the confirmation meeting link: no intake, names, birth data, notes or tokens.
- What remains: slice 2 reminders at 48h and 2h with 60m/15m grace (needs migration 014: a separate scheduled deadline, because due_at is the retry time), calendar attachments, verified contact-change notices, bootstrap composition (P45) and protected owner settings.
- Decisions: no migration in slice 1 (014 is reserved for slice 2; 015 belongs to P18). Owner recipient is the one enabled administrator email. Crash after SMTP acceptance and before acknowledgement resends the same Message-ID after lease expiry: a duplicate is possible and documented, loss is not.
- Verification: see docs/status.md for exact native results and limits.
- Next concrete action: run full native verification, push the draft PR and wait for hosted checks.
- Recovery: origin/recovery/2026-10-25-prototype at 6f9405b; worktree /private/tmp/purpuragenda-p11-admin-create; stacked PR dependencies: none.
