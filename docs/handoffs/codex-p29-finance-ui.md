# P29/finance-ui

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-28T10:02:33Z.
- Current task: P29/finance-ui; branch: task/p29-finance-ui; base: main d9ed47b; PR URL: pending draft creation.
- Last pushed implementation commit: 23b83ff (PR114, merged d9ed47b); uncommitted changes: tested finance UI awaiting first commit.
- What works: P28 complete; PR114 confirmed merged d9ed47b after15 hosted checks and independent review. Finance command APIs and private projection already merged.
- What remains: Claim released; PR115 confirmed merged bff4b0e after15 hosted checks and independent review.
- Decisions: actual money stays independent of booking state/approval; no booking-version precondition on money entries; no currency conversion.
- Verification: PR114 run40667201562 all15 checks passed, no scanning alerts, clean independent exact-head review of23b83ff. Local303 Angular tests/build/CSP passed. Main3509eb0 Verify40666756645 fully green. Local322 Angular tests/build/CSP passed with1387/1391 lines1431/1516 branches; documentation/diff checks passed. Independent review found a large-amount display rounding defect, fixed using BigInt formatting with EUR/KWD boundary tests; exact-head review ofabb1e93 clean; hosted run40669559347 all15 checks green, zero scanning alerts.
- Next concrete action: continue P30/services-ui from main bff4b0e.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b preserved; stacked PR dependencies: none.
