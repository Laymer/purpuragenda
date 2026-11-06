# P37/calendar-downloads — merged

- Agent and UTC checkpoint: codex, 2026-11-06T11:07:00Z.
- Current task: P37/calendar-downloads slice2 closed; branch task/p37-calendar-downloads; base main6ad38f0; PR https://github.com/Laymer/purpuragenda/pull/147.
- Last pushed implementation commit: d58bf31e30deb0576293a294bd0c95bc10c6f188; no implementation changes left local.
- What works: protected customer/owner downloads with persisted authority, expiry projection, private HTTP headers and no session/mutation effects.
- Decisions: UI/Google and unified calendar reporting continue in slice3. No migration, notification or attachment change.
- Verification: focused57 Java/runtime, final20 regression,668 Angular/build/CSP,373 Python and generated contract verification passed; independent calendar_download_review accepted exactd58bf31 without findings and ran89 Python cases. All15 hosted checks42204862573 passed, including complete build/coverage and real browser/persistence.
- Merge reconciliation: GitHub confirmed MERGED at2026-11-06T11:05:22Z, squash f3d38a42c6509a907daa2daf05a6c50f51ca272c; expected-head merge used d58bf31.
- Limits: no full local-build claim; real calendar clients remain manual gates. P37 still in progress.
- Next concrete action: follow docs/handoffs/codex-p37-calendar-actions.md; closure consolidated into its test-bearing commit.
- Recovery: task/p37-calendar-downloads atd58bf31 and mainf3d38a4; prototype6f9405b reference only; stacked dependencies: none.
