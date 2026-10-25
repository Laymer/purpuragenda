# P21 administrator money commands

- Agent and UTC checkpoint: Claude-workerF, 2026-10-25T21:30:00Z.
- Current task: P21/finance-commands; branch: task/p21-finance-commands (slice 1, receipts and reconciliation); base: main (3c9b6de); PR URL: recorded on the draft PR. Later slices stack on it: approval/confirmation, then refunds.
- Last pushed implementation commit: see the draft PR; uncommitted changes: none once pushed.
- What works: `AdministratorFinance.recordReceipt` over the typed `FinanceUnitOfWork`; concrete `PostgresFinance` takes the shared resource lock, then the enabled/enrolled administrator row, the persisted ADMIN session and the booking, all at one captured database time. A receipt is one immutable payment fact with audit and outbox effects; it never changes, confirms, extends or revives the booking. A receipt on a cancelled, rejected or expired booking (including a hold reclaimed at expiry equality) is late and also records a `LATE_RECEIPT` refund obligation, never occupancy.
- What remains: slice 2 (manual approval and external-payment confirmation) and slice 3 (refund and explicit refund requirement), then HTTP in P22/P29.
- Decisions: no migration (payment_entry, refund_obligation, audit_event and outbox_event suffice); 013 stays reserved for the P17 throttles. Reconciliation is the refund obligation plus a `PAYMENT_RECONCILIATION_REQUIRED` audit/outbox pair. Fresh MFA is 10 minutes from the session's authentication instant, idle 15 minutes, absolute 8 hours, all with equality expired; failures are AUTHENTICATION_REQUIRED, FRESH_AUTHENTICATION_REQUIRED, FORBIDDEN (non-admin session) or NOT_FOUND (unknown booking). Receipts are not version checked because the money fact is independent of booking edits.
- Verification: see docs/status.md for exact native results and limits.
- Next concrete action: independent review and hosted checks of slice 1, then slice 2.
- Recovery: origin/recovery/2026-10-25-prototype at 6f9405b; worktree /private/tmp/purpuragenda-p21-finance; stacked PR dependencies: none for slice 1.
