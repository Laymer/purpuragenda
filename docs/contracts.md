# Public contract foundation

The OpenAPI 3.1 document at contracts/openapi.yaml defines the public service and availability seam for P19. It is a contract for future implementation, not evidence of working endpoints. Every later endpoint is added by its owning task. The server remains authoritative for availability, capacity, ownership and snapshots.

`contracts/pom.xml` pins OpenAPI Generator 7.26.0 from the recovered approved build configuration. Maven resolves the versioned artifact from Maven Central; strict spec validation is enabled and generation timestamps are disabled. This standalone generation build avoids repeating generator execution in every backend module. Generated code uses the relative same-origin `/api/v1` base path.

After `bash scripts/dev/install-toolchain.sh`, use:

```sh
source scripts/dev/toolchain-env.sh
python3 scripts/ci/check-contract.py --write
python3 scripts/ci/check-contract.py
```

The write command replaces only the generated SDK directory with fresh generator output. Review contract changes and the generated diff together; never edit generated files manually. Verification generates into a temporary directory and rejects missing, altered or extra committed SDK files. It runs on every PR and main through the required aggregate check. No credentials, backend server or live SMTP are needed.

Handwritten seam tests check public access, same-origin routing, required query inputs, resolved references, snapshot/time fields and safe problem metadata. Generated-only SDK scaffolding has no handwritten Angular coverage claim. Once the Angular shell is added, all handwritten sources must be covered and the SDK must compile against the pinned Angular version; that remaining P12 slice is not certified here. Additional HTTP implementation behavior, date-range validation and security tests belong to P19/P20 and their owning tasks.

The session operations (`bootstrapSession`, `getSession`, `logoutSession`) use explicit wire models, a `sessionCookie` security scheme and a required `X-CSRF-Token` header for logout; their runtime behavior is in docs/sessions.md.

`listServices` and `availability` are implemented by `PublicController` (throttling in `AvailabilityThrottleFilter`, so the GET handler performs no writes) with explicit field-by-field wire mapping (no record serialization). The catalog is `Cache-Control: no-cache` with a strong ETag and 304 on `If-None-Match`; availability is `no-store` because it is advisory and the booking write path rechecks everything. Availability is throttled to 120 requests per minute per client address (trusted-proxy header only from an allowlisted peer, IPv6 per /64); denials are `429` with `Retry-After`, malformed requests are charged too and throttle storage failure fails closed with a retryable `503`. Problem codes: `INVALID_REQUEST`, `INVALID_ZONE`, `INVALID_DATE_RANGE`, `RANGE_TOO_WIDE`, `SERVICE_UNAVAILABLE` (404), `RATE_LIMITED`, `TEMPORARILY_UNAVAILABLE`.
