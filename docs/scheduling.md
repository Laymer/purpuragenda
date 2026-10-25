# Scheduling domain contracts

Europe/Brussels defines business dates, weekly windows, date exceptions and the midnight-anchored start grid (1–120 minutes). Windows permit starts in [start,end); the actual session and 120-minute reservation may cross ordinary closing times or breaks. Default daily windows offer 09:00 through 19:30 on a 30-minute grid. Adjacent or intersecting windows merge; inherited windows apply unless replaced/closed by an exception. ADD windows merge with inheritance.

Nonexistent DST local times yield no candidates. Repeated times yield both distinct instants, in instant order. Display-zone selection and offset labels belong to later application/UI tasks. Minimum notice is elapsed time, inclusive at equality. The inclusive horizon adds one calendar month in Brussels, clamps month ends, advances through gaps and retains the source offset when valid during an overlap. Captured authoritative time must be supplied by the application transaction.

Pending expiry is the earlier of creation plus lifetime and start minus buffer, and must be strictly after creation. Customer cutoff comparison is inclusive. Daily, active and request limits remain separate rules; capacity zero blocks new requests. The application must obtain accurate counts and preserve immutable submission charges under its resource lock.

A hard blackout intersects the full reservation, including time after the session ends. An administrator override requires a reason and explicit rules, including every intersecting blackout ID. Existing reservation overlap and nonfuture starts cannot be overridden. Domain rule evaluation never performs persistence or authorizes an actor; later application and PostgreSQL tasks enforce these decisions atomically.
