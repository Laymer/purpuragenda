package com.purpuragenda.application;

import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.AdminMfaUnitOfWork.Enrollment;
import com.purpuragenda.application.port.out.SessionStore.*;
import com.purpuragenda.domain.customer.Input;
import java.util.*;
import java.util.function.Function;

/** Restricted recovery only: this workflow cannot create or exchange a session. */
public final class AdministratorRecovery {
    public record Attempt<T extends AutoCloseable>(Optional<T> value,long retryAfterSeconds) implements AutoCloseable {
        public Attempt { Objects.requireNonNull(value);if(retryAfterSeconds<0 || retryAfterSeconds>0 && value.isPresent())throw new IllegalArgumentException("INVALID_RECOVERY_ATTEMPT"); }
        @Override public void close() { value.ifPresent(item->{try { item.close(); } catch(Exception failure) { throw new IllegalStateException("RECOVERY_CLEANUP_FAILED"); }}); }
        @Override public String toString() { return "AdministratorRecoveryAttempt[REDACTED]"; }
    }
    private final PrivateAdminRecovery recovery;private final RequestThrottle throttle;private final AdminRecoveryBinding bindings;
    public AdministratorRecovery(PrivateAdminRecovery recovery,RequestThrottle throttle,AdminRecoveryBinding bindings) {
        this.recovery=Objects.requireNonNull(recovery);this.throttle=Objects.requireNonNull(throttle);this.bindings=Objects.requireNonNull(bindings);
    }
    public Attempt<PrivateAdminRecovery.Reenrollment> begin(Snapshot session,String email,char[] password,char[] code,String client) {
        return attempt(session,email,password,code,client,account->recovery.begin(account,password,code,bindings.derive(session.reference())));
    }
    public Attempt<Enrollment> reenroll(Snapshot session,String email,char[] password,char[] token,String totp,String client) {
        return attempt(session,email,password,token,client,account->recovery.reenroll(account,password,token,bindings.derive(session.reference()),totp));
    }
    private <T extends AutoCloseable> Attempt<T> attempt(Snapshot session,String email,char[] password,char[] bearer,String client,Function<String,Optional<T>> work) {
        try {
            Objects.requireNonNull(session);
            if(session.kind()!=Kind.ANONYMOUS)return new Attempt<>(Optional.empty(),0);
            String account=Input.email(email);var admission=throttle.adminAttempt(account,client);
            if(!admission.allowed())return new Attempt<>(Optional.empty(),admission.retryAfterSeconds());
            boolean success=false;
            try { var result=work.apply(account);success=result.isPresent();return new Attempt<>(result,0); }
            finally {
                if(success) { try { admission.attempt().succeeded(); } catch(RuntimeException conservative) { /* Retain uncertain charge. */ } }
                else admission.attempt().failed();
            }
        } finally { if(password!=null)Arrays.fill(password,'\0');if(bearer!=null)Arrays.fill(bearer,'\0'); }
    }
}
