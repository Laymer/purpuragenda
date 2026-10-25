#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
umask 077
mkdir -p .secrets
chmod 700 .secrets
for name in postgres-password migrator-password app-password; do
  if [[ ! -f ".secrets/$name" ]]; then openssl rand -hex 32 > ".secrets/$name"; fi
done
if [[ ! -f .secrets/encryption-key ]]; then openssl rand 32 > .secrets/encryption-key; fi
# Compose file-backed secrets preserve host file modes. The private0700 parent protects
# them on the host; read-only individual mounts must be readable by container users.
chmod 444 .secrets/postgres-password .secrets/migrator-password .secrets/app-password .secrets/encryption-key
if [[ ! -f .env.local ]]; then
  cat > .env.local <<'ENV'
COMPOSE_PROJECT_NAME=purpuragenda-local
APP_MODE=local
PUBLIC_BASE_URL=http://localhost:8080
OWNER_NOTIFICATION_EMAIL=owner@example.test
MAIL_FROM=bookings@example.test
DELIVERY_MODE=CAPTURE
ENV
fi
echo 'Local secret files prepared. No credentials printed. Existing files preserved.'
