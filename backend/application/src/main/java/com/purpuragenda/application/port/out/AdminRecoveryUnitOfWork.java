package com.purpuragenda.application.port.out;

import com.purpuragenda.application.ReenrollmentGrantPolicy.Binding;
import com.purpuragenda.application.port.out.AdminMfaUnitOfWork.Secret;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** Recovery shares the administrator factor lock and never creates ordinary authorization. */
public interface AdminRecoveryUnitOfWork {
    <T> T locked(String emailKey,Function<Admin,T> work);
    interface Admin {
        Instant now();
        Optional<Identity> identity();
        Optional<Setup> begin(String recoveryHash,Binding binding,String tokenHash);
        Optional<Secret> prove(String tokenHash,Binding binding);
        void reenroll(long acceptedStep,List<String> recoveryHashes);
    }
    record Setup(Secret secret,Instant expiresAt) {
        public Setup { Objects.requireNonNull(secret);Objects.requireNonNull(expiresAt); }
        @Override public String toString() { return "AdminRecoverySetup[REDACTED]"; }
    }
    record Identity(UUID id,String passwordHash) {
        @Override public String toString() { return "AdminRecoveryIdentity[REDACTED]"; }
    }
}
