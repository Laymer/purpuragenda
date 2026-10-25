# External financial facts

A ledger belongs to one booking and contains append-only verified receipts/refunds with actual amounts, ISO currencies, methods, references and verification instants. Booking approval and payment are independent. Service prices remain EUR and bounded to 100000000 cents; receipt amounts do not inherit that ceiling. No currency conversion is inferred. Aggregation uses checked integer arithmetic.

Each refund links an actual receipt for the same booking/currency, and total refunds cannot exceed that receipt. Duplicate entry IDs or kind/method/reference tuples are rejected. Application/database idempotency and uniqueness across bookings are separate mandatory tasks. No checkout integration exists.

Refund obligations are explicit per receipt, idempotent and bounded by receipt amount. Only outstanding obligation amounts contribute to refund due, separated by actual currency. Requiring refunds again adds follow-up for newly recorded late receipts without duplicating earlier obligations. Targeting one late receipt does not make earlier nonrefundable funds refundable.

Projection order is outstanding REFUND_DUE, all actual funds returned REFUNDED_EXTERNAL, sufficient net service currency PAID_EXTERNAL, otherwise UNPAID. A settled late-receipt refund leaves retained sufficient original money PAID_EXTERNAL. Partial receipts and unmatched currencies stay unpaid. Even a zero-priced service requires an actual matching-currency receipt for an external-paid fact. Remaining amount is bounded below by zero.

A financial fact does not reacquire occupancy or revive a terminal booking. Later commands must evaluate live hold, meeting, booking state and net sufficient funds under the scheduling unit of work, or create reconciliation/refund follow-up. This task verifies calculations, not those application decisions.

## Administrator receipt command

`AdministratorFinance.recordReceipt` runs through `FinanceUnitOfWork`; `PostgresFinance` derives the administrator from the locked persisted ADMIN session (enabled and enrolled administrator, unrevoked, current security epoch, 15-minute idle, eight-hour absolute, authentication within ten minutes; equality is expired) before any idempotent receipt replay. It uses the shared scheduling resource lock, one captured database time and synchronous expiry reclamation. The fact keeps its actual currency and amount (no ceiling, no conversion), must not be dated after the captured time and its kind/method/reference is unique across bookings. A receipt never confirms, extends or revives a booking. On a cancelled, rejected or expired booking it is late: the receipt is still recorded, with a `LATE_RECEIPT` refund obligation and a reconciliation audit/outbox event, and the released occupancy is untouched. Expected business failures commit earlier expiry reclamation; unexpected failures roll back everything.
