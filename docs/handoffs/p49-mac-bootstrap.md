# P49 macOS host bootstrap handoff

- Agent and UTC checkpoint: Codex, 2026-10-25T09:13:19Z.
- Current task: P49/macos-bootstrap; branch: task/p49-mac-bootstrap; base: main; PR URL: pending.
- Last pushed implementation commit: none for this slice; uncommitted changes: initial tested bootstrap/pins/runbook ready for checkpoint; native CI next.
- What works: owner confirmed Intel i9 macOSSequoia15.7.8, DockerDesktop installed; can run local bootstrap. P12 shell mergedc1151b2.
- What remains: tested pinned Tailscale installer/readiness bootstrap and clear host runbook, independent review/CI. Public ingress, live deployment and real Mac acceptance remain manual later.
- Decisions: prefer local bootstrap on Mac rather than SSHcredentials; preserve existing apps/state/volumes; never print tailnet identity/auth JSON/URLs/tokens; stop with nonzero pending status for interactive Mac/tailnet approval.
- Verification: 12 synthetic bootstrap tests and Bash syntax pass; official package checksum matched, embedded installer TeamID parsed. Linux --check refuses unsupported host. Native installation/trust/actual target readiness not executed here.
- Next concrete action: implement safe macOS bootstrap and synthetic command tests.
- Recovery: recovery/2026-10-25-prototype at6f9405b; stacked PR dependencies: none; runtime/SMTP/ingress activation outside this slice.
