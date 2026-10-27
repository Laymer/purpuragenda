#!/usr/bin/env bash
# Purpuragenda local entry point for macOS (Intel/Apple silicon, Docker Desktop) and
# Linux/WSL2 (Docker Desktop WSL integration or Docker Engine).
# Bash 3.2 compatible: macOS /bin/bash works. Never removes data volumes or secrets.
set -euo pipefail

# RUN_ROOT, not PURPURAGENDA_ROOT: scripts/dev/toolchain-env.sh assigns that name when sourced.
case "$0" in
  */*) RUN_ROOT="$(CDPATH='' cd -- "${0%/*}" && pwd)" ;;
  *) RUN_ROOT="$(pwd)" ;;
esac
readonly ME="./purpuragenda.sh"
# Same Docker Desktop discovery as scripts/dev/local.py; overridable only so tests stay hermetic.
DOCKER_DESKTOP_BIN="${PURPURAGENDA_DOCKER_DESKTOP_BIN:-/Applications/Docker.app/Contents/Resources/bin}"
# Fixed loopback ports published by compose.yaml (web: application and Mailpit).
DOCTOR_PORTS="${PURPURAGENDA_DOCTOR_PORTS-8080 8025}"
readonly MIN_DISK_KB=$((5 * 1024 * 1024))
readonly LOW_DISK_KB=$((15 * 1024 * 1024))
# WSL2 interop marker; overridable only so tests stay hermetic on WSL hosts.
WSL_INTEROP_FILE="${PURPURAGENDA_WSL_INTEROP_FILE:-/proc/sys/fs/binfmt_misc/WSLInterop}"
readonly COMMANDS="help doctor setup up smoke admin down status logs demo-seed test verify e2e"

usage() {
  cat <<EOF
Purpuragenda local runner (macOS and Linux/WSL2).

Usage: $ME <command> [arguments]
       $ME help [command]
       $ME <command> --help

Commands:
  help        Show this help, or detailed help for one command.
  doctor      Check bash, git, Python, Docker CLI/daemon, Compose v2, ports 8080/8025, disk and OS.
  setup       Install the pinned toolchain into ./.toolchain and create private ./.secrets.
  up          Build and start the local stack, wait for readiness and smoke-test it.
  smoke       Check that the running stack (app, Angular shell, Mailpit) responds.
  admin       Enroll the single administrator interactively (password + TOTP).
  down        Stop the stack, keeping every volume and secret (docker compose stop).
  status      Show container state (docker compose ps).
  logs        Follow logs, optionally for one service (docker compose logs -f).
  demo-seed   Create 2-3 synthetic bookings through the real public API and Mailpit.
  test        Fast unit checks: Angular tests/build and the Python suites.
  verify      Run the CI suites this host can run (./verify.sh local); 'verify all' requires every suite.
  e2e         Core browser journey (not implemented yet; always fails).

Examples:
  $ME doctor
  $ME setup && $ME up
  $ME admin
  $ME demo-seed
  $ME logs backend
  $ME down

Application: http://localhost:8080   Captured mail (Mailpit): http://localhost:8025
EOF
}

command_help() {
  case "$1" in
    help) cat <<EOF
Usage: $ME help [command]
Show the command overview, or the detailed help of one command.
Example: $ME help doctor
EOF
    ;;
    doctor) cat <<EOF
Usage: $ME doctor
Check the host before setup: OS (macOS Intel/Apple silicon, Linux, WSL2), bash, git,
Python 3.9+, Docker CLI and daemon (Docker Desktop on macOS is found automatically),
Docker Compose v2, loopback ports 8080/8025 (free or used by this project), free disk
space, the pinned toolchain and private secrets. Prints a fix for each problem.
Exits 1 when a blocking problem is found, 0 otherwise (warnings do not block).
Example: $ME doctor
EOF
    ;;
    setup) cat <<EOF
Usage: $ME setup
Install the pinned JDK, Maven, Node and Python into ./.toolchain
(scripts/dev/install-toolchain.sh), then create any missing private file in ./.secrets
(scripts/dev/local.sh init). Existing secrets are never replaced. Safe to repeat.
Example: $ME setup
EOF
    ;;
    up) cat <<EOF
Usage: $ME up
Build images, start the stack, wait for readiness and run the smoke check
(scripts/dev/local.sh up). Starts Docker Desktop on macOS when needed.
Example: $ME up
EOF
    ;;
    smoke) cat <<EOF
Usage: $ME smoke
Check /healthz, Mailpit and the Angular shell of the running stack (scripts/dev/local.sh smoke).
Example: $ME smoke
EOF
    ;;
    admin) cat <<EOF
Usage: $ME admin
Enroll the single administrator in a private terminal (scripts/dev/local.sh admin-setup):
email, password typed twice, authenticator key and a six-digit code, then ten recovery
codes shown once. Refuses when an administrator already exists. There is no seeded administrator.
Example: $ME admin
EOF
    ;;
    down) cat <<EOF
Usage: $ME down [service...]
Stop the stack with 'docker compose stop'. Containers, volumes and ./.secrets are kept;
'$ME up' resumes with the same data. Volume removal (-v/--volumes) is refused: it would
delete the database, which must match the private keys in ./.secrets.
Examples: $ME down
          $ME down backend
EOF
    ;;
    status) cat <<EOF
Usage: $ME status
Show the state of this project's containers (docker compose ps).
Example: $ME status
EOF
    ;;
    logs) cat <<EOF
Usage: $ME logs [service]
Follow container logs (docker compose logs -f), for all services or one of:
postgres, migrate, backend, web, mailpit, proxy. Stop following with Ctrl-C.
Examples: $ME logs
          $ME logs backend
EOF
    ;;
    demo-seed) cat <<EOF
Usage: $ME demo-seed [--count N]
Create N (1-3, default 3) synthetic pending bookings through the real public API only:
session bootstrap, guest email proof, proof link read from Mailpit, redemption,
availability and booking creation with an Idempotency-Key. Local mode only: refuses any
target other than http://localhost:8080 and requires Mailpit on http://localhost:8025.
Uses demo+...@example.test addresses and made-up intake; never prints tokens or cookies.
Examples: $ME up && $ME demo-seed
          $ME demo-seed --count 2
EOF
    ;;
    test) cat <<EOF
Usage: $ME test
Fast checks without Docker: Angular unit tests and build (scripts/ci/verify-frontend.sh)
and the Python suites (scripts/ci and scripts/ci/tests). Run '$ME setup' first.
Example: $ME test
EOF
    ;;
    verify) cat <<EOF
Usage: $ME verify [local|all]
local (default): run every implemented suite this host can run (./verify.sh local);
  hosted-only suites are reported SKIPPED (HOSTED-ONLY). Not release evidence.
all: require every implemented suite (./verify.sh all); fails closed on missing tools,
  missing reports and suites that need another host. Hosted CI stays authoritative.
Examples: $ME verify
          $ME verify all
EOF
    ;;
    e2e) cat <<EOF
Usage: $ME e2e
Automated core browser journey. Not implemented yet (P38/P39 slice pending): this
command always fails so that it can never pass silently.
EOF
    ;;
    *) return 1 ;;
  esac
}

die_usage() {
  printf '%s\n\n' "$1" >&2
  usage >&2
  exit 2
}

python_command() {
  if [[ -x "$RUN_ROOT/.toolchain/python/bin/python3" ]]; then
    printf '%s\n' "$RUN_ROOT/.toolchain/python/bin/python3"
  else
    printf 'python3\n'
  fi
}

discover_docker() {
  if ! command -v docker >/dev/null 2>&1 && [[ -x "$DOCKER_DESKTOP_BIN/docker" ]]; then
    PATH="$DOCKER_DESKTOP_BIN:$PATH"
    export PATH
  fi
  command -v docker >/dev/null 2>&1
}

compose() {
  discover_docker || { echo "Docker CLI not found. Run '$ME doctor' for a fix." >&2; exit 1; }
  unset COMPOSE_FILE
  docker compose -f "$RUN_ROOT/compose.yaml" "$@"
}

# ---- doctor -------------------------------------------------------------------
BLOCKING=0
WARNINGS=0
ok() { printf '  [ok]   %s\n' "$*"; }
warn() { printf '  [warn] %s\n' "$*"; WARNINGS=$((WARNINGS + 1)); }
fail() { printf '  [FAIL] %s\n' "$*"; BLOCKING=$((BLOCKING + 1)); }
fix() { printf '         fix: %s\n' "$*"; }

detect_os() {
  local kernel release arch
  kernel="$(uname -s 2>/dev/null || echo unknown)"
  release="$(uname -r 2>/dev/null || echo unknown)"
  arch="$(uname -m 2>/dev/null || echo unknown)"
  HOST_ARCH="$arch"
  case "$kernel" in
    Darwin)
      HOST_OS=macos
      case "$arch" in
        arm64) ok "macOS on Apple silicon ($arch)" ;;
        x86_64) ok "macOS on Intel ($arch)" ;;
        *) warn "macOS on untested architecture $arch" ;;
      esac
      ;;
    Linux)
      # WSL2 kernels report "microsoft-standard"; WSL1 reports "Microsoft" with no real Linux kernel.
      case "$release" in
        *microsoft-standard*|*WSL2*) HOST_OS=wsl; ok "Linux on WSL2 ($arch, kernel $release)" ;;
        *[Mm]icrosoft*)
          HOST_OS=wsl
          warn "Linux on WSL1 ($arch, kernel $release): Docker is not supported on WSL1"
          fix "in Windows PowerShell: wsl --set-version <distribution> 2 (or wsl --set-default-version 2)"
          ;;
        *)
          if [[ -n "${WSL_INTEROP:-}" || -e "$WSL_INTEROP_FILE" ]]; then
            HOST_OS=wsl; ok "Linux on WSL2 with a custom kernel ($arch, kernel $release)"
          else
            HOST_OS=linux; ok "Linux ($arch, kernel $release)"
          fi
          ;;
      esac
      ;;
    *)
      HOST_OS=unsupported
      fail "unsupported operating system: $kernel"
      fix "use macOS, Linux or a WSL2 distribution (on Windows run this inside WSL2 Ubuntu)"
      ;;
  esac
}

check_bash() {
  local major="${BASH_VERSINFO[0]}"
  if (( major >= 4 )); then
    ok "bash $BASH_VERSION"
  else
    warn "bash $BASH_VERSION is old; it runs purpuragenda.sh, but newer bash is recommended"
    fix "optional: brew install bash (macOS keeps /bin/bash 3.2)"
  fi
}

check_git() {
  if command -v git >/dev/null 2>&1 && git --version >/dev/null 2>&1; then
    ok "$(git --version)"
  else
    fail "git not found"
    case "$HOST_OS" in
      macos) fix "xcode-select --install" ;;
      *) fix "sudo apt-get install git (or your distribution's package manager)" ;;
    esac
  fi
}

check_python() {
  local python version
  python="$(python_command)"
  if version="$("$python" -c 'import sys; print("%d.%d" % sys.version_info[:2]); sys.exit(sys.version_info < (3, 9))' 2>/dev/null)"; then
    ok "Python $version"
  else
    fail "Python 3.9 or newer not found (needed by setup and the helper scripts)"
    case "$HOST_OS" in
      macos) fix "xcode-select --install (provides python3), or brew install python" ;;
      *) fix "sudo apt-get install python3" ;;
    esac
  fi
}

DOCKER_DAEMON=no
check_docker() {
  local version compose_version
  if ! discover_docker; then
    fail "Docker CLI not found"
    case "$HOST_OS" in
      macos) fix "install Docker Desktop (https://docs.docker.com/desktop/setup/install/mac-install/) and open it once" ;;
      wsl) fix "Docker Desktop: Settings > Resources > WSL integration, enable this distribution; or install Docker Engine in WSL" ;;
      *) fix "install Docker Engine: https://docs.docker.com/engine/install/" ;;
    esac
    return
  fi
  ok "Docker CLI $(command -v docker)"
  if version="$(docker version --format '{{.Server.Version}}' 2>/dev/null)" && [[ -n "$version" ]]; then
    DOCKER_DAEMON=yes
    ok "Docker daemon $version"
  else
    fail "Docker daemon not reachable"
    case "$HOST_OS" in
      macos) fix "open -a Docker, wait until Docker Desktop is running ('$ME up' also starts it)" ;;
      wsl) fix "start Docker Desktop on Windows with WSL integration for this distribution, or: sudo service docker start" ;;
      *) fix "sudo systemctl start docker; to run without sudo: sudo usermod -aG docker \$USER and log in again" ;;
    esac
  fi
  if compose_version="$(docker compose version --short 2>/dev/null)" && [[ "$compose_version" =~ ^v?([0-9]+)\. ]] \
      && (( BASH_REMATCH[1] >= 2 )); then
    ok "Docker Compose $compose_version"
  else
    fail "Docker Compose v2 plugin not found ('docker compose', not 'docker-compose')"
    case "$HOST_OS" in
      macos) fix "update Docker Desktop" ;;
      *) fix "install the docker-compose-plugin package: https://docs.docker.com/compose/install/linux/" ;;
    esac
  fi
}

# Exit 0: in use; 10: free; anything else (missing Python, exception): unknown, never "free".
port_busy() {
  "$(python_command)" - "$1" <<'PY'
import errno, socket, sys
try:
    port = int(sys.argv[1])
    busy = False
    probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    # SO_REUSEADDR ignores TIME_WAIT leftovers; an active listener still refuses the bind.
    probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        probe.bind(('127.0.0.1', port))
    except OSError as error:
        if error.errno != errno.EADDRINUSE:
            raise
        busy = True
    finally:
        probe.close()
    if not busy:
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.settimeout(1)
        busy = client.connect_ex(('127.0.0.1', port)) == 0
        client.close()
except Exception:
    sys.exit(3)
sys.exit(0 if busy else 10)
PY
}

port_owned() {
  local published
  [[ "$DOCKER_DAEMON" == yes ]] || return 1
  published="$(unset COMPOSE_FILE; docker compose -f "$RUN_ROOT/compose.yaml" port web "$1" 2>/dev/null)" || return 1
  [[ "$published" == *":$1" ]]
}

check_ports() {
  local port probe
  for port in $DOCTOR_PORTS; do
    probe=0
    port_busy "$port" 2>/dev/null || probe=$?
    if (( probe == 10 )); then
      ok "loopback port $port is free"
    elif (( probe != 0 )); then
      fail "could not check loopback port $port (probe exit $probe)"
      fix "install Python 3.9+ and rerun '$ME doctor'; the port must be free or used by this project"
    elif port_owned "$port"; then
      ok "loopback port $port is used by this project's running stack"
    else
      fail "loopback port $port is used by another process"
      case "$HOST_OS" in
        macos) fix "find it with: lsof -nP -iTCP:$port -sTCP:LISTEN, then stop it (the port is fixed)" ;;
        wsl) fix "find it with: ss -ltnp 'sport = :$port' (and on Windows: netstat -ano | findstr :$port), then stop it" ;;
        *) fix "find it with: ss -ltnp 'sport = :$port', then stop it (the port is fixed)" ;;
      esac
    fi
  done
}

check_disk() {
  local available
  available="$(df -Pk "$RUN_ROOT" 2>/dev/null | awk 'NR == 2 { print $4 }')"
  if [[ ! "$available" =~ ^[0-9]+$ ]]; then
    warn "could not read free disk space"
  elif (( available < MIN_DISK_KB )); then
    fail "only $((available / 1024)) MiB free; at least 5 GiB is needed for the toolchain and images"
    fix "free disk space (on Docker Desktop also check Settings > Resources > Disk usage limit)"
  elif (( available < LOW_DISK_KB )); then
    warn "$((available / 1024 / 1024)) GiB free; 15 GiB or more is recommended"
  else
    ok "$((available / 1024 / 1024)) GiB free disk space"
  fi
}

check_workspace() {
  if [[ -x "$RUN_ROOT/.toolchain/jdk/bin/java" ]]; then
    ok "pinned toolchain installed"
  else
    warn "pinned toolchain not installed"
    fix "$ME setup"
  fi
  if [[ -d "$RUN_ROOT/.secrets" ]]; then
    ok "private ./.secrets present (keep it; it matches the database volume)"
  else
    warn "private ./.secrets not created yet"
    fix "$ME setup"
  fi
}

doctor() {
  HOST_OS=unknown
  echo "Purpuragenda doctor"
  detect_os
  check_bash
  check_git
  check_python
  check_docker
  check_ports
  check_disk
  check_workspace
  if (( BLOCKING > 0 )); then
    echo "Doctor: $BLOCKING blocking problem(s), $WARNINGS warning(s). Apply the fixes above and rerun '$ME doctor'."
    return 1
  fi
  echo "Doctor: no blocking problems ($WARNINGS warning(s))."
}

# ---- commands -----------------------------------------------------------------
refuse_volume_removal() {
  echo "Refused: '$1' would delete Docker volumes, including the PostgreSQL database." >&2
  echo "The database must stay with the private keys in ./.secrets. '$ME down' only stops containers;" >&2
  echo "'$ME up' resumes with the same data. Never use 'docker compose down -v'." >&2
  exit 2
}

cmd_down() {
  local argument
  for argument in "$@"; do
    case "$argument" in
      --volumes|--volumes=*) refuse_volume_removal "$argument" ;;
      --*) die_usage "Unsupported option for down: $argument" ;;
      -*v*) refuse_volume_removal "$argument" ;;
      -*) die_usage "Unsupported option for down: $argument" ;;
    esac
  done
  compose stop "$@"
}

cmd_logs() {
  (( $# <= 1 )) || die_usage "logs accepts at most one service"
  if (( $# == 1 )); then
    [[ "$1" != -* ]] || die_usage "Unsupported option for logs: $1"
    compose logs -f "$1"
  else
    compose logs -f
  fi
}

cmd_test() {
  local python
  # shellcheck source=scripts/dev/toolchain-env.sh
  source "$RUN_ROOT/scripts/dev/toolchain-env.sh"
  python="$(python_command)"
  bash "$RUN_ROOT/scripts/ci/verify-frontend.sh"
  "$python" "$RUN_ROOT/scripts/ci/run-python-tests.py" --start "$RUN_ROOT/scripts/ci"
  "$python" "$RUN_ROOT/scripts/ci/run-python-tests.py" --start "$RUN_ROOT/scripts/ci/tests"
  echo "Fast checks passed (Angular unit tests/build and Python suites). Full gates: $ME verify"
}

no_arguments() {
  (( $1 == 0 )) || die_usage "$2 takes no arguments"
}

main() {
  local command="${1:-}"
  if [[ -z "$command" ]]; then
    die_usage "Missing command."
  fi
  shift
  if [[ " $COMMANDS " == *" $command "* ]] && [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    command_help "$command"
    return 0
  fi
  cd "$RUN_ROOT"
  case "$command" in
    help|--help|-h)
      if (( $# == 0 )); then usage; return 0; fi
      command_help "$1" || die_usage "Unknown command: $1"
      ;;
    doctor) no_arguments $# doctor; doctor ;;
    setup)
      no_arguments $# setup
      bash scripts/dev/install-toolchain.sh
      bash scripts/dev/local.sh init
      ;;
    up) no_arguments $# up; exec bash scripts/dev/local.sh up ;;
    smoke) no_arguments $# smoke; exec bash scripts/dev/local.sh smoke ;;
    admin) no_arguments $# admin; exec bash scripts/dev/local.sh admin-setup ;;
    down) cmd_down "$@" ;;
    status) no_arguments $# status; compose ps ;;
    logs) cmd_logs "$@" ;;
    demo-seed) exec "$(python_command)" scripts/dev/demo_seed.py "$@" ;;
    test) no_arguments $# test; cmd_test ;;
    verify)
      (( $# <= 1 )) || die_usage "verify accepts one mode: local or all"
      case "${1:-local}" in
        local|all) exec bash verify.sh "${1:-local}" ;;
        *) die_usage "Unknown verify mode: $1" ;;
      esac
      ;;
    e2e)
      echo "e2e: core journey suite not implemented yet (P38/P39 slice pending)." >&2
      exit 1
      ;;
    *) die_usage "Unknown command: $command" ;;
  esac
}

main "$@"
