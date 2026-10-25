#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
python_command=python3
if [[ -x .toolchain/python/bin/python3 ]]; then python_command=.toolchain/python/bin/python3; fi
exec "$python_command" scripts/dev/local.py "$@"
