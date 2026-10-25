#!/usr/bin/env bash
set -euo pipefail
: "${PG_TEST_SCRATCH:?Only isolated test scratch may be mounted}"
[[ "$PG_TEST_SCRATCH" == /tmp/purpuragenda-restore-* ]] || exit 2
exec docker run --rm --network host --read-only --cap-drop ALL --security-opt no-new-privileges \
  --user "$(id -u):$(id -g)" --env PGPASSWORD \
  --mount "type=bind,src=$PG_TEST_SCRATCH,dst=$PG_TEST_SCRATCH" \
  postgres:18.6-bookworm@sha256:bbf83594798e144bff365a9c191c5d779f181fb3135902064ab8ff11918020fc "$@"
