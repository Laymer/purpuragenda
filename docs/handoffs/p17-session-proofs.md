# Persistent sessions and factor handoff

- Agent and UTC checkpoint: Codex-worker-b, 2026-10-25T15:55:56Z.
- Current task: P17/session-proofs; branch: task/p17-session-proofs; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/45.
- Last pushed implementation commit: cbc23a3; uncommitted changes: corrected synthetic upgrade fixture and independent-review MANAGE/session-lock tests, mandatory report guard and checkpoint evidence.
- What works: merged P16recovery010 and P15guest011; new SessionStore and JDBC adapter compile with productionmaster012. 108domain/67application and15report checks pass; 26of27 initial realPG session cases pass; one upgrade-fixture column typo corrected; expanded29-case suite awaits rerun.
- What remains: execute29new PG cases and every implemented category, resolve findings, publish/review exacthead; HTTP CSRF/origin/throttles/cleanup remain separateP17slices.
- Decisions: SessionStore.SessionReference(id,browserHash) stays internal/redacted; cookie bearer token hashed, stable server-owned browser binding across rotation; adminproof insidefactortransaction then single consumption underadminlock/epochrecheck. Guestexchange acquires resource→P15locks→session; adminexchange admin→session; session-only touches acquire no laterauthoritylocks. No authbypass/publicregistration.
- Verification: pinned test compilation passes; 108domain/67application unit and15report regression cases pass. Docker engine/socket absent after orderly stop reported by workerC; routine restart recovered29.6.2, with isolated smoke in progress. Initial27-case PG run had one SQLSTATE42703fixture failure; no production defect found. Expanded29-case/fullcoverage acceptance pending.
- Next concrete action: finish13recovery regression cases, run fullnativebuild including corrected29-case session suite, then exactheadreview and CI.
- Recovery: recovery/2026-10-25-prototype at6f9405b preserved; previousP16worktree/private/tmp/purpuragenda-p16-recovery-persistence clean; current/private/tmp/purpuragenda-p17-session-proofs; own preservedautostasha5264e9 aftermainintegration; stacked PR dependencies: P15migration011 merged765cbf5; P09adapter awaits012.
