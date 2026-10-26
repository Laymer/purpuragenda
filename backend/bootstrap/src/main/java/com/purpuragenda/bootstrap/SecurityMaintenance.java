package com.purpuragenda.bootstrap;

import com.purpuragenda.application.SecurityCleanup;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Overlap-safe entry point for the hourly retention job. RuntimeWorkers invokes {@link #tick()}.
 * Failures are reduced to step names and never logged with details.
 */
final class SecurityMaintenance {
    private final SecurityCleanup cleanup;
    private final AtomicBoolean running = new AtomicBoolean();

    SecurityMaintenance(SecurityCleanup cleanup) { this.cleanup = Objects.requireNonNull(cleanup); }

    /** A concurrent invocation returns immediately with a skipped report instead of queueing a second sweep. */
    SecurityCleanup.Report tick() {
        if (!running.compareAndSet(false, true)) return new SecurityCleanup.Report(Map.of(), Set.of("overlap"));
        try { return cleanup.runOnce(); }
        finally { running.set(false); }
    }

    @Override public String toString() { return "SecurityMaintenance[configuration=REDACTED]"; }
}
