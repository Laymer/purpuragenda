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
