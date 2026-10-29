package com.purpuragenda.domain.privacy;

import java.time.*;
import java.util.Objects;

/** Temporary export availability uses elapsed time, independently of civil retention dates. */
public record TemporaryExport(Instant createdAt) {
    public TemporaryExport { Objects.requireNonNull(createdAt); createdAt.plus(Duration.ofHours(1)); }
    public Instant expiresAt() { return createdAt.plus(Duration.ofHours(1)); }
    public boolean availableAt(Instant now) {
        Objects.requireNonNull(now);
        return !now.isBefore(createdAt) && now.isBefore(expiresAt());
    }
}
