package com.purpuragenda.application.port.out;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.View;
import java.util.function.Function;

/**
 * Read-only configuration snapshot. The adapter checks the persisted ADMIN session (enabled, enrolled, unrevoked, MFA
 * completed, current security epoch) in the same snapshot before the work runs; no session touch or resource lock.
 */
public interface ConfigurationQuery {
    <T> T read(SessionBinding session,Function<View,T> work);
}
