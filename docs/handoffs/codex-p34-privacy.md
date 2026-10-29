# P34/privacy

- Agent and UTC checkpoint: Codex-coordinator, 2026-10-29T17:47:00Z.
- Current task: P34/privacy; branch: task/p34-privacy; base: main 7ddd0b9; PR URL: pending draft creation.
- Last pushed implementation commit: 9105d39 (PR140, merged 7ddd0b9); uncommitted changes: tested privacy review/expiry policy, input guidance and scope reconciliation ready for checkpoint.
- What works: P32 merged; PR82/83/88 reconciled; tested civil review deadlines, exact-record holds, one-hour export expiry and accessible non-medical field guidance.
- What remains: controlled exports, corrections, reviewed erasure/tombstones; calendar, recovery and local-release verification.
- Decisions: owner retains existing intake/free-text fields with non-medical/sensitive-content prohibition. Local release is distinct from public activation; existing automated gates remain mandatory.
- Verification: PR140 exact9105d39 all15 checks green in run40751169792, recorded independent review clean, zero open code-scanning alerts; GitHub confirms merged7ddd0b9. Local114 domain tests/coverage, full Angular tests/build/CSP and364 Python tests pass; independent corrected-working-tree review clean. Initial sandbox esbuild/socket errors resolved by authorized reruns; hosted gates pending.
- Next concrete action: push tested checkpoint as draft PR; continue controlled export workflow, exact-head review and all hosted gates.
- Recovery: origin/recovery/2026-10-25-prototype at6f9405b; stacked PR dependencies: none.
