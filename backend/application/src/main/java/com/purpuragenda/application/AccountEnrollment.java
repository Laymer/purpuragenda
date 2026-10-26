package com.purpuragenda.application;

import com.purpuragenda.application.port.out.AccountEnrollmentStore;
import com.purpuragenda.application.port.out.AccountEnrollmentStore.*;
import com.purpuragenda.application.port.out.PasswordHash;
import com.purpuragenda.domain.DomainFailure;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Explicit optional account enrollment. A verified email proof alone never creates an account: the customer must
 * also choose a credential, and the same proof consumed here cannot be used for guest access.
 */
public final class AccountEnrollment {
    private final AccountEnrollmentStore store;
    private final PasswordHash passwords;
    public AccountEnrollment(AccountEnrollmentStore store, PasswordHash passwords) {
        this.store = Objects.requireNonNull(store);
        this.passwords = Objects.requireNonNull(passwords);
    }
    /** Hashing happens before the transaction so no database lock is held during the memory-hard derivation. */
    public Optional<Enrolled> enroll(String token, String browserHash, char[] password) {
        try {
            String encoded;
            try {
                PrivateAdminBootstrap.validatePassword(password);
                encoded = passwords.hash(password);
            } catch (DomainFailure invalid) { throw new DomainFailure("INVALID_ACCOUNT_PASSWORD"); }
            return store.enroll(new Enrollment(token, browserHash, encoded));
        } finally { if (password != null) Arrays.fill(password, '\0'); }
    }
}
