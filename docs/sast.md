# Mandatory CodeQL analysis

P43/sast-foundation adds one required sast job with three mandatory matrix cells:
Java/manual, Python/none and JavaScript-TypeScript/none. All run on every PR/main
revision, including documentation changes; fail-fast is disabled. Dropped languages,
floating action pins, custom query exclusions and debug/database uploads fail policy.
Full SBOM/container/configuration verification remain later P43 work.

The official v4 action resolves to immutable commit9b3254085494b55a0f7c4c2b3d2d52cc712ce370.
Its current CodeQL2.27.3 supports Java25 and the repository's Python/TypeScript syntax.
Exact per-language Linux bundle sizes/SHA256 and official provenance are recorded
in ops/security-tools.lock.json. Preparation downloads only the language-specific
bundle into an owned temporary directory, checks exact bytes/hash and supplies the
verified local tar.zst to the official action. No floating query packs are fetched;
the pinned bundle supplies default plus security-extended queries.

The pinned JDK is exported before initialization. Java clean test-compile forces
actual main/test compilation under CodeQL tracing, without test-skip flags. This
phase does not certify tests: the independent mandatory build still runs actual
PostgreSQL/Angular/unit/architecture categories. Interpreted extraction uses CodeQL's
native none mode after tracked-source validation. Source symlinks are rejected;
only individually proven generated SDK files are exempt from required coverage.

A successful analyze action does not prove an acceptable security result. The SARIF
guard requires the pinned CodeQL version, correct language/category, successful
invocations, nonempty language rules, complete tracked-source artifacts and valid
rule references. The finalized database source archive must also capture the exact
checkout bytes of every expected source: baseline filenames alone cannot certify
that extraction actually parsed the files. Missing/unclassified severity and suppressed results fail. Scores
at least7.0 block high/critical findings; lower classified findings are counted.
Missing/malformed reports or incomplete language cells cannot certify a PR.

The pinned action emits baseline file information without source snippets; the
removed add-snippets input is not used. The guard rejects snippets/full contents.
Raw SARIF goes only to official GitHub Code Scanning, with processing awaited.
Database/debug uploads are disabled; debug runner mode is refused before analysis.
General CI artifacts contain only language/tool/version and source/rule/finding
counts via anchored safe receipt writes, retained14days. No source messages,
provider errors, credentials or raw SARIF are retained as general artifacts.

Local Python regression tests use synthetic SARIF and download bytes. Actual matrix
extraction/query and GitHub upload evidence must pass CI before this slice merges;
a local compile or mocked clean report is not CodeQL acceptance evidence.
