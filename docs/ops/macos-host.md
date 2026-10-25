# Intel Mac host bootstrap

Owner target: Intel i9 MacBook Pro, macOS Sequoia15.7.8, existing Docker Desktop. The application will run as Linux/amd64 containers, with public medinavie.com ingress reaching the laptop over Tailscale. The current Linux computer is the proposed encrypted off-host backup destination. Public ingress, backup connectivity, Zoom/SMTP settings and live acceptance remain pending.

Run the reviewed checkout's bootstrap from a local Terminal session on the Mac:

```sh
bash scripts/ops/bootstrap-macos.sh --install
bash scripts/ops/bootstrap-macos.sh --check
```

The install command verifies the platform and pinned official Tailscale package, its SHA256, Apple package signature/installer identity and Gatekeeper assessment. It installs through the native installer, validates the app's signature/team/version, then opens Tailscale and Docker Desktop when needed. An existing matching ready installation is reused. Existing different versions or App Store variants require a reviewed update/migration; preserve their configuration. A cached administrator authorization is reused; otherwise the native macOS administrator dialog requests authorization. Enter credentials only in that Mac dialog. The helper quotes the verified package path and keeps credentials out of scripts, command arguments and logs.

Complete Tailscale's device login on the Mac. On Sequoia, open System Settings → General → Login Items & Extensions → Network Extensions and approve the Tailscale extension; approve its VPN configuration when requested. Finish the SSO/tailnet onboarding in the app. This device authorization is interactive without an existing managed-device enrollment. The script prints a fixed pending code and exits2 until the device reports Running/Online; rerunning the check is safe. It never prints status JSON, login URLs, auth keys, account names or device addresses.

`HOST_PREREQUISITES_READY` means the signed pinned Tailscale client reports connected and the explicit local Docker Desktop context reports Linux/x86_64 with at least2CPUs and4GiB. It certifies host prerequisites only. App migrations, HTTPS, ingress ACLs, backups, external suppliers and real calendar/browser checks retain their separate release gates. The check does not install anything; the installation path changes host prerequisites without changing application volumes, DNS or public exposure.

Use Docker Desktop's resource settings if the readiness check reports `DOCKER_LINUX_2CPU_4GIB_REQUIRED`. Keep the laptop powered and review sleep/lid behavior for continuous service. Enable intended login items and rehearse recovery after restart, network interruption and power loss. Tailscale Standalone and Docker Desktop readiness after user login must be included in the real laptop acceptance; preserve FileVault and ordinary OS protections. Measure the conditional RPO/RTO using the actual Mac and backup computer.

P45 will supply hardened operational Compose and private routing; P46/P47 supply encrypted backup/restore; P49's full development/recovery/release runbooks remain unfinished. Application deployment, public ingress/DNS activation and supplier purchases are separate owner decisions. The bootstrap is prepared here; it has not been executed on the owner's Mac, and a public service has not been exposed.

## Provenance and verification

Tailscale1.104.2 is pinned in ops/host-tools.lock.json. The official checksum sidecar matched the downloaded22348402-byte package. The signed archive's embedded leaf certificate identifies `Developer ID Installer: Tailscale Inc. (W5364U7YZB)`; native trust assessment runs on macOS. The mandatory macos-15-intel CI job uses stock /bin/bash, authenticates the actual package and compiles the native authorization helper, then retains a safe verification receipt. It performs no installation or tailnet login. Linux tests use synthetic command boundaries and verify platform refusal, idempotency, pin/signature/identity failure handling, pending login, Docker limits and private-status suppression. They cannot certify native installation or the owner's network.

Native authorization follows [Apple’s do-shell-script guidance](https://developer.apple.com/library/archive/technotes/tn2065/_index.html); Intel CI availability follows [the official runner inventory](https://github.com/actions/runner-images).

Primary references: [official package/checksum service](https://pkgs.tailscale.com/stable/), [macOS installation](https://tailscale.com/docs/install/mac), [Sequoia extension approval](https://tailscale.com/docs/concepts/macos-sysext), [macOS variants/startup behavior](https://tailscale.com/docs/concepts/macos-variants), and [CLI-in-scripts environment](https://tailscale.com/docs/reference/tailscale-cli?tab=macos).
