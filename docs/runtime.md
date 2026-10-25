# Executable backend foundation

The backend packages as `backend/bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar`. The serving entry point composes the current scheduling, private administrator factor, durable authentication delivery and SMTP ports. It exposes generic Actuator health only. Customer/admin HTTP, session protections, automatic worker ticks and the private operator CLI are later slices; there is no public administrator registration or authentication bypass.

Use the app database role for serving and the migrator role only for the separate command:

```sh
java -jar backend/bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar
java -jar backend/bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar migrate
```

Supply configuration through environment variables. The migration command needs only database configuration, opens no HTTP listener, applies production Liquibase changesets and exits. It never loads SMTP or encryption keys. Repeating migration preserves insert-only seeds. Serving with migrator credentials, or migrating with app credentials, is rejected before opening a connection.

| Setting | Requirement |
| --- | --- |
| `APP_DATABASE_URL` | Bare `jdbc:postgresql://host:port/database`; no query options or embedded credentials |
| `APP_DATABASE_USER` | `purpuragenda_app` when serving; `purpuragenda_migrator` for `migrate` |
| `APP_DATABASE_PASSWORD_FILE` | Private, bounded UTF-8 file; a final CRLF/LF is removed, meaningful spaces preserved |
| `APP_MODE` | `local`, `test`, `restore` or explicitly configured `operational` |
| `APP_PUBLIC_ORIGIN` | Local/restore loopback HTTP origin; operational HTTPS without local/synthetic defaults |
| `APP_TOTP_KEY_FILE`, `APP_DELIVERY_KEY_FILE` | Distinct 32-byte keys; final symlinks and reused material are refused |
| `APP_TOTP_KEY_ID`, `APP_DELIVERY_KEY_ID` | Distinct safe version IDs, preserving the corresponding persistent ciphertext versions |
| `APP_SMTP_HOST`, `APP_SMTP_PORT`, `APP_MAIL_FROM` | Local/test capture transport only; restore requires no SMTP settings |
| Operational additions | `APP_NOTICE_VERSION`, `APP_SMTP_SECURITY`, `APP_SMTP_USER`, `APP_SMTP_PASSWORD_FILE`; TLS security is `STARTTLS_REQUIRED` or `IMPLICIT_TLS` |

Local/test permits `meetings.example.invalid` only as a synthetic demonstration meeting host. It never resolves or contacts a meeting URL. This default is unavailable operationally. Runtime configuration does not constitute legal review or approval for deployment/live mail.

The listener defaults to loopback `127.0.0.1:8081`; the forthcoming private Compose network supplies its container bind address. `/actuator/health/readiness` returns only `UP` or `DOWN`: it verifies required application columns including recovery/guest authority, resource/settings/control rows, valid policy/calendar and that maintenance is off. A reachable empty database is not ready. The app role never reads restricted Liquibase metadata. Shutdown drains for 30 seconds; migration/runtime failures carry fixed cause-free codes, and framework/provider logging is disabled.

`bash scripts/ci/verify-build.sh` requires the real runtime PostgreSQL/HTTP report as well as the migration adapter report. Missing Docker, suites, reports, cases, failures or skips fail validation. Compose lifecycle, persistence/upgrade smoke, two backend processes and encrypted restore are not certified by this bootstrap foundation.
