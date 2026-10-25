package com.purpuragenda.application;

import com.purpuragenda.application.port.out.AuthDeliveryStore;
import com.purpuragenda.application.port.out.GuestRecoveryLookup;
import java.util.Objects;

/** Internal request workflow. P17 must apply CSRF, origin and distributed throttling before invoking it. */
public final class GuestRecovery {
    private final GuestRecoveryLookup lookup;
    private final AuthDeliveryStore delivery;
    public GuestRecovery(GuestRecoveryLookup lookup,AuthDeliveryStore delivery) {
        this.lookup=Objects.requireNonNull(lookup);this.delivery=Objects.requireNonNull(delivery);
    }
    /** Both matching and unknown requests have the same empty accepted result. No authority is issued here. */
    public void request(GuestRecoveryLookup.Request request) {
        Objects.requireNonNull(request);
        lookup.find(request).ifPresent(id -> delivery.issue(new AuthDeliveryStore.Issue(
                request.email(),AuthDeliveryStore.Purpose.MANAGE,id,null)));
    }
}
