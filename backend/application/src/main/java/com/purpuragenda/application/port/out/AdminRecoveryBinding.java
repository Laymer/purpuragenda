package com.purpuragenda.application.port.out;

import com.purpuragenda.application.ReenrollmentGrantPolicy.Binding;
import com.purpuragenda.application.port.out.SessionStore.SessionReference;

/** Derives a purpose-specific binding exclusively from the persisted server session reference. */
public interface AdminRecoveryBinding {
    Binding derive(SessionReference session);
}
