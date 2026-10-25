# P40 coverage foundation handoff


- Agent and UTC checkpoint: p40_coverage, 2026-10-25T08:02:26Z.
- Current task: P40 coverage foundation; branch: task/p40-coverage-foundation; base: main at 8d7f62d; PR URL: pending.
- Last pushed implementation commit: none for P40; uncommitted changes: P40 coverage foundation, tests and evidence before commit.
- What works: P01–P06 merged. What remains: independent review and exact-head CI; P40 final application coverage acceptance remains later.
- Decisions: retain domain/application 95% lines, enforce 90% lines elsewhere and 90% branches everywhere; no aggregate masking.
- Verification: bash scripts/ci/verify-build.sh passed 108 domain + 3 architecture + 23 coverage-gate tests, zero skips; 276/277 domain lines and 478/489 branches; git diff --check passed.
- Next concrete action: push tested commit, open draft P40 foundation PR, and request independent review.
- Recovery: recovery/2026-10-25-prototype at 6f9405b; stacked PR dependencies: none; coordinator reconciles shared ledger.

One append-only JaCoCo file per module covers unit/integration forks; P38 must wire any external-process data before final reports. Angular must instrument all handwritten src TypeScript and emit frontend/coverage/coverage-final.json. No PostgreSQL, browser, load, security or restore result is claimed by this slice.
