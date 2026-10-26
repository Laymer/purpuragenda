package com.purpuragenda.application.port.out;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** A locked credential and one database time. Authentication creates a one-use proof, never a session boolean. */
public interface AccountAuthenticationUnitOfWork {
    <T> T locked(String loginEmail,Function<Account,T> work);
    interface Account {
        Instant now();
        Optional<Credential> credential();
        Instant issueProof(String tokenHash,SessionStore.ProofBinding binding);
    }
    record Credential(UUID accountId,UUID customerId,String passwordHash,long version) {
        @Override public String toString() { return "AccountCredential[REDACTED]"; }
    }
    record AuthenticationProof(AdminMfaUnitOfWork.RecoveryCode token,Instant expiresAt) implements AutoCloseable {
        public AuthenticationProof { Objects.requireNonNull(token);Objects.requireNonNull(expiresAt); }
        @Override public void close() { token.close(); }
        @Override public String toString() { return "AccountAuthenticationProof[REDACTED]"; }
    }
}
