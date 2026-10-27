package com.purpuragenda.application.port.out;

import java.util.function.ToIntFunction;

/** Internal maintenance shares scheduling's resource lock, one database time and atomic effects; no synthetic command receipts. */
public interface SchedulingMaintenanceUnitOfWork {
    int maintain(ToIntFunction<SchedulingUnitOfWork.Transaction> work);
}
