# Customer booking query

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-26T15:14:00Z.
- Current task: P20/customer-view; branch: task/p20-customer-view; base: main; PR URL: none yet.
- Last pushed implementation commit: PR67 exact06d68d3 confirmed merged7216986; uncommitted changes: P20 read-only query, projection and tests implemented; publication pending.
- What works: automatic AUTH delivery and hourly security cleanup now composed; public catalog, sessions, scheduling and finance foundations remain merged. Guest proof HTTP, account enrollment and notification jobs are reviewed drafts integrating main in separate worktrees.
- What remains: authorized customer query/commands HTTP, workflow UI and remaining localv1 gates; no full milestone completion claimed.
- Decisions: P20 reads use a consistent read-only snapshot and current persisted authority, without session touches or expiry mutations. Mutation commands retain their resource lock/recheck. P17 owns current guest contract until merge; no contract change in this slice.
- Verification: PR67 exact06d68d3 full native853Java/6Angular/233Python and coverage passed, all14hosted checks, clean localDocker six-check receipt, independent exact-head review; GitHub confirms merge721698635df8ae3c600d75908d100ab6ed0cf0d3 at2026-10-26T15:12:56Z. New slice4application/19actual PostgreSQL and234strictPython cases pass; full gates pending.
- Next concrete action: implement application query/port and read-only JDBC authority, current financial projections and cutoff/action tests; publish bounded slice and run every implemented category.
- Recovery: origin/recovery/2026-10-25-prototype6f9405b and existing worktrees/secrets/volumes preserved; stacked PR dependencies: none for query slice; HTTP follows guest proof PR68.
