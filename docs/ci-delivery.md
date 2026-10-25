# PR and commit enforcement

Every new commit includes a substantive automated test-source addition or update.
This applies to documentation and handoff changes too: include those changes with
a tested implementation or validator change instead of a standalone checkpoint.
CI checks each commit in the PR range, not only the final combined diff.
Deletion, unchanged renaming, whitespace and line-comment-only edits do not count.
The independent reviewer verifies test relevance and behavior; a source diff alone
cannot establish semantic quality. Mechanical merges may inherit the tested change
from the merged branch, measured against their first parent.

Already published history through exact commit
`dd482e3f30f883b0e7c1a3cecd459d2afcc087aa` is grandfathered. This includes P07's
existing review checkpoints and does not exempt later changes to that branch.
A complete checkout is required; missing Git objects and shallow history fail.
Use `python3 scripts/ci/check-commit-tests.py --base BASE_SHA --head HEAD_SHA`.

The `delivery-policy` job runs synthetic repository/API tests, the commit policy,
and a read-only live main-protection check. The existing always-running `verify`
aggregate requires documentation, build and delivery policy to succeed; skipped,
cancelled or failed categories cannot pass. Every PR runs all implemented categories.

The managed configuration is `.github/rules/main.json`. It targets main only,
requires PRs and a current-base successful `verify` from GitHub Actions (app ID
15368, observed on the repository's checks), resolved review threads and squash
merges. It allows no bypass actors and also prohibits deletion/force pushes.
Independent agent review is performed by the coordinator before merge; it is not
represented as a GitHub approval from a second human account.

Check live rules with `python3 scripts/ci/main-protection.py`. After independent
review, an authorized coordinator applies them with
`python3 scripts/ci/main-protection.py --apply`, then checks the readback.
The command needs GitHub CLI authentication with repository administration access
to apply. Its default mode only reads metadata. The script changes only its named
managed ruleset and preserves the existing `nodelete` ruleset and other rulesets.
Existing required checks, their source-app pins, strict checks, merge restrictions
and stronger managed parameters cannot be relaxed automatically; unexpected settings
require manual reconciliation. API failures fail closed without echoing provider output.

The foundation PR's live-protection check will fail until the reviewed configuration
is applied. This is deliberate: do not remove or conditionally skip the check to
merge it. P48 remains incomplete until immutable publication and the full suite
inventory are delivered by later slices.

GitHub rules API reference:
https://docs.github.com/en/rest/repos/rules

## Read-only CI evidence

GitHub omits `bypass_actors` from the read-only Actions token response. CI never
interprets an omitted field as an empty list. After full administrator verification,
`python3 scripts/ci/main-protection.py --record-receipt` records
`.github/rules/main.receipt.json`. Explicit `--apply` also records it only after
successful full administrator readback. The coordinator reviews and commits this
non-secret receipt with a relevant regression test.

When bypass actors are omitted, CI requires the reviewed receipt to match the
actual ruleset ID, exact aware `updated_at` instant, canonical configuration SHA256 and verified
empty bypass list. Equivalent offset spellings normalize to UTC; missing, naive or malformed timestamps fail.
Any settings edit changes the version and invalidates evidence;
missing or mismatched receipts fail. An explicit bypass list is always checked
directly. Read-only CI cannot mint administrator evidence, and receives no privileged
secret. The receipt attests to the administrator readback at that immutable version;
independent review protects the committed evidence from fabricated replacement.
