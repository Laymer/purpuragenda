package com.purpuragenda.domain.scheduling;

import static com.purpuragenda.domain.DomainFailure.require;
import java.time.*;

public record SchedulingPolicy(long version, int gridMinutes, int dailyCapacity, Duration minimumNotice,
                               Duration pendingLifetime, Duration expiryBuffer, Duration customerCutoff) {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Europe/Brussels");
    public static final int CUSTOMER_LIMIT = 3;
    public SchedulingPolicy {
        require(version > 0 && gridMinutes >= 1 && gridMinutes <= 120 && dailyCapacity >= 0, "INVALID_POLICY");
        require(minimumNotice != null && !minimumNotice.isNegative() && pendingLifetime != null && pendingLifetime.isPositive()
                && expiryBuffer != null && !expiryBuffer.isNegative() && customerCutoff != null && !customerCutoff.isNegative(), "INVALID_POLICY");
    }
    public static SchedulingPolicy defaults() { return new SchedulingPolicy(1, 30, 3, Duration.ofHours(24), Duration.ofHours(24), Duration.ofHours(1), Duration.ofHours(24)); }
    public Instant horizon(Instant now) { return now.atZone(BUSINESS_ZONE).plusMonths(1).toInstant(); }
    public Instant expiresAt(Instant now, Instant start) {
        Instant lifetime = now.plus(pendingLifetime), buffer = start.minus(expiryBuffer);
        Instant result = lifetime.isBefore(buffer) ? lifetime : buffer;
        require(result.isAfter(now), "IMPOSSIBLE_PENDING_HOLD");
        return result;
    }
    public boolean customerMayChange(Instant start, Instant now) { return !Duration.between(now, start).minus(customerCutoff).isNegative(); }
}
