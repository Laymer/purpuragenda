# Private calendar exports

P37 slice1 supplies an application-owned `CalendarEvent` projection and `CalendarWriter` port, implemented by the iCal4j4.4.0 adapter. It does not expose a download endpoint yet. Authorization, private HTTP headers, Google prefill actions, Angular controls and mandatory calendar report integration remain sequential P37 work. No schema, API or email attachment change is part of this slice.

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
