# Purpuragenda

<img width="2172" height="724" alt="Celestial purple zodiac arc" src="https://github.com/user-attachments/assets/538a6183-79a1-42d8-b1a1-1e2f32b3cd1e" />

Purpuragenda is a private booking application for video consultations with one practitioner. The interface is in English and meetings use manually entered Zoom links. The backend is Java 25 and Spring on PostgreSQL, the frontend is Angular, and the local stack runs in Docker Compose with all mail captured by Mailpit.

## 1.0.0 is a local release

It runs on your own computer, sends no real email and is not deployed anywhere. On the local stack, end to end:

* a guest picks a consultation and a time, proves their email address through Mailpit, enters their details and submits a request;
* the administrator signs in with a password and an authenticator code, sees the agenda and pending queue, and approves with a Zoom link;
* the customer opens a management link, sees the status, and can cancel or move the booking before the change cutoff.

`STATUS.md` lists what is merged and every gate that is still open. Payments, service and schedule editing, accounts, privacy tools, calendar exports, backups and deployment are not part of 1.0.0.

## Prerequisites

Docker Desktop (macOS, or Windows with WSL2 integration) or Docker Engine with the Compose v2 plugin, plus bash, git and Python 3.9 or newer. Ports 8080 and 8025 must be free. Open the application at http://localhost:8080 (use `localhost`, not `127.0.0.1`) and captured mail at http://localhost:8025.

## Quick start

```sh
./purpuragenda.sh doctor      # check the host, Docker, Compose v2, ports and disk
./purpuragenda.sh setup       # install the pinned toolchain and create private ./.secrets
./purpuragenda.sh up          # build, start, wait for readiness and smoke-test
./purpuragenda.sh admin       # enroll the single administrator (interactive password + TOTP)
./purpuragenda.sh demo-seed   # optional: synthetic pending bookings through the real API
./purpuragenda.sh smoke       # re-check the running stack
./purpuragenda.sh status      # container state
./purpuragenda.sh logs        # follow logs, optionally for one service
./purpuragenda.sh down        # stop, keeping every volume and secret
./purpuragenda.sh help        # every command; help <command> explains one
```

Never run `docker compose down -v` and never delete `./.secrets`: the keys there match the data in the database volume.

## Testing

```sh
./purpuragenda.sh test        # Angular tests and build, Python suites (no Docker)
./purpuragenda.sh verify      # ./verify.sh local: suites this host can run; not release evidence
./verify.sh all               # every implemented suite; fails for suites this host cannot run
./purpuragenda.sh e2e         # Chromium core journey on its own isolated Compose stack
```

Hosted CI runs every check on each pull request, and all must pass before a squash merge. Coverage gates are at least 90% lines and branches per backend module and for Angular, with 95% lines for domain and application.

More detail: [docs/local-testing.md](docs/local-testing.md). Rules for contributors and agents: [AGENTS.md](AGENTS.md), [STATUS.md](STATUS.md) and [docs/decisions.md](docs/decisions.md).
