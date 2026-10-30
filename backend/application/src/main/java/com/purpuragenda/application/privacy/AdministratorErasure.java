package com.purpuragenda.application.privacy;

import com.purpuragenda.application.port.out.CustomerErasureStore;
import java.util.Objects;

/** Application boundary exposes only reviewed privacy operations, never automatic retention deletion. */
public final class AdministratorErasure {
    private final CustomerErasureStore store;
    public AdministratorErasure(CustomerErasureStore store){this.store=Objects.requireNonNull(store);}
    public CustomerErasureStore store(){return store;}
}
