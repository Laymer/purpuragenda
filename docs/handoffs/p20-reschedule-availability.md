# Authorized rescheduling availability

- Agent and UTC checkpoint: Claude-lane-c, 2026-10-27T14:00:00Z (previous Codex-coordinator 2026-10-27T00:06:21Z).
- Current task: P20/reschedule-availability; branch: task/p20-reschedule-availability; base: task/p22-admin-finance-http; PR URL: https://github.com/Laymer/purpuragenda/pull/88.
- Last pushed implementation commit: e41192f (Codex-coordinator); uncommitted changes: append-only merge of updated finance PR86 (PR80/PR76 with main cc3fe39) resolving the previous conflicts; customer availability bean, operation set and SDK union retained, runtime gate stays18.
- What works: persisted customer changes and scoped booking reads are implemented. Public creation availability cannot correctly advise rescheduling of an archived service or exclude the customer's existing reservation.
- What remains: current-main integration, full native/coverage/hosted/Compose gates and independent review; management UI remains a separate slice.
- Decisions: one enforced read-only RR snapshot and captured time for authority, booking and scheduling context. No mutation, expiry reclamation, quota charge or session touch. Do not use public creation availability as a substitute. Root owns this released contract slot; no migration or root-route edits.
- Verification:106Java (44domain/7application/25queryPG/9HTTPPG/4wire/17runtime) pass with zero failures/errors/skips in297seconds.42Angular/fullbuild/CSP and complete handwritten119/119lines87/88branches pass.258strictPython/docs and SDK generation pass. First42Angular/1failure was an SDK query-encoding assertion; actual URL regression and full frontend rerun pass. Full native/coverage/hosted/Compose/review not yet run.
- Next concrete action: gh pr checks 88 --watch; exact independent review; coordinator retargets after PR86 merges.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and every worktree/secret/volume preserved; stacked PR dependencies:86/85/84/80/76/81, with74 already merged; management UI follows this and83.

Integrated target78Java (13domain/7application/25queryPG/10HTTPPG/5wire/18runtime) passes in331seconds, zero failures/errors/skips.259strictPython/docs and unchanged SDK regeneration pass. Review finding reproduced1wire failure (expected429/actual200): private advice bypassed public availability filter. Fixed normalized private paths to share persisted120/min budget; unit/path/storage and actual HTTP public/private/malformed-target boundary tests pass. Sanitized failure artifact retained.
