#!/usr/bin/env bash
set -euo pipefail
: "${RESTIC_REPOSITORY:?Set repository}"
: "${RESTIC_PASSWORD_FILE:?Set password file}"
exec "${RESTIC_BIN:-restic}" forget --tag purpuragenda --keep-within 24h --keep-daily 7 --keep-weekly 4 --keep-monthly 3 --prune
