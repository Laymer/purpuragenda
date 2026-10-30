# Manual screenshot provenance

These images are actual Chromium captures of the isolated local application with synthetic data. They illustrate the current development manual; they do not certify a release or public-use readiness.

The screenshot steps live in `acceptance-tests/src/test/java/com/purpuragenda/acceptance/CoreJourneyE2eTest.java`. Capture is opt-in:

```sh
source scripts/dev/toolchain-env.sh
PURPURAGENDA_MANUAL_SCREENSHOTS=1 python3 scripts/ci/verify-e2e.py
```

The orchestrator starts a separate real Compose stack, privately enrolls the administrator, and exercises the normal booking, account and administrator flows. Images are written to `artifacts/manual-screenshots/`. Inspect them before copying the eight numbered PNGs here. Re-running with different synthetic references or dates changes those values naturally.

Capture only the named non-authentication screens. Password and one-time-code inputs are masked. Do not add captures of enrollment secrets, recovery codes, bearer links, browser storage or production/customer data. No credentials or raw provider diagnostics belong in this directory.

The README test verifies all eight local assets, their PNG signatures and dimensions. Browser acceptance and CI remain separate mandatory checks. The owner-provided banner in the root README is retained separately from these generated captures.
