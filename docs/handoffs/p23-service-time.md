# Public service and time selection

- Agent and UTC checkpoint: Codex-worker-c, 2026-10-26T15:45:00Z.
- Current task: P23/service-time; branch: task/p23-service-time; base: main (7216986); PR URL: not yet created.
- Last pushed implementation commit:166d568; uncommitted changes: real public selection component/helpers/style and22Angular cases, lazy /book route/homeCTA and evidence being consolidated into first checkpoint.
- What works: real catalog/availability selection, canonical IANA zones and explicit UTCoffsets for repeated instants, versioned in-memory selection, stale-request cancellation and choices cleared on every parameter/version refresh. Safe loading/error/empty/retry/429 states and labelled native keyboard controls.
- What remains: full implemented gates, current-main integration, realbrowser preview and independent exact-head review. Authenticated session/access/intake/submission remain later P17/P20/P24 slices; no fake final action.
- Decisions: exclusively own handwritten frontend routes/home/public feature and tests; no OpenAPI/generated changes. Selection stays in memory; no fake booking submission. Shared authenticated access/intake follows coordinated P17/P20 work.
- Verification:22Angular cases and production build/sourcecompletecoverage pass104/104lines82/82branches; initial339.59kB and lazy68.33kB. First compile/type and2encoded-query fixture failures were diagnosed/fixed and preserved as sanitized counters; fullnative/hosted gates pending.
- Next concrete action: publish coherent tested checkpoint, integrate current main without rewriting history, run all mandatory categories and independent review; browser/Compose resource slot coordinated by root.
- Recovery: origin/recovery/2026-10-25-prototype at6f9405b; existing worktrees/volumes preserved; sibling P36 draft64 remains frozen at48705cd awaiting finalmainintegration; stacked PR dependencies: mergedP19 only.
