# Private administrator bootstrap foundation

P16/private-bootstrap exposes framework-free private application ports and adapters;
it does not expose a web endpoint, startup hook or executable operator command.
No authentication/session capability is issued. P16 remains incomplete until atomic
MFA enrollment, accepted-step replay prevention, recovery and forced reenrollment
are implemented. Deployment/owner credentials remain separate P50 decisions.

Passwords contain15–128 Unicode codepoints. Supplementary characters count once;
unpaired UTF-16 surrogates and ISO-control codepoints are rejected. Spaces are
preserved; there is no trimming, normalization or truncation. The use case consumes
and clears its caller's char[] on every outcome. The hash adapter borrows inputs
and clears its own character/UTF-8/derived-byte copies. Managed-runtime copies
cannot be promised erased; passwords are never converted to application Strings.

The existing pinned Bouncy Castle1.86 dependency is promoted from test to runtime.
Argon2id v19 uses exactly65536KiB,3 iterations,1 lane, random16-byte salt and32-byte
output. A canonical bounded PHC parser rejects any other algorithm/version/cost,
length, delimiter, invalid or noncanonical Base64 before expensive derivation.
Verification uses constant-time byte comparison. Existing RFC9106 oracle and
approved-cost tests remain mandatory alongside real adapter verification.
Official implementation provenance: https://github.com/bcgit/bc-java/tree/r1rv86/core/src/main/java/org/bouncycastle/crypto .

Bootstrap validates normalized email and hash, starts an independent READ_COMMITTED
transaction on its own direct JDBC connection and locks admin_user before testing for any existing row. Existing,
disabled or historical administrators cannot be replaced, reenabled or reset.
Concurrent empty-table attempts serialize, with the existing unique index as a
second database barrier. A new row is enabled but unenrolled, accepted-step=-1,
with no recovery codes. A fixed nonpersonal audit event commits atomically; audit failure rolls back the administrator. Its random TOTP secret is encrypted through P13 Secrets
using a dedicated TOTP key instance; repeated calls with another key leave it
unchanged. No plaintext secret, password, provider cause or credential-bearing
result is logged/returned. Failures roll back with a fixed safe code.

Future private operator composition must use Console.readPassword and fail when
no private interactive console exists; never accept passwords through argv,
environment variables or public registration. This slice deliberately has no
operator wiring while secure atomic enrollment is unavailable.

Update (P45/followup): the private operator form now exists as `admin-setup` (`Purpuragenda admin-setup`, composed by a non-web `OperatorApplication`). It uses `System.console()` and requires `Console.isTerminal()`, reads the password twice with `readPassword`, then runs the unchanged bootstrap, password-proven provisioning and enrollment use cases and prints key and recovery codes only through that console. `scripts/dev/local.sh admin-setup` supplies the container terminal; `verify-compose.py` drives it on a pseudo-terminal with a synthetic credential and an independently computed TOTP code.
