#!/usr/bin/env bash
set -euo pipefail
if [[ -n "${DATABASE_PASSWORD_FILE:-}" ]]; then export DATABASE_PASSWORD="$(cat "$DATABASE_PASSWORD_FILE")"; fi
if [[ -n "${SMTP_PASSWORD_FILE:-}" && -f "$SMTP_PASSWORD_FILE" ]]; then export SMTP_PASSWORD="$(cat "$SMTP_PASSWORD_FILE")"; fi
exec /opt/jdk/bin/java -XX:MaxRAMPercentage=70 -jar /app/application.jar "$@"
