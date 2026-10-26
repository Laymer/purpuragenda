package com.purpuragenda.application.notification;

import com.purpuragenda.application.port.out.OutboxStore;
import com.purpuragenda.application.port.out.OutboxStore.Outcome;
import java.util.*;

/** Bounded expansion: one failing or unknown event never prevents the others from being expanded. */
public final class OutboxExpander {
    private final OutboxStore store;
    public OutboxExpander(OutboxStore store) { this.store=Objects.requireNonNull(store); }
    public Outcome tick() {
        try { return store.expandNext(NotificationPlanner::plan); }
        catch(RuntimeException failed) { throw new IllegalStateException("OUTBOX_EXPANSION_FAILED"); }
    }
    /** Expands up to {@code limit} events and returns how many were consumed (including unknown and silent types). */
    public int drain(int limit) {
        if(limit<1 || limit>1000)throw new IllegalArgumentException("INVALID_EXPANSION_LIMIT");
        int consumed=0;
        while(consumed<limit && tick()!=Outcome.IDLE)consumed++;
        return consumed;
    }
}
