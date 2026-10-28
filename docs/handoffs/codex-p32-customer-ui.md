# P32/customer-ui

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-28T17:35:40Z.
- Current task: P32/customer-ui; branch: task/p32-customer-ui; base: main f309a7b; PR URL: pending draft creation.
- Last pushed implementation commit: 43871ea (PR135, merged f309a7b); uncommitted changes: implemented customer UI, tests and ledger ready for tested commit.
- What works: explicit administrator customer searches, bounded frozen-query pages, stable-customer booking history, existing detail/private-history links, safe reauthentication and in-memory private state cleanup. PR135 confirmed merged f309a7b after 15 hosted checks and independent review.
- What remains: draft push, exact-head review and hosted checks; reconciliation/retry remain separate slices.
- Decisions: explicit search and bounded pages; in-memory terms/results, stable customer ID history and existing booking links; clear on session invalidation/destroy; no customer search data in URL/storage.
- Verification: local576 Angular tests/build/CSP passed (2338/2343 lines,2625/2762 branches), docs/diff passed; independent working-tree review clean. Initial2 new test assertions expected decoded SDK parameter internals; corrected to inspect decoded wire URL and full rerun passed. PR135 all15 green and zero alerts, exact-head43871ea reviewed; hosted checks for this slice pending.
- Next concrete action: record frontend coverage totals, run docs/diff checks, commit/push task/p32-customer-ui and create draft PR; confirm exact-head review and all15 hosted checks.
- Recovery: origin/recovery/2026-10-25-prototype at6f9405b (legacy origin/recovery/2026-10-25-prototype6f9405b); stacked PR dependencies: none.

Claim released: PR136 confirmed merged badd5eab4961f8e2d1420729ea8b10ab9844792b after15 hosted checks, zero alerts and independent exact-head review99ee0ed; Codex-coordinator 2026-10-28T17:54:37Z. Next claim P32/operations-reads.
