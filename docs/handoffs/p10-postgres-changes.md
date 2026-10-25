# P10 PostgreSQL customer cancellation and rescheduling adapter

- Agent and UTC checkpoint: Claude-worker-a, 2026-10-25T19:30:00Z.
- Current task: P10/postgres-changes; branch: task/p10-postgres-changes; base: main (27e2df1, merged application commands PR47); PR URL: recorded on the draft PR once opened.
- Last pushed implementation commit: none yet; uncommitted changes: PostgresBookingChanges, shared PersistedSession rows, BookingChangesPostgresTest, reviewer-note application tests and this handoff.
- What works: the concrete BookingChangesUnitOfWork derives authority only from locked persisted rows (exact-booking MANAGE grant, directly or through the consumed creation grant's single live child, or the account owning the booking), checks it before any idempotent receipt, rechecks session expiry/revocation/browser ownership under the lock and applies cancellation and rescheduling atomically with 120-minute occupancy, audit, outbox and refund obligations. Unknown, unowned and differently scoped bookings fail identically with FORBIDDEN.
- What remains: independent review of the exact head, hosted checks, and protected HTTP exposure (P20). No migration was added; the next number remains 013.
- Decisions: reuse PostgresScheduling.resolved for the resource lock, captured time, savepoint and receipt storage. Expected failure receipts commit earlier expiry reclamation while the attempted mutation rolls back to the savepoint; unexpected failures roll back everything. A successful receipt must carry exactly its audit/outbox effects (verified before commit). Guest history authority does not exist. Customer cancellation obligations cover each receipt's unrefunded remainder with reason CUSTOMER_CANCELLED.
- Verification: see docs/status.md for the exact native results and limits.
- Next concrete action: run the full native verification, push the draft PR and wait for hosted checks.
- Recovery: origin/recovery/2026-10-25-prototype at 6f9405b; worktree /private/tmp/purpuragenda-p10-adapter; stacked PR dependencies: none (application PR47 is merged).
