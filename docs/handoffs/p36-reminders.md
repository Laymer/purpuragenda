# Customer reminder deadlines and suppression

- Agent and UTC checkpoint: Codex-worker-c, 2026-10-26T22:31:00Z.
- Current task: P36/reminders; branch: task/p36-reminders; base: main; PR URL: not yet opened.
- Last pushed implementation commit: not yet pushed; local implementation commit7c5a125; uncommitted changes: append-only main72 integration/evidence union being published.
- What works: notification expansion/booking delivery slice1 confirmed mergedPR64 at669bc5b after full native/hosted/Compose and independent reviews. P23c112 and P24accessa1098ed are separately frozen awaiting next-main gates.
- What remains: reserved014 migration, original deadline/grace model, confirmed-only48h/2h generation, current-state/revision/grace suppression and meaningful unit/realPG/upgrade/race tests; all implemented gates and review.
- Decisions: coordinator allocated014/readiness/count ownership; preserve applied015/016/017 and append014 after017. No RuntimeApplication edits. No SMTP under database locks, live SMTP, provider purchase, deployment or volume deletion.
- Verification: comprehensive reactor passed17domain/18application/66adapters (26existing notification,5readiness,11migration,24reminder), zero failures/errors/skips; final added overdue-expansion case gives25reminderPG+6application+17domain passing37.792s.5strict report-guard regressions pass. Initial20PG had2fixture double-cleanup failures(SQL3F000), corrected; then a new real21case prototype audit reproduced duplicate reminder delivery(expected1/actual2), corrected with both-confirmation-path regressions. Safe counts retained locally under artifacts/p36-reminders-failure. Full module coverage/native/hosted/Compose and independent review remain pending.
- Next concrete action: implement bounded reminder deadline/grace and schema upgrade tests, run targeted realPG suite in this isolated worktree, publish reviewed tested draft.
- Recovery: origin/recovery/2026-10-25-prototype at6f9405b; all worktrees/parked prototype/volumes preserved; worktree /private/tmp/purpuragenda-p36-reminders; stacked PR dependencies: none, base87021617; applied017 preserved unchanged;014 totalchangesets17.
