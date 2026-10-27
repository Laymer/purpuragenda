package com.purpuragenda.application.port.out;

import com.purpuragenda.application.port.out.CustomerBookingQuery.Authorized;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import java.util.Optional;

/** Exactly the live guest grant's target, never account ownership or guest history. */
public interface GuestBookingQuery {
    /** Resolves and authorizes the target in one read-only snapshot without touching the session. */
    Optional<Authorized> find(SessionBinding session);
}
