# P32/customer-http

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-28T17:24:19Z.
- Current task: P32/customer-http; branch: task/p32-customer-http; base: main 41056cc; PR URL: pending draft creation.
- Last pushed implementation commit: 48887ce (PR134, merged 41056cc); uncommitted changes: customer HTTP implementation, tests, contract/SDK and ledger ready for first tested commit.
- What works: strict GET /admin/customers and /admin/customers/{customerId}/bookings, runtime wiring, minimal private projections, bounded cursors, contract/SDK and real-MFA HTTP verification. PR134 confirmed merged 41056cc after 15 hosted checks and independent review.
- What remains: push draft, exact-head independent review and 15 hosted checks; UI/reconciliation/retry remain.
- Decisions: GET-only controller with persisted administrator recheck; no-store minimal identity/history projections; canonical bounded cursors, strict query fields and safe CUSTOMER_NOT_FOUND after authorization.
- Verification: local8 HTTP tests including4 real PostgreSQL/MFA,18 RuntimeDatabaseTest cases,78 Python contract/report tests,552 Angular tests/build/CSP (2258/2263 lines,2555/2691 branches), SDK regeneration/check and additions-only contract diff passed. Independent working-tree review clean; docs/diff check before commit. Full backend coverage and all categories remain hosted gates.
- Next concrete action: run python3 scripts/ci/check-docs.py and git diff --check, commit tested slice, push task/p32-customer-http and create draft PR; confirm exact-head review and hosted gates.
- Recovery: origin/recovery/2026-10-25-prototype at6f9405b (legacy origin/recovery/2026-10-25-prototype6f9405b); stacked PR dependencies: none.
