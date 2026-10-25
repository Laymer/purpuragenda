# P08 transaction-core handoff


- Agent and UTC checkpoint: Codex-p08, 2026-10-25T08:46:12Z.
- Current task: P08/transaction-core; branch: task/p08-transaction-core; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/18.
- Last pushed implementation commit: 646aa50; uncommitted changes: integrate mandatory Angular shell c1151b2 and add deadlock-inside-savepoint regression.
- What works: P07 merged ff3b33b; actual-container CI40177367926 passed11 migration +11 security +2 parser +108 domain +3 architecture tests, zero skips; all implemented categories green. What remains: minimal transaction/idempotency port and JDBC core; booking/policy persistence remains second P08 slice.
- Decisions: explicit READ COMMITTED, resource lock then one database time, savepoint validation before returning success, only known conflicts translated; no operational test clock or network effects under lock.
- Verification: 100 root Python and31 policy tests, docs/suite inventory/whitespace passed;6 Angular tests/build passed with22/22lines30/30branches; targeted108 domain+4 application tests passed, application29/30 lines and48/48 branches; full local build fails at unavailable Docker as required; P07 domain276/277 lines478/489 branches, adapters70/73 lines52/52 branches. Local Docker unavailable; real CI remains mandatory.
- Next concrete action: push draft transaction-core PR, require actual PostgreSQL CI/coverage and independent review.
- Recovery: recovery/2026-10-25-prototype at6f9405b; stacked PR dependencies: none; P07 prerequisite merged. Preserve old transaction adapter only as review reference.

Only transaction/time/savepoint/idempotency core is adopted. Booking/policy/catalog persistence remains the next P08 slice. P09 will extend closed business receipt codes and translate expected domain validation inside attempt; unknown DomainFailure intentionally aborts the whole transaction.
