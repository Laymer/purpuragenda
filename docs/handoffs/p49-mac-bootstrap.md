# P49 macOS host bootstrap handoff

- Agent and UTC checkpoint: Codex, 2026-10-25T09:44:04Z.
- Current task: P49/macos-bootstrap; branch: task/p49-mac-bootstrap; base: main; PR URL: pending.
- Last pushed implementation commit: none for this slice; uncommitted changes: native CI, reviewed GUI authorization and metadata integration ready for publication.
- What works: owner confirmed Intel i9 macOSSequoia15.7.8, DockerDesktop installed; can run local bootstrap. P12 shell mergedc1151b2; P08core6c1f96f and P43dependency2c896ff integrated.
- What remains: tested pinned Tailscale installer/readiness bootstrap and clear host runbook, independent review/CI. Public ingress, live deployment and real Mac acceptance remain manual later.
- Decisions: prefer local bootstrap on Mac rather than SSHcredentials; preserve existing apps/state/volumes; never print tailnet identity/auth JSON/URLs/tokens; stop with nonzero pending status for interactive Mac/tailnet approval.
- Verification: 15 synthetic bootstrap tests and Bash syntax pass; independent agent review approved; SSH read-only confirms actual target15.7.8/x86_64, DockerDesktop installed, Tailscale absent, native administrator authorization needed; official package checksum matched, embedded installer TeamID parsed. Linux --check refuses unsupported host. Native CI and actual installation/readiness remain pending; no privileged host changes or public exposure yet.
- Next concrete action: implement safe macOS bootstrap and synthetic command tests.
- Recovery: recovery/2026-10-25-prototype at6f9405b; stacked PR dependencies: none; runtime/SMTP/ingress activation outside this slice.
