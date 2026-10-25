# P50 owner release inputs handoff

- Agent and UTC checkpoint: Codex, 2026-10-25T10:33:25Z.
- Current task: P50/release-inputs; branch: task/p50-release-inputs; base: main; PR URL: pending.
- Last pushed implementation commit: none; uncommitted changes: exact approved core text, dated public inputs, pending full notice, strict release receipts/gates and tests ready for draft.
- What works: Mac/Tailscale/Docker host prerequisites accepted; Squarespace registrar/public DNS verified; owner approved privacy core wording including necessary providers; existing Zoom/SMTP settings later; no public ingress server exists.
- What remains: machine-checked input register separating owner-reported/approved core from complete launch gates; full notice/retention/terms, ingress/DNS/HTTPS, supplier settings, backup/recovery, real-client and deployment acceptance remain pending.
- Decisions: preserve exact approved core text; no full-notice/legal-completeness claim or public deployment/purchase authorization inferred. Evidence must be safe and revision-bound for release.
- Verification:179 strict root+31 delivery Python tests, input/docs/suite inventory and whitespace passed locally; release rejects missing full notice/acceptance. All existing implemented categories remain mandatory; exact-head CI and independent review pending.
- Next concrete action: publish draft, integrate current main and require independent review/exact-head all-category CI.
- Recovery: recovery/2026-10-25-prototype at6f9405b; stacked PR dependencies: none; P49 preflight7d7f19e merged with13 checks.
