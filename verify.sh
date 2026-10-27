#!/usr/bin/env bash
# Run every implemented CI suite from scripts/ci/suites.json locally; fails closed.
set -euo pipefail

case "$0" in
  */*) root="$(cd "${0%/*}" && pwd)" ;;
  *) root="$(pwd)" ;;
esac

usage() {
  cat <<'EOF'
Usage: ./verify.sh <command>

Commands:
  all    Run every suite marked "implemented" in scripts/ci/suites.json through the same
         scripts hosted CI uses (.github/workflows/verify.yml), then print a per-suite
         PASS/FAIL summary. Exits 0 only when every implemented suite passed here.
         Fails closed on a missing tool, a missing report, an implemented suite without a
         local command, or a suite that needs another host (macOS, native Windows or the
         GitHub CodeQL action); hosted CI stays the merge gate for those.
  list   Show the suite-to-command mapping without running anything.
  help   Show this help.

Examples:
  ./verify.sh all
  ./verify.sh list
EOF
}

case "${1:-}" in
  help|--help|-h) usage; exit 0 ;;
  all|list) (( $# == 1 )) || { usage >&2; exit 2; } ;;
  *) usage >&2; exit 2 ;;
esac

cd "$root"
# shellcheck source=scripts/dev/toolchain-env.sh
source scripts/dev/toolchain-env.sh
exec python3 scripts/dev/verify_local.py "$1"
