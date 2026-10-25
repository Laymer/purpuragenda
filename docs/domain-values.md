# Domain value contracts

Money uses integer minor units and a recognized uppercase ISO currency. Service prices are EUR, 0–100000000 cents; session duration is an integer 1–120 minutes. Archived services remain valid immutable values. Service identity is non-null and its version is positive.

Customer text is stripped and NFC-normalized, with limits measured in Unicode code points. Names allow 1–100, birthplace 1–200, message up to 1000; message may contain ordinary multiline whitespace, other control characters fail. Mailbox identity uses an ASCII local part plus IDNA domain, lowercases for identity comparison, bounds the final ASCII mailbox to 254 characters and does not strip plus labels or dots. International phones normalize accepted formatting to a leading plus and 7–15 digits. Display zones must be available named IANA zones.

Intake preserves civil birth date and time (including supplied seconds) without location or timezone inference. Birth time cannot contain fractional seconds. Validation against authoritative time uses the Europe/Brussels date and accepts birth date equal to that date. HTTP parsing and unexpected-field/body limits belong to the later adapter tasks.

Intervals are half-open: adjacency does not overlap. The reservation factory always adds 120 elapsed minutes, across DST and month ends. Session duration is independent. The generic interval constructor also supports arbitrary nonempty windows and blackouts; it does not establish a booking or authorize occupancy. Actual scheduling mutations and the database overlap constraint are later tasks.

Business validation failures contain safe fixed codes, not supplied values. No payment, scheduling policy, persistence or booking-state behavior is certified by these value contracts.
