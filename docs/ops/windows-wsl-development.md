# Windows and WSL development prerequisites

The supported development baseline is x64 Windows with a separate Ubuntu24.04 distribution running WSL2 and Docker Desktop's Linux engine. Preserve an existing Kali distribution, its default status and all data. The operational host is the owner's Intel Mac; these instructions concern Windows development only.

Install [PowerShell7.4 or later](https://learn.microsoft.com/powershell/scripting/install/installing-powershell-on-windows) if unavailable. Follow [Microsoft's WSL instructions](https://learn.microsoft.com/windows/wsl/reference) to add Ubuntu24.04 as a separate distribution if it does not exist. For a new distribution, the explicit install command is:

```powershell
wsl --install --distribution Ubuntu-24.04 --no-launch
wsl --distribution Ubuntu-24.04
```

Finish Ubuntu's local user setup in that terminal. Do not put the Linux account password in scripts or this repository. If Ubuntu already exists, reuse it; do not reinstall it. A WSL1 result requires reviewing backups and migration separately. The preflight never changes a distribution's version/default or unregisters a distribution.

Start Docker Desktop. Enable its WSL2 Linux engine and enable integration specifically for Ubuntu24.04 in Settings → Resources → WSL Integration. Preserve other distributions' settings. Follow [Docker's WSL documentation](https://docs.docker.com/desktop/features/wsl); avoid installing a competing Docker Engine inside Ubuntu. Allocate at least2CPUs/4GiB to the Linux engine.

From a reviewed checkout on Windows, run:

```powershell
pwsh -NoProfile -File scripts/ops/preflight-wsl.ps1
```

The script lists distributions without exposing the list, requires exactly one Ubuntu24.04/WSL2 entry, then probes that explicit distribution. It verifies the actual release and x86_64 architecture and the explicit `default` Docker context's local Unix socket. Docker must identify as Desktop/Linux/x86_64 with the minimum resources. An environment-selected remote daemon cannot pass. Each native call is bounded; private Docker facts and provider diagnostics are captured without being printed. It may start the selected Ubuntu distribution to perform these probes; it does not install software, change defaults, restart Docker, create containers or change volumes.

`WSL_DEVELOPMENT_PREREQUISITES_READY` certifies these prerequisites only. A fixed nonzero result identifies the missing prerequisite. Resolve it and repeat the command; do not bypass the check or select Kali implicitly. Arguments are rejected. No elevation or credentials are requested by the preflight.

## Project verification inside Ubuntu

Open the explicit distribution, and keep the checkout on Ubuntu's Linux filesystem for normal permissions and build performance:

```powershell
wsl --distribution Ubuntu-24.04
```

Inside that Ubuntu terminal, use an existing reviewed checkout or clone the project into a new directory in your Linux home. Do not overwrite an existing checkout or copy private state into Git. Run:

```sh
bash scripts/dev/install-toolchain.sh
python3 scripts/ci/run-python-tests.py --start scripts/ci
python3 scripts/ci/run-python-tests.py --start scripts/ci/tests
bash scripts/ci/verify-build.sh
source scripts/dev/toolchain-env.sh
python3 scripts/ci/check-contract.py
python3 scripts/dev/install-security-tools.py
python3 scripts/ci/check-secrets.py
python3 scripts/dev/install-security-tools.py --tool osv-scanner
python3 scripts/ci/check-dependencies.py
```

Toolchain and security-tool downloads require network access and checksum verification. The full build requires a reachable Docker daemon and the immutable PostgreSQL image; unavailable dependencies fail. It runs the implemented Angular/backend/PostgreSQL/architecture suites and enforces per-module coverage. Contract and scan commands retain their strict missing-report/tool/feed checks. CodeQL's pinned three-language analysis and native platform verification also run in required CI; see docs/sast.md. A local build alone is not final release acceptance.

No operational deployment/start command is supplied by this prerequisite slice. Public API, identity, Compose, backup/restore and full browser/load acceptance remain their owning tasks. Preserve all database/container volumes during development and recovery.

## Evidence and remaining acceptance

The mandatory Windows CI job executes18 synthetic preflight cases in native PowerShell. It checks explicit distro selection, localized listing state, unavailable WSL, WSL1/missing/ambiguous Ubuntu, release/architecture mismatch, remote Docker, invalid/private facts and resource limits. Two cases also execute real isolated PowerShell child processes to verify argument preservation, private-stderr suppression, exit codes and bounded timeout termination. The runner emits a count-only report in an exclusively created temporary file; a separate gate requires Windows/PowerShell7.4+, all18 executed cases and zero skips/failures. Missing reports fail. CI does not install or exercise a real WSL distribution for this suite.

Before marking Windows/WSL acceptance complete, exercise the preflight on a real Windows/Ubuntu24.04/WSL2/Docker Desktop installation, run the full project verification including real PostgreSQL, and rehearse a restart. Record only safe pass/fail facts, tool versions and sanitized report references. Verify that the existing Kali/default distribution and data remain preserved. This actual-machine acceptance is pending; native script tests cannot substitute for it. Full P49 development/operations/recovery/release runbooks also remain pending.
