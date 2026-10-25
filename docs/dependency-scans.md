# Dependency advisory foundation

P43/dependency-foundation scans resolved Java runtime/test dependencies and, once
present, the complete npm lock. The mandatory job runs on every PR/main revision,
including documentation changes. It blocks every known vulnerability, which is
stricter than allowing medium/low findings. No baselines, ignored vulnerabilities,
severity waivers or call-analysis suppressions are accepted. SAST, Maven plugin/tool
classpaths, container/configuration scanning and complete release SBOMs remain
later P43 slices; a Java dependency SBOM does not complete the SBOM category.

The lock pins OSV-Scanner2.7.0's official Linux x64 executable digest, cross-checked
against the release asset digest and official SHA256SUMS. CycloneDX Maven2.9.3 was
verified in Maven Central's official metadata and executed successfully. Exact
release/checksum/documentation URLs are in ops/security-tools.lock.json. Public
OSV advisories need no API credential; unavailable feeds/tools/reports fail.

```sh
python3 scripts/dev/install-security-tools.py --tool osv-scanner
source scripts/dev/toolchain-env.sh
python3 scripts/ci/check-dependencies.py
```

The pinned aggregate generator resolves direct and transitive dependencies across
the complete Maven reactor, including tests. It generates metadata only; it does
not certify tests or replace the independent required build/database suites.
The gate validates source reactor identity, every module/direct declaration,
component coordinates and complete dependency graph. It strips author/license/text
metadata before persisting the minimal valid Java CycloneDX1.6 inventory. This
artifact identifies dependency components, not final images or every build tool.

OSV receives that SBOM and frontend/package-lock.json when frontend/package.json
exists. Missing frontend locks, unversioned entries and missing package results
fail. Explicit empty scanner configuration prevents repository suppressions;
all-packages/all-vulns and exact resolved-package correspondence prevent a clean
subset from certifying the build. A known vulnerable Log4j metadata canary proves
live advisory detection before project scanning; no vulnerable code is installed.

Each run verifies scanner bytes/version, removes old receipts and uses temporary
raw reports. Provider stdout/stderr and arbitrary errors are never published.
Only validated package coordinates/advisory IDs and counts reach
artifacts/security/dependencies.json. The sanitized Java inventory is
artifacts/security/java.cdx.json. CI retains these two safe artifacts for14days;
missing artifacts fail, while dependency findings leave a reviewed failure receipt.
Tool/feed failures never leave stale passing evidence.

Official behavior references:
https://google.github.io/osv-scanner/usage/scan-source
https://google.github.io/osv-scanner/output/
https://cyclonedx.github.io/cyclonedx-maven-plugin/makeAggregateBom-mojo.html
