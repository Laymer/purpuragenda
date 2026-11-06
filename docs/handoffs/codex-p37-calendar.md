# P37/calendar-model

- Agent and UTC checkpoint: codex, 2026-11-06T10:15:21Z.
- Current task: P37/calendar-model slice1; branch: task/p37-calendar-model; base: main 74d4d9f; PR URL: pending first push.
- Last pushed implementation commit: none (first tested implementation checkpoint prepared); uncommitted changes: model, writer,12 new calendar tests, iCal4j4.4.0 dependency and documentation.
- What works: typed minimal projection, persisted revision, supplied DTSTAMP, UTC actual sessions, stable audience UIDs, generic private content, iCal4j validation/escaping and75-octet UTF-8 folding.
- Decisions: no migration, HTTP, UI or attachments; preserve erasure behavior and PR31. Four audit wording corrections reconstructed from implementation and independently corroborated; exact earlier list unavailable.
- Verification: focused25 tests (13 domain,4 projection,8 writer) pass; pinned Python371 and delivery-policy31 pass; check-docs passes. Working-source independent calendar_review found no blocker; suggested distinct-booking UID regression added. Full local build and all hosted categories pending; unchanged coverage gates remain mandatory.
- Limits and failures: first focused filter selected no domain tests and failed closed; fixed by including BookingTest. First parse-back assertion compared Instant with iCal4j ZonedDateTime; corrected to Instant.from. Unpinned system Python failed a preexisting archive-link fixture; all371 pass with repository-pinned Python3.12.15. No skipped dependency or weakened gate.
- What remains: full verification, final committed-head independent review and green merge. Parent P37 remains incomplete; protected downloads/UI/calendar report integration and real calendar imports remain separate gates.
- Next concrete action: push tested draft PR, execute full verification, independently review final head and squash only after all checks pass.
- Recovery: origin/recovery/2026-10-25-prototype at6f9405b reference only; stacked PR dependencies: none.
