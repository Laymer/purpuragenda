# Authorized rescheduling availability

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-26T23:56:00Z.
- Current task: P20/reschedule-availability; branch: task/p20-reschedule-availability; base: main; PR URL: none yet.
- Last pushed implementation commit: none; uncommitted changes: application/port, PostgreSQL snapshot query, protected HTTP/bootstrap, typed contract/SDK and tests, based on released finance86/96698e0 contract ancestry.
- What works: persisted customer changes and scoped booking reads are implemented. Public creation availability cannot correctly advise rescheduling of an archived service or exclude the customer's existing reservation.
- What remains: current-main integration, full native/coverage/hosted/Compose gates and independent review; management UI remains a separate slice.
- Decisions: one enforced read-only RR snapshot and captured time for authority, booking and scheduling context. No mutation, expiry reclamation, quota charge or session touch. Do not use public creation availability as a substitute. Root owns this released contract slot; no migration or root-route edits.
- Verification:106Java (44domain/7application/25queryPG/9HTTPPG/4wire/17runtime) pass with zero failures/errors/skips in297seconds.42Angular/fullbuild/CSP and complete handwritten119/119lines87/88branches pass.258strictPython/docs and SDK generation pass. First42Angular/1failure was an SDK query-encoding assertion; actual URL regression and full frontend rerun pass. Full native/coverage/hosted/Compose/review not yet run.
- Next concrete action: finish runtime/frontend targets, publish draft and release contract slot to enrollment; integrate current main79 append-only, run all gates and obtain independent review.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and every worktree/secret/volume preserved; stacked PR dependencies:86/85/84/80/76/81, with74 already merged; management UI follows this and83.
