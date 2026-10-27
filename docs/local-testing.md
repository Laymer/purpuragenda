# Local testing guide

What you can run and check on this Mac today. This is **not** a full v1 demo: customers and the administrator cannot yet book, log in or manage anything in a browser, because most business HTTP endpoints and almost all workflow UI are still unbuilt (see `STATUS.md`). What exists is a real PostgreSQL-backed backend, a sample Angular shell, a captured-mail inbox and a large automated test suite.

All commands run from the repository root. Docker Desktop must be installed; the scripts start it if needed. Nothing here sends real mail, touches DNS or deploys anything.

`./purpuragenda.sh` wraps the steps below on macOS and Linux/WSL2; `./purpuragenda.sh help` lists its commands. Start with `./purpuragenda.sh doctor` (host, Docker, Compose v2, ports, disk), then `setup`, `up`, `smoke`, `admin`, `status`, `logs [service]` and `down` (stops containers; volume removal is refused). `./purpuragenda.sh demo-seed` creates up to three synthetic pending bookings through the public API and Mailpit on the local stack only. `./purpuragenda.sh test` runs the fast Angular and Python checks; `./purpuragenda.sh verify` (default `./verify.sh local`) runs the CI suites this host can run and marks hosted-only ones SKIPPED, which is not release evidence; `./verify.sh all` requires every implemented suite and fails for those this host cannot run. `./purpuragenda.sh e2e` deliberately fails until the core journey suite exists.

## 1. One-time toolchain (pinned versions, no system changes)

```sh
bash scripts/dev/install-toolchain.sh   # JDK 25, Maven, Node, Python 3.12 into ./.toolchain
source scripts/dev/toolchain-env.sh      # use bash; it does not work when sourced from zsh
```

`source` the environment in every new shell before running the Python or Maven commands below.

## 2. Start the local stack

```sh
./scripts/dev/local.sh up       # creates missing private files in ./.secrets, builds images, starts, waits for readiness
./scripts/dev/local.sh smoke    # app /healthz, Mailpit and the Angular shell respond
```

* Application: <http://localhost:8080> (use `localhost`, not `127.0.0.1`; the configured origin is `http://localhost:8080`).
* Captured mail: <http://localhost:8025> (Mailpit; all mail goes only here).
* Only loopback ports 8080 and 8025 are published. `.secrets/` holds the private keys and passwords; never delete or commit it, it matches the data in the database volume.
* Stop without losing data: `docker compose stop`. **Never** run `docker compose down -v`; that deletes the database.

## 3. Create the administrator (real password and TOTP)

```sh
./scripts/dev/local.sh admin-setup
```

It needs a real terminal. You are prompted for an email and a password (15 to 128 characters, typed twice, not echoed). It then shows an authenticator key and an `otpauth` link, asks for a six-digit code from your authenticator app and prints ten one-time recovery codes once. Store them somewhere safe. It refuses to run if any administrator already exists. If five codes are rejected the administrator stays unenrolled and the command cannot resume it. There is no administrator login screen or endpoint yet, so this only proves enrollment works.

## 4. Try the HTTP API that exists

The request shapes below follow the merged contract and the HTTP tests, but they were not replayed by hand against a running stack when this guide was written; if one differs, trust `contracts/openapi.yaml` and report it.

Public, read-only:

```sh
curl -s http://localhost:8080/api/v1/services
curl -s -H 'Accept: application/json' \
  'http://localhost:8080/api/v1/availability?serviceId=10000000-0000-0000-0000-000000000001&from=2026-11-02&to=2026-11-08&displayZone=Europe/Brussels'
```

Availability is advisory. Ranges are limited to 31 days; 120 requests per minute per client address are allowed, then you get `429` with `Retry-After`.

Session foundation (anonymous guest session, CSRF and logout). Unsafe requests need the exact `Origin` header:

```sh
curl -s -c jar -H 'Origin: http://localhost:8080' -X POST http://localhost:8080/api/v1/session/bootstrap   # returns csrf token
curl -s -b jar http://localhost:8080/api/v1/session                                                          # role and deadlines
curl -s -b jar -H 'Origin: http://localhost:8080' -H "X-CSRF-Token: <token from bootstrap>" \
  -X POST http://localhost:8080/api/v1/session/logout
curl -s -b jar -X POST http://localhost:8080/api/v1/session/logout                                         # rejected: ORIGIN_REJECTED
```

Not available yet: guest email proof, booking creation/changes, administrator login, finance and configuration endpoints, notifications, calendar exports. The commands and rules for booking creation, changes, administrator creation, payments, refunds and schedule configuration are implemented and tested against real PostgreSQL, but only through automated tests, not over HTTP.

## 5. Run the automated checks

```sh
source scripts/dev/toolchain-env.sh
caffeinate -is bash scripts/ci/verify-build.sh      # frontend tests, Maven verify with real PostgreSQL containers, Python tests, coverage gates (long)
caffeinate -is python3 scripts/ci/verify-compose.py # isolated fresh stack: startup, restart persistence, maintenance, header-spoof boundary, admin-setup on a pseudo-terminal
python3 scripts/ci/check-docs.py
python3 scripts/ci/check-contract.py
python3 scripts/ci/check-suites.py
python3 scripts/ci/run-python-tests.py --start scripts/ci/tests
```

`caffeinate` only prevents sleep during the run. A laptop that sleeps mid-run can cause spurious timeouts. These commands use their own isolated projects and never touch your running stack.

Hosted CI runs the same categories on every pull request; all 14 checks must pass before merge.

## 6. Troubleshooting

* `docker: command not found`: use `./scripts/dev/local.sh` (it adds Docker Desktop's directory to `PATH`) or open Docker Desktop once.
* A start failure on an existing install: do not regenerate `.secrets`. Run `docker compose ps` and `docker compose logs migrate backend` (logging is intentionally minimal and contains no personal data).
* `admin-setup` says it needs a terminal: run it directly in a terminal, not through a pipe or script.
* A port 8080 or 8025 conflict: stop whatever else uses it; the ports are fixed.

## What is not covered here

Real SMTP, DNS, public access through Tailscale, off-host backups and restore drills, real-device and calendar acceptance, legal approval and deployment are separate release work and are not part of local testing.
