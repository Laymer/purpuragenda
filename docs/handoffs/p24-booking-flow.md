# Customer booking flow

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-26T23:02:00Z.
- Current task: P24/booking-flow; branch: task/p24-booking-flow; base: main; PR URL: none yet.
- Last pushed implementation commit: parent integration2ff4f73; uncommitted changes: booking-flow implementation, regression tests and this evidence checkpoint.
- What works: real typed email issuance/redemption, memory-only fragment capture before router startup, intake validation, authoritative plaintext review/consent, immutable submission with explicit uncertain retries, and actual booking status rendering are implemented.
- What remains: independent source review, actual PostgreSQL/Mailpit browser journey, current-main full native/hosted/Compose gates. Customer management/account/admin UI remain separate slices.
- Decisions: root owns main/app routes; use generated API and shared access, no mocked backend. Capture and scrub verification fragments before router startup; keep tokens/intake in memory only. No migration or contract edits. Account/admin UI and management changes remain separate slices.
- Verification: full frontend verifier passes90Angular tests and production build; complete handwritten coverage383/387lines368/394branches, strict CSP passes. Log /private/tmp/p24-flow-frontend-final.log. Initial compile/fixture and stale shell assertions were corrected; final pass has no failures or skips. Full backend/browser checks are not yet claimed.
- Next concrete action: publish tested implementation checkpoint, resolve independent review findings and run isolated real browser journey, then all exact-revision gates after dependencies merge.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and every existing worktree/secret/volume retained; stacked PR dependencies:74,75,78;72 confirmed merged8702161; public selection70 confirmed merged6fc7e0a.
