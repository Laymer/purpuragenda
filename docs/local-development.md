# Local Compose checkpoint

This slice packages the current application foundation. Full booking/admin HTTP and UI, automatic authentication-mail ticks, private `admin-setup`, two-backend tests and operational/restore profiles remain unfinished. Do not interpret startup as full v1 acceptance.

```sh
./scripts/dev/local.sh up
./scripts/dev/local.sh smoke
```

`up` discovers the existing Docker Desktop installation, starts it when needed, preserves existing private files, builds pinned images and waits for readiness. Application: `http://localhost:8080`; captured Mailpit mail: `http://localhost:8025`. Initialized checkouts also support `docker compose up --build --wait`. The extra `local.sh init` command prepares files without starting Docker.

Only Caddy publishes loopback ports. PostgreSQL, migration, backend and Mailpit use internal networks; Mailpit's UI is proxied through Caddy. There is no Docker socket mount. Containers run as numeric nonroot users with read-only roots, dropped capabilities and provider logging disabled. PostgreSQL initialization is copied executable into its image, avoiding host bind execution differences; health requires the final TCP listener and both database roles. Migration receives only its DDL password. The server receives its DML password and independent versioned TOTP/delivery/rate keys; the rate key is reserved for the P17 HTTP work.

`.secrets` is private0700; individual read-only mounts are0444 so numeric container users can read them. The initializer validates existing content, rejects links and duplicates, and adds only missing files under a directory lock. Never replace existing files to repair a startup failure: they correspond to persisted data. PostgreSQL, Mailpit and Caddy use named volumes; ordinary `docker compose down` preserves them.

The sender defaults to `hello@medinavie.com`; an existing `PURPURAGENDA_MAIL_FROM` override is retained. SMTP remains fixed to private Mailpit1025. Local notices and meeting hosts remain synthetic. No live mail, DNS change or deployment is included.

Caddy overwrites `X-Purpuragenda-Client-IP` and removes inbound forwarding headers. Its configured fixed address is outside the dynamic proxy pool. `APP_SECURITY_TRUSTED_PROXY_PEERS` is reserved for the matching exact-peer P17 protection; configured public origin remains authoritative. Separate test stacks override `PURPURAGENDA_PROXY_SUBNET`, `PURPURAGENDA_PROXY_POOL` and `PURPURAGENDA_PROXY_ADDRESS` together.

```sh
source scripts/dev/toolchain-env.sh
# macOS: temporary sleep assertion only; no persistent host setting changes.
caffeinate -is python3 scripts/ci/verify-compose.py
```

The new mandatory persistence job builds actual images, creates a unique project/network/secret directory, checks real startup and repeated migration, service and Mailpit persistence, maintenance readiness, and spoofed proxy headers. The small test-only header observer is never packaged in the app jar. Its receipt records execution location, revision and dirty state; incomplete, stale or dirty evidence fails. Both isolated volumes and matching private temporary state are retained. Run this gate from a clean commit; Linux CI calls the Python script directly.

The first isolated run exposed Docker's null IPAM entries; a unit regression now covers them. A complete corrected fresh-stack run, current-main integration, all-category checks and independent exact-head review remain required before merging this checkpoint. Automatic AUTH delivery and private operator enrollment are separate follow-ups.
