#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
umask 077
: "${RESTIC_REPOSITORY:?Set the encrypted off-host repository}"
: "${RESTIC_PASSWORD_FILE:?Set the private restic password file}"
compose_bin="${COMPOSE_BIN:-docker compose}"
read -r -a compose_command <<< "$compose_bin"
stack=("${compose_command[@]}" --env-file "${COMPOSE_ENV_FILE:-.env.operational}" -f compose.yaml -f compose.operational.yaml)
scratch="$(mktemp -d /tmp/purpuragenda-backup.XXXXXXXX)"
trap 'rm -rf -- "$scratch"' EXIT
snapshot_time="$("${stack[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT clock_timestamp()"')"
"${stack[@]}" exec -T postgres sh -c 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' > "$scratch/database.dump"
python3 - "$scratch" "$snapshot_time" <<'PY'
import hashlib,json,pathlib,sys
p=pathlib.Path(sys.argv[1]);dump=p/'database.dump'
(p/'manifest.json').write_text(json.dumps({'applicationVersion':'0.1.0-SNAPSHOT','snapshotNotBefore':sys.argv[2],'postgresMajor':18,'dumpSha256':hashlib.sha256(dump.read_bytes()).hexdigest()},indent=2)+'\n')
PY
restic_bin="${RESTIC_BIN:-restic}"
"$restic_bin" backup "$scratch" --tag purpuragenda --json > "$scratch/receipt.json"
"$restic_bin" check
echo 'Encrypted backup transferred and repository check passed. Recovery-point time is in its manifest.'
