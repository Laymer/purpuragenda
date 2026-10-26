package com.purpuragenda.application;

import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.customer.Input;
import java.util.*;

/** A current persisted account session and verification of its old password authorize replacement and sign-out. */
public final class AccountPasswordChange {
    public record Result(boolean changed,long retryAfterSeconds) {
        public Result { if(retryAfterSeconds<0 || changed && retryAfterSeconds!=0)throw new IllegalArgumentException("INVALID_PASSWORD_CHANGE_RESULT"); }
        @Override public String toString() { return "AccountPasswordChangeResult[REDACTED]"; }
    }
    private final AccountPasswordUnitOfWork store;
    private final PasswordHash passwords;
    private final RequestThrottle throttle;
    public AccountPasswordChange(AccountPasswordUnitOfWork store,PasswordHash passwords,RequestThrottle throttle) {
        this.store=Objects.requireNonNull(store);this.passwords=Objects.requireNonNull(passwords);this.throttle=Objects.requireNonNull(throttle);
    }
    public Result change(String email,SessionBinding session,char[] previous,char[] replacement,String clientIp) {
        try {
            String normalized=Input.email(email);Objects.requireNonNull(session);
            var admission=throttle.accountAttempt(normalized,clientIp);
            if(!admission.allowed())return new Result(false,admission.retryAfterSeconds());
            try {
                try { PrivateAdminBootstrap.validatePassword(previous);PrivateAdminBootstrap.validatePassword(replacement); }
                catch(DomainFailure invalid) { throw new DomainFailure("INVALID_ACCOUNT_PASSWORD"); }
                // Memory-hard derivation never holds the database account/session locks.
                String encoded=passwords.hash(replacement);
                boolean changed=store.locked(normalized,session,account->{
                    var credential=account.credential();
                    if(credential.isEmpty() || !passwords.verify(previous,credential.get().passwordHash()))return false;
                    account.replacePassword(encoded);return true;
                });
                if(changed) {
                    // Credential replacement already committed; a failed slot release must not hide successful sign-out.
                    try { admission.attempt().succeeded(); } catch(RuntimeException ignored) { }
                }
                return new Result(changed,0);
            } finally { admission.attempt().failed(); }
        } finally { if(previous!=null)Arrays.fill(previous,'\0');if(replacement!=null)Arrays.fill(replacement,'\0'); }
    }
}
