# Customer booking flow

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-26T23:00:44Z.
- Current task: P24/booking-flow; branch: task/p24-booking-flow; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/83.
- Last pushed implementation commit: 351b50b; uncommitted changes: independently identified race/fragment/IDNA fixes, keyboard focus and regressions.
- What works: real typed email issuance/redemption, memory-only fragment capture before router startup, intake validation, authoritative plaintext review/consent, immutable submission with explicit uncertain retries, and actual booking status rendering are implemented.
- What remains: independent source review, actual PostgreSQL/Mailpit browser journey, current-main full native/hosted/Compose gates. Customer management/account/admin UI remain separate slices.
- Decisions: root owns main/app routes; use generated API and shared access, no mocked backend. Capture and scrub verification fragments before router startup; keep tokens/intake in memory only. No migration or contract edits. Account/admin UI and management changes remain separate slices.
- Verification: full frontend verifier passes100Angular tests and production build; complete handwritten coverage395/399lines385/407branches, strict CSP passes. Log /private/tmp/p24-review-final.log. 248strictPython, documentation and regenerated contract checks pass at the implementation checkpoint. Independent findings reproduced99tests/7failures for late private responses, cleared selection during bootstrap and wrong-path fragments; keyboard91tests/1failure and IDNA100tests/1failure separately reproduced, then all100pass. Sanitized failure counts retained. Full backend/browser checks are not yet claimed.
- Next concrete action: publish corrected implementation checkpoint, obtain fresh independent review and run isolated real browser journey, then all exact-revision gates after dependencies merge.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and every existing worktree/secret/volume retained; stacked PR dependencies:74,75,78;72 confirmed merged8702161; public selection70 confirmed merged6fc7e0a.
