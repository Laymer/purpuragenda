#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/dev/toolchain-env.sh
# Remove stale reports so a missing test/build cannot inherit an earlier success.
python3 scripts/ci/check-frontend-reports.py --clean
npm --prefix frontend ci --no-audit --no-fund
npm --prefix frontend test
npm --prefix frontend run build
python3 scripts/ci/check-frontend-reports.py
