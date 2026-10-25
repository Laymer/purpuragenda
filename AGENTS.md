# Purpuragenda implementation rules

Read docs/implementation-plan.md, docs/decisions.md and docs/status.md before changes.
The approved version 1 scope is an English, single-practitioner booking application.

- Keep domain dependent only on the JDK. Application depends only on domain and its own ports.
- Frameworks, JDBC, SMTP, calendar and HTTP belong in adapters. Bootstrap composes them.
- Scheduling mutations use SchedulingUnitOfWork, one resource lock, one captured authoritative time, and a PostgreSQL transaction. Never allow overlapping 120-minute reservations, even for an administrator.
- Retain immutable service/intake/policy snapshots and independent payment facts. Manual approval does not imply payment.
- Use Europe/Brussels civil dates for business rules and Instant for elapsed time. Test DST, month ends and inclusive cutoffs.
- Never put personal data, tokens, cookies, passwords, TOTP secrets or arbitrary provider errors in logs or test artifacts. Use synthetic test data.
- No public administrator registration, test authentication bypass, checkout integration, automatic Zoom provisioning or live email in local/restore mode.
- Preserve data volumes. Never use `docker compose down -v` in operational instructions.
- Verification commands must fail for unavailable dependencies, unimplemented suites and missing reports. Record actual results and limits in docs/status.md; do not mark a milestone complete without its gates.
- Keep API contracts and operational documentation aligned with implementation. Deployment and external supplier purchases are separate manual decisions.
