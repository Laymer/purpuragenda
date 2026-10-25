# Pinned build

Run on Linux x86_64; the supported Windows environment is Ubuntu 24.04 under WSL2. Installation changes only ignored repository-local tool directories. Python 3.12 or newer, HTTPS access, bash and unzip are prerequisites.

```sh
bash scripts/dev/install-toolchain.sh
bash scripts/ci/verify-build.sh
```

The installer validates every archive against ops/toolchain.lock.json before extraction, including cached archives. Existing installations are rebuilt from verified archives rather than trusted through a marker file. Do not run installation concurrently with a build. The wrapper separately validates its Maven download against the committed SHA256. Run `source scripts/dev/toolchain-env.sh` in bash to use the pinned tools interactively.

The six Maven modules establish the domain → application → adapters → bootstrap dependency direction; acceptance-tests and quality are separate test modules. Only architecture tests exist in P02. Empty feature modules are build scaffolding and have no passing feature-test claim. Production classes trigger a required test-suite guard and report check. Missing or skipped architecture reports fail verification. PostgreSQL, HTTP, browser, coverage and operational suites are adopted and made mandatory by their assigned tasks; they are not implemented by this build checkpoint.

Architecture checks enforce Maven module edges and pure JDK dependencies in domain/application, ban JDBC there, and prevent REST controllers from calling outbound/JDBC adapters. Bytecode checks start as each layer gains production classes; module edges and the rejection fixture are checked immediately. Future dependencies must retain these boundaries. Framework and executable application packaging are introduced with their actual implementations.

Every PR, including documentation changes, runs documentation and build checks. The aggregate job requires all implemented categories to succeed; cancelled or skipped prerequisites fail it. Add each new automated category to the aggregate dependency list as it is implemented. No deployment or image publication is performed here.

## Coverage foundation

`bash scripts/ci/verify-build.sh` runs the coverage-gate regression tests and validates source-complete reports after the clean Maven build. Every implemented backend production module requires its own JaCoCo XML report: domain/application require at least 95% lines and 90% branches; adapters/bootstrap require at least 90% lines and branches. A healthy module cannot mask a failing module. Missing reports, counters or source entries fail. Straight-line modules may have no executable branches, supported by their per-line report evidence. Empty scaffold modules make no coverage claim.

The inherited JaCoCo agent appends unit and integration fork execution to one file per module; the verify-phase report analyzes each class once. Integration suites must retain the agent argLine and run before report/check. Tests in separately launched backend processes must merge their execution data into the owning module before final reporting; no external-process acceptance coverage is claimed by this foundation.

When frontend TypeScript is introduced, Angular tests must emit `frontend/coverage/coverage-final.json` with all handwritten production sources, including untouched files and bootstrap code. Configure the test runner to instrument all these sources before invoking the shared gate. It requires 90% lines and branches independently for Angular. Spec/declaration files are not executable production sources. Only individual generated files listed by `.openapi-generator/FILES` with generator-version provenance can be excluded; merely putting a file under a generated directory does not exclude it. Generated files must still pass deterministic generation checks in the contract task.

Run the isolated synthetic gate tests with `python3 -m unittest discover -s scripts/ci -p 'test_coverage.py'`. The production entry point accepts no threshold overrides. Full-project P40 acceptance remains pending until the application and Angular features exist and meet these requirements.

## Angular category

The adopted P12 Angular shell now runs mandatory `npm ci`, Angular tests, production build and fail-closed report/coverage checks inside every full build. See frontend.md for commands, exact pins, generated-file exclusions and evidence paths. No missing dependency/report or skipped test may pass. All handwritten frontend TS, including bootstrap, is covered independently at90% lines/branches; this does not certify later feature journeys or the real-browser matrix.
