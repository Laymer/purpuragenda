# Decisions

2026-10-24: Adopt the supplied version 1 plan, including proposed defaults D01–D13 and P01–P18, as implementation requirements. See implementation-plan.md for the full register and external launch gates.

Existing host Java25.0.3/Maven3.9.12/Node24.19.0 are bootstrap tools only; project toolchains are isolated and pinned.

2026-10-24 security adjustment: Vitest/coverage-v8 5.0.1 -> 5.0.5 and Vite override8.3.3. The exact approved Angular build22.2.4 depends on Vite8.3.0, which npm audit reports as affected by GHSA-587h-7wmj-h5pm and related advisories. Vitest5.0.1 is affected by GHSA-xp26-3454-mjgp (maintainer advisory confirms5.0.3 fix). Pin patched compatible releases and rerun build/tests/audit; do not suppress the findings. Upstream: https://github.com/vitest-dev/vitest/security/advisories/GHSA-xp26-3454-mjgp .

Adoptium's version-assets API returned404 for25.0.5+8; the official temurin25-binaries GitHub release contains the planned Linux archive and SHA256 sidecar. Resolve these official assets directly; no JDK-version deviation.

Deployment remains manual. No hosted Sites registration is required for this repository-based Java/PostgreSQL/Angular application. The Sites skill's local-only path applies.

2026-10-25: Approved remaining-work delivery in delivery.md: three concurrent workers with separate task PRs/worktrees, independent agent review and coordinator green squash merges. Every new commit includes a relevant test addition/update. Require per-module/backend and Angular 90% line/branch coverage, retaining 95% lines for domain/application. Keep full implemented suites mandatory on every PR. Launch prerequisites remain owner gates and deployment/purchases require separate manual decisions.

2026-10-25 owner hosting direction: an always-on laptop runs the application, connected by Tailscale to a public ingress serving purchased medinavie.com. Customers need no Tailscale client. Zoom and SMTP already exist; settings will be supplied later. Public ingress, encrypted off-host backup and reviewed legal/retention evidence are not yet verified. Provisioning, DNS and deployment remain manual owner decisions.

2026-10-25 standing authorization: the owner authorizes all routine permissions needed to implement the approved plan, including isolated branches/worktrees, dependency/tool downloads, local and CI tests, required GitHub configuration, draft PRs, independent agent review, pushes and green squash merges. Do not repeatedly request conversational confirmation for this authorized work. This does not disable tool/sandbox/organization controls; use approved persistent command rules where available. Deployment, DNS/ingress activation, supplier purchases and live customer communication remain separate manual release decisions under the approved plan. Never publish secrets or bypass required checks.

2026-10-25 owner hosting refinement: the application host is an Intel i9 MacBook Pro running macOS. Tailscale is not installed; provide automated installation/configuration for the target host, retaining necessary interactive tailnet identity and macOS system approval. The current Linux computer is the proposed encrypted off-host backup destination, not yet configured or verified. No public ingress server has been identified. P45/P46/P49 must cover the Linux container runtime on macOS, startup/sleep/reboot behavior, Tailscale routing to the public medinavie.com ingress and backup monitoring; real target-machine verification remains P50. Official installation/approval references: https://tailscale.com/docs/install/mac and https://tailscale.com/docs/concepts/macos-sysext .

2026-10-25 host setup confirmation: target macOSSequoia15.7.8 on Intel i9 MacBook Pro already has DockerDesktop. Owner can run a local bootstrap or SSH; prefer reviewed local bootstrap for private interactive Tailscale/device authorization. P49 macOS bootstrap is an early independent slice; native host acceptance, ingress and backup provisioning remain P50 gates.
