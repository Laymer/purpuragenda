# Booking state contracts

Pending requests may be confirmed, rejected, expired or cancelled. Confirmed appointments may be cancelled, completed or marked no-show. Terminal states never return to pending/confirmed. Constructors reject inconsistent confirmation history; confirmed/completed/no-show records retain validated meeting details and a confirmation basis. Manual approval records its basis and makes no payment assertion.

Service, intake, policy, creation contact, display zone and notice-version snapshots are immutable. Rescheduling retains the same snapshot and state, atomically evaluated later by application scheduling; it never extends a pending deadline. A verified delivery-contact amendment changes neither creation contact nor customer identity. Completion/no-show preserve occupancy and daily-capacity facts while no longer consuming the normal active allowance.

Approval needs a live future hold and meeting. Expiry equality is expired. Customer changes require inclusive policy cutoff; administrator changes still require application authorization/audit and scheduling validation. Early outcomes need a reason; outcomes cannot precede the session start. Confirmed cancellation, rescheduling and meeting updates advance calendar revision.

Meeting parsing requires HTTPS, a configured allowed domain or its subdomain, a numeric participant /j/ path and at most the supported participant pwd query. It rejects IP literals, userinfo, ports, fragments, host/start paths and unknown credentials. No network fetch occurs. Meeting construction is private; its diagnostic string is redacted. Persistence adoption must parse stored URLs with configured allowed domains instead of the recovery adapter's public constructor. Uniqueness across bookings remains an application/database task.

These contracts do not implement authorization, transactions, notifications or external payment facts. Those tasks must invoke them inside the specified scheduling unit of work.
