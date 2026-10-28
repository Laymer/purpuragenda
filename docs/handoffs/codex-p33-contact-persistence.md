# P33/contact-persistence

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-28T14:03:49Z.
- Current task: P33/contact-persistence; branch: task/p33-contact-persistence; base: main 285d124; PR URL: pending draft creation.
- Last pushed implementation commit: be7b6bd (PR125, merged285d124); uncommitted changes: compiled PostgreSQL contact proof/consumption and notices ready for draft PR.
- What works: PR125 confirmed merged285d124 after15 hosted checks and independent review; application boundary preserves stable ownership and immutable booking facts.
- What remains: atomic persisted authority, scoped proof issuance/consumption, old/new-address notices and real PostgreSQL regression tests; HTTP/SDK/UI follows separately.
- Decisions: reuse existing booking_contact_change history keyed by the contact event id; no schema duplication. Reuse locked customer booking authority and the scheduling transaction. No runtime HTTP endpoint until the next slice.
- Verification: PR125 run40700378375 all15 green, zero scanning alerts, independent exact-head reviewbe7b6bd clean; local303 application/108 domain tests and coverage gates passed. Main0f02a29 run40699579785 green. Docker unavailable locally; PostgreSQL tests run only in hosted CI. Current local304 application/108 domain tests and coverage gates passed; adapter/test compilation and42 report-gate tests passed. Independent source review clean; added expiry/invalid-callback tests await exact-head confirmation.
- Next concrete action: push task/p33-contact-persistence, create draft PR and verify all hosted checks plus exact-head independent review.
- Recovery: verified origin/recovery/2026-10-25-prototype at6f9405b (legacy reference origin/recovery/2026-10-25-prototype6f9405b); stacked PR dependencies: none.

- Closed 2026-10-28T14:28:52Z: PR126 confirmed merged929fc23 after15 hosted checks (run40703981281), zero scanning alerts and independent exact-head review0765b5a. Claim released; next P33/contact-http.
