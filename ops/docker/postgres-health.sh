#!/usr/bin/env bash
set -euo pipefail
pg_isready -q -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"
[[ "$(psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT count(*) FROM pg_roles WHERE rolname IN ('purpuragenda_app','purpuragenda_migrator') AND rolcanlogin" 2>/dev/null)" == 2 ]]
