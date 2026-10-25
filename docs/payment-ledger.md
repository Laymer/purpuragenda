# External financial facts

A ledger belongs to one booking and contains append-only verified receipts/refunds with actual amounts, ISO currencies, methods, references and verification instants. Booking approval and payment are independent. Service prices remain EUR and bounded to 100000000 cents; receipt amounts do not inherit that ceiling. No currency conversion is inferred. Aggregation uses checked integer arithmetic.

Each refund links an actual receipt for the same booking/currency, and total refunds cannot exceed that receipt. Duplicate entry IDs or kind/method/reference tuples are rejected. Application/database idempotency and uniqueness across bookings are separate mandatory tasks. No checkout integration exists.

Refund obligations are explicit per receipt, idempotent and bounded by receipt amount. Only outstanding obligation amounts contribute to refund due, separated by actual currency. Requiring refunds again adds follow-up for newly recorded late receipts without duplicating earlier obligations. Targeting one late receipt does not make earlier nonrefundable funds refundable.

Projection order is outstanding REFUND_DUE, all actual funds returned REFUNDED_EXTERNAL, sufficient net service currency PAID_EXTERNAL, otherwise UNPAID. A settled late-receipt refund leaves retained sufficient original money PAID_EXTERNAL. Partial receipts and unmatched currencies stay unpaid. Even a zero-priced service requires an actual matching-currency receipt for an external-paid fact. Remaining amount is bounded below by zero.

A financial fact does not reacquire occupancy or revive a terminal booking. Later commands must evaluate live hold, meeting, booking state and net sufficient funds under the scheduling unit of work, or create reconciliation/refund follow-up. This task verifies calculations, not those application decisions.
