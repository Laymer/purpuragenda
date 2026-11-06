# Private calendar exports

P37 slice1 supplies an application-owned `CalendarEvent` projection and `CalendarWriter` port, implemented by the iCal4j4.4.0 adapter. Slice2 composes protected customer and owner downloads through the existing persisted booking queries. Google prefill actions, Angular controls and unified mandatory calendar reporting remain sequential P37 work. There is no schema or email attachment change.

The projection accepts an already-authorized domain booking and a typed CUSTOMER/OWNER audience. It retains only booking UUID, audience, persisted calendar revision, session instants, mapped calendar status and optional participant meeting URL. It never retains intake, notes, contacts, service names, payment facts or authentication credentials; diagnostic text is redacted. Both authorized audiences can receive the participant link while the booking is confirmed; pending and terminal events omit it, matching existing booking views. A Zoom participant passcode inside the validated meeting URL is intentionally preserved; host/start/zak credentials remain prohibited by the domain Meeting validator.

UID is stable per booking and audience (`booking UUID-customer@purpuragenda` or `booking UUID-owner@purpuragenda`), independent of timestamp, status, booking version and calendar revision. SEQUENCE copies the persisted calendar revision exactly. The writer does not invent revision changes. DTSTAMP is supplied explicitly by the caller; there is no clock read. RFC5545 timestamps use whole UTC seconds (subsecond input is truncated). DTSTART/DTEND cover the actual snapshot service duration, never the120-minute reservation.

| Booking state | Calendar STATUS |
| --- | --- |
| PENDING_CONFIRMATION | TENTATIVE |
| CONFIRMED, COMPLETED, NO_SHOW | CONFIRMED |
| CANCELLED, REJECTED, EXPIRED | CANCELLED |

The summary is always “Consultation”. Description contains generic consultation text, a manual update/removal caveat and, when present, the validated Zoom participant URL as ordinary text. No METHOD, RSVP, attendees, organizer, URL property, attachment or alarm is emitted. Downloaded imports are snapshots; even a stable UID/SEQUENCE does not guarantee that a calendar client updates, deduplicates or removes an earlier import.

iCal4j owns property syntax, escaping and validation. Its output writer counts Java characters rather than UTF-8 octets, so the adapter folds its serialized logical lines at75 UTF-8 octets, including continuation spaces, without splitting code points. Output is UTF-8 with CRLF. Validation failures expose only a fixed error code. Tests cover all state mappings, both audiences, revisions, fixed timestamps, actual durations across Brussels DST, property allowlists, escaping/injection text, ASCII/Unicode folding boundaries and parse-back validation.

Real Apple/iPhone/Google initial, revised, duplicate, cancelled, timezone and Zoom-link imports remain manual launch gates. This slice does not certify those clients or complete P37.

## Protected downloads (slice2)

GET /api/v1/bookings/{bookingId}/calendar requires account ownership or the exact current guest scope and fixes the CUSTOMER audience. GET /api/v1/admin/bookings/{bookingId}/calendar requires a current persisted MFA administrator and fixes the OWNER audience. Both recheck persisted authority and booking facts in one consistent read-only snapshot. DTSTAMP uses that snapshot's captured database time. Unknown and unowned customer targets both return404; missing/revoked sessions rejected during initial inspection return401; a customer scope revoked between inspection and the query returns the same404 as an unowned target. Non-administrators cannot use the owner route. Identifiers never confer authority. Query parameters (including audience and token) are rejected.

Responses are UTF-8 text/calendar with fixed attachment filename consultation.ics, Cache-Control:no-store, Referrer-Policy:no-referrer and X-Content-Type-Options:nosniff. No ETag or Last-Modified is emitted; cache validators and Range do not cause304 or partial output. HEAD checks the same authority and returns no body. Errors use existing safe private problem responses. The privacy maintenance barrier blocks both routes.

Reads never touch session idle time, reclaim expiry, acquire the scheduling mutation lock or write effects. An elapsed PENDING_CONFIRMATION hold exports CANCELLED at expiry equality, using an in-memory expired projection and the unchanged persisted calendar revision; the database state remains pending until normal reclamation. Both routes serialize current booking facts, so later meeting/revision/cancellation changes appear on the next authorized download. The existing PostgreSQL evidence guard now requires guest, account and owner calendar HTTP suites; unified calendar/report/browser acceptance remains subsequent work.
