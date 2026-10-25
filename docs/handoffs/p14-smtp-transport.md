# P14 SMTP transport handoff

- Agent and UTC checkpoint: Codex-p14, 2026-10-25T10:21:07Z.
- Current task: P14/smtp-transport; branch: task/p14-smtp-transport; base: main; PR URL: pending.
- Last pushed implementation commit: none for SMTP slice; uncommitted changes: MailDelivery port/Message tests, SmtpDelivery adapter and adapter POM plus this claim; all local only, no SMTP PR yet.
- What works: queue foundation PR19 mergede917ee3, exact real PostgreSQL/coverage CI green.
- What remains: resumed after corrective PR23 merged6afe0e1; actual positive STARTTLS/SMTPS, trusted/untrusted/hostname-mismatch and loopback tests; encrypted-auth worker follows separately.
- Decisions: pure-JDK redacted message/result port; restore zero network; capture only loopback or literal private mailpit; operational required TLS/identity/timeouts/debug-off; no P36 templates.
- Verification: preserved SMTP source compiles; targeted domain/application tests including two MailDelivery tests passed; no SMTP transport passing claim until actual TLS tests/gates.
- Next concrete action: checkpoint tested port/WIP, integrate current main, implement actual TLS tests and privacy acceptance.
- Recovery: recovery/2026-10-25-prototype at6f9405b; stacked PR dependencies: P13/P07/P08/P14 queue merged; P43 dependency/SAST, Mac bootstrap and P09 availability gates preserved from current main; queue correction6afe0e1 merged.
