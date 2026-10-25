package com.purpuragenda.application.port.out;

import com.purpuragenda.domain.customer.Input;
import java.util.Optional;
import java.util.UUID;

/** Exact reference and current delivery contact only; never an email-address history search. */
public interface GuestRecoveryLookup {
    record Request(String reference,String email) {
        public Request { reference=Input.text(reference,8,80);email=Input.email(email); }
        @Override public String toString() { return "GuestRecoveryRequest[REDACTED]"; }
    }
    Optional<UUID> find(Request request);
}
