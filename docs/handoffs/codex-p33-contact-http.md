# P33/contact-http

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-28T14:28:52Z.
- Current task: P33/contact-http; branch: task/p33-contact-http; base: main 929fc23; PR URL: pending draft creation.
- Last pushed implementation commit: 0765b5a (PR126, merged929fc23); uncommitted changes: tested contact HTTP/contract/SDK ready for draft PR.
- What works: PR126 confirmed merged929fc23 after15 hosted checks and independent review, including13 PostgreSQL contact authority/proof/rollback/notice cases and coverage.
- What remains: protected proof request/confirmation HTTP, runtime wiring, contract/SDK and HTTP verification; customer UI follows separately.
- Decisions: proof issuance follows existing email cooldown and CSRF conventions; confirmation requires exact booking authority, strong If-Match and Idempotency-Key. No ownership or login transfer.
- Verification: PR126 run40703981281 all15 green, zero scanning alerts, independent exact-head review0765b5a clean; local304 application/108 domain tests and coverage, adapter compile and42 report-gate tests passed. PostgreSQL/browser tests hosted-only because Docker unavailable locally. Current6 HTTP unit tests,71 contract/report-gate tests, adapter/bootstrap compile, contract regeneration/verification and487 Angular tests/build/CSP passed; Angular2075/2080 lines2262/2388 branches. Independent source review clean.
- Next concrete action: push task/p33-contact-http and create draft PR; require all hosted checks and exact-head review.
- Recovery: verified origin/recovery/2026-10-25-prototype at6f9405b (legacy reference origin/recovery/2026-10-25-prototype6f9405b); stacked PR dependencies: none.
