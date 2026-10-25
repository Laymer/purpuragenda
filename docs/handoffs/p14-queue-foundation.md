# P14 queue foundation handoff

- Agent and UTC checkpoint: Codex-p14, 2026-10-25T09:31:09Z.
- Current task: P14/queue-foundation; branch: task/p14-queue-foundation; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/19.
- Last pushed implementation commit: 2c6fce4 (reviewed implementation9c3faff plus prerequisites); uncommitted changes: original-due preservation regression and final PR/CI evidence before tested checkpoint.
- What works: P12 shell merged c1151b2 with actual-container and Angular CI; crypto/migration/security/inventory foundations merged.
- What remains: final checkpoint exact-head rerun and coordinator merge; SMTP/auth envelopes later P14 slice.
- Decisions: explicit READ_COMMITTED; pg_stat_activity barrier before expiry change; no P36 event expansion/reminders; no network calls in queue transaction; per-booking/audience advisory lock plus database exclusion; stale completion cannot record attempts.
- Verification: initial exact-head CI40180605912 at2c6fce4 all7jobs passed;10queue+11migration+11core realPG cases,46adapter+6application+108domain+3architecture tests, zero skips. Coverage adapter202/205lines151/166branches; app32/33lines48/48branches; Angular100%;124rootPython+31policy and472package advisory scan passed. Local full command fails Docker as required; real CI supplies integrity evidence.
- Next concrete action: push tested final checkpoint and require exact-head real PostgreSQL CI/coordinator merge.
- Recovery: recovery/2026-10-25-prototype at6f9405b; stacked PR dependencies: P12/P13/P07 merged; P08 transaction-core6c1f96f merged and integrated; booking/policy persistence owned separately; P43 dependencies2c896ff integrated.
