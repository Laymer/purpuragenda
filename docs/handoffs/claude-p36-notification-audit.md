# P36/notification-audit

- Agent and UTC checkpoint: claude_p36, 2026-11-06T08:42:42Z.
- Current task: P36/notification-audit (remaining-scenario audit); branch: task/p36-notification-audit; base: main 8735d0a; PR URL: pending first push.
- Last pushed implementation commit: none yet; uncommitted changes: audit document, lifecycle matrix and erasure suppression assertions, guard raised to 35 cases, P35 closure notes consolidated.
- What works: docs/notification-audit.md maps every emitted outbox type and delivery requirement to code and tests. New 9-case real-PostgreSQL lifecycle matrix (REJECTED, EXPIRED, CANCELLED, APPROVED, CONFIRMED_EXTERNAL_PAYMENT, MEETING_CHANGED, PAYMENT_RECORDED, PAYMENT_RECONCILIATION_REQUIRED, REFUND_REQUIRED). Identity erasure case now proves queued mail is suppressed for both audiences. Adapter tests compile; queue report guard tests pass; check-docs passes.
- What remains: hosted PostgreSQL execution of the new cases (no local Docker), independent review, green squash merge. Then P37 private calendars.
- Decisions: no production change; implementation already meets the plan. Observation recorded for the owner: identity erasure rewrites already SENT/FAILED job states to SUPPRESSED. Calendar attachments remain deferred by the 2026-10-29 scope.
- Verification: ./mvnw -o -pl backend/adapters -am test-compile; python3 -m unittest scripts/ci/test_queue_report.py (5 OK); scripts/ci/check-docs.py. PostgreSQL cases verified only by hosted CI.
- Next concrete action: push, open draft PR, watch hosted checks, request independent review.
- Recovery: origin/recovery/2026-10-25-prototype at 6f9405b; stacked PR dependencies: none.
