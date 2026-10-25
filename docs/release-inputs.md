# Owner inputs and release acceptance

The 2026-10-25 owner inputs are registered in ops/release-inputs.json. They are dated facts and statements of availability, not a live readiness certificate. No credentials, private host addresses, personal identities or provider diagnostics belong in that register or acceptance receipts.

The owner approved the exact English core wording in docs/policies/privacy-core.en.txt, including purpose limitation, no sale/unrelated sharing, necessary service providers and deletion requests. Its version, approval date and content hash are bound in the register. This is a core paragraph, not the complete privacy notice. The full notice's version/path/hash are explicitly unset. Release fails until separate full text is configured and accepted; the same core hash cannot masquerade as a full notice. P24/P35/P50 must integrate the complete reviewed notice/retention rules and bind actual booking snapshots to the complete served version. Existing snapshots retain their immutable original version.

The owner reported that reviewed policies exist and approved the clarified core paragraph. Full controller/service details, retention schedule, cancellation/refund terms and their application integration remain pending. Do not infer legal completeness from the paragraph or silently substitute a new policy. The public registry confirmed Squarespace as registrar and the four Google Domains nameservers; no DNS/account changes were made. The Mac's Tailscale/Docker prerequisites passed actual checks; restart/sleep recovery and service operation are separate. No public Linux ingress exists. Existing Zoom/SMTP settings are to follow, and the Linux computer is the proposed off-host backup destination.

## Verification and launch gates

Every PR runs structural input validation alongside all other implemented categories:

```sh
python3 scripts/ci/check-release-inputs.py
```

This checks safe complete input shape, approved-core/full-notice hashes and the fixed launch-gate inventory. It does not contact live suppliers or claim launch readiness. The register permits an explicitly missing full notice during development; release verification rejects it.

Release additionally requires the complete automated suite inventory and separate owner acceptance evidence for the exact checked-out revision:

```sh
python3 scripts/ci/check-suites.py --release --owner-evidence /secure/path/owner-acceptance
```

The evidence directory must be outside the reviewed source checkout. This avoids a circular commit hash and prevents committing a self-attestation as release evidence. No generator of real acceptance receipts is supplied. An operator records each acceptance only after its actual checks and explicit owner decision, preserving sanitized supporting evidence separately. The directory contains exactly one safe JSON receipt per launch gate listed in the register, named `<gate>.json`, with only:

- `schemaVersion`: integer1;
- `gate`: the exact gate name;
- `sourceRevision`: the full40-character result of `git rev-parse HEAD` for the reviewed release;
- `verifiedAt`: an ISO timestamp with UTC offset;
- `ownerApproved`: boolean true after the relevant explicit owner acceptance.

Missing, stale, unapproved, malformed, extra or linked receipts fail; private/raw logs are not accepted as receipts. Inputs are read through bounded, anchored no-follow file handles. Receipt metadata is an operator's record, not a cryptographic signature or independent proof of owner identity. A protected owner approval remains necessary for final publication/deployment under P48; CI must never manufacture acceptance or treat file presence as that approval.

The fixed gates cover full privacy notice, retention schedule, booking/cancellation/refund terms, public ingress, DNS/HTTPS, SMTP sender/delivery, Zoom entitlement/links, encrypted off-host backup/restore, host restart/recovery, real-client/owner demonstrations and the explicit deployment decision. Buying a server, activating DNS/ingress and sending live customer messages remain separate decisions. SSH/bootstrap permission certifies none of these.

All eleven external/live-use acceptances remain outstanding at this checkpoint. The new automated category implements input validation, not owner acceptance. `--release` also continues to fail for any unimplemented automated category, unavailable dependency or incomplete required verification. No live app deployment or supplier purchase has occurred.
