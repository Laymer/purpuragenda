# Secret-scanning foundation

P43/scanning-foundation introduces a mandatory secret gate on every PR and main
revision, including documentation changes. Dependency scanning, SBOM, SAST,
container/configuration scanning and final P43 acceptance remain pending slices.
This gate makes no vulnerability/severity acceptance claim for those categories.

The pinned Gitleaks 8.30.1 Linux x64 archive is the official project release. The
archive SHA256 was cross-checked against its official checksum sidecar and GitHub
release asset digest; the executable SHA256 was derived from that verified archive.
Both hashes and exact provenance URLs are in ops/security-tools.lock.json. WSL uses
the same Linux x64 artifact; other platforms fail explicitly. No container daemon
or live credentials are required.

```sh
python3 scripts/dev/install-security-tools.py
python3 scripts/ci/check-secrets.py
```

Installation verifies cached/downloaded archive bytes before extracting only the
regular executable member, then verifies its independent digest. Each scan also
verifies executable bytes and version. Unavailable tools, incomplete/shallow Git
history, missing/malformed reports, errors, canary failures and any findings fail.

The gate scans all reachable Git history (`--all`) and a copy of current tracked
working-tree files. Ignored/untracked private local files are not included; stage
new sources before a local pre-push run. A tracked symbolic link is scanned as link
text and never followed to private external contents; symlinked tracked-file
ancestors are rejected. No repository allowlists,
baselines, inline allow comments or automatic repository scanner configuration are
accepted: the wrapper explicitly extends only pinned upstream default rules.
Archives are not recursively unpacked by this source-code foundation; package,
binary and container supply-chain checks belong to later P43 slices.

Every run proves clean and positive behavior with temporary synthetic canaries.
The positive canary is constructed at runtime, never committed or printed. Scanner
stdout/stderr are discarded and redaction is mandatory. A count-only report
template omits source matches, credentials, file paths and author/commit metadata
even from temporary findings reports. Temporary inputs/reports are deleted after
each run. The persistent artifacts/security/secrets.json receipt contains only
version, commit, tracked-file count, finding counts and pass/fail. A prior receipt
is removed before execution, so missing dependencies cannot leave stale success.
CI uploads only the count-only receipt (pinned upload-artifact v4.6.2,14-day
retention); a missing receipt fails. Raw scanner reports are never uploaded.

Official provenance: https://github.com/gitleaks/gitleaks/releases/tag/v8.30.1
and its linked checksum sidecar. Tests use only synthetic inputs and verify
checksum, missing-tool/report, redaction/suppression, result consistency, canary,
history and safe-receipt failures. Any detected issue blocks the PR; remediation
is reviewed privately without publishing suspected values.
