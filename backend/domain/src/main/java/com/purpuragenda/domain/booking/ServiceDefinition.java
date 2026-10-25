package com.purpuragenda.domain.booking;

import static com.purpuragenda.domain.DomainFailure.require;
import com.purpuragenda.domain.customer.Input;
import java.util.UUID;

public record ServiceDefinition(UUID id, long version, String name, int durationMinutes, Money price, boolean active) {
    public ServiceDefinition {
        require(id != null && version > 0, "INVALID_SERVICE");
        name = Input.text(name, 1, 120);
        require(durationMinutes >= 1 && durationMinutes <= 120, "INVALID_DURATION");
        require(price != null && price.currency().equals("EUR"), "INVALID_CURRENCY");
        require(price.minor() <= 100_000_000L, "INVALID_AMOUNT");
    }
}
