#!/usr/bin/env bash
set -euo pipefail
migrator_password="$(cat /run/secrets/migrator_password)"
app_password="$(cat /run/secrets/app_password)"
[[ "$migrator_password" =~ ^[a-f0-9]{64}$ && "$app_password" =~ ^[a-f0-9]{64}$ ]] || { echo 'Database passwords must be privately generated 64-character hex values.' >&2; exit 1; }
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 <<SQL
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE ROLE purpuragenda_migrator LOGIN PASSWORD '$migrator_password';
CREATE ROLE purpuragenda_app LOGIN PASSWORD '$app_password';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE,CREATE ON SCHEMA public TO purpuragenda_migrator;
GRANT USAGE ON SCHEMA public TO purpuragenda_app;
ALTER DEFAULT PRIVILEGES FOR ROLE purpuragenda_migrator IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO purpuragenda_app;
ALTER DEFAULT PRIVILEGES FOR ROLE purpuragenda_migrator IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO purpuragenda_app;
SQL
unset migrator_password app_password
