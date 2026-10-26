package com.purpuragenda.application;

import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.AccountAuthenticationUnitOfWork.*;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.customer.Input;
import java.util.*;

/** Password verification and proof issuance are atomic; callers must consume the proof to rotate a persisted session. */
public final class AccountAuthentication {
    public record Attempt(Optional<AuthenticationProof> proof,long retryAfterSeconds) implements AutoCloseable {
        public Attempt { proof=Objects.requireNonNull(proof);if(retryAfterSeconds<0 || proof.isPresent() && retryAfterSeconds!=0)throw new IllegalArgumentException("INVALID_ACCOUNT_ATTEMPT"); }
        @Override public void close() { proof.ifPresent(AuthenticationProof::close); }
        @Override public String toString() { return "AccountAuthenticationAttempt[REDACTED]"; }
    }
    private final AccountAuthenticationUnitOfWork store;
    private final PasswordHash passwords;
    private final AdminFactorCrypto tokens;
    private final RequestThrottle throttle;
    private final String dummyHash;
    public AccountAuthentication(AccountAuthenticationUnitOfWork store,PasswordHash passwords,AdminFactorCrypto tokens,RequestThrottle throttle) {
        this.store=Objects.requireNonNull(store);this.passwords=Objects.requireNonNull(passwords);
        this.tokens=Objects.requireNonNull(tokens);this.throttle=Objects.requireNonNull(throttle);
        char[] dummy=("unused-account-"+UUID.randomUUID()).toCharArray();
        try { dummyHash=passwords.hash(dummy); } finally { Arrays.fill(dummy,'\0'); }
    }
    public Attempt authenticate(String email,char[] password,String clientIp,SessionStore.ProofBinding binding) {
        List<AdminMfaUnitOfWork.RecoveryCode> prepared=new ArrayList<>();
        try {
            String normalized=Input.email(email);Objects.requireNonNull(binding);
            var admission=throttle.accountAttempt(normalized,clientIp);
            if(!admission.allowed())return new Attempt(Optional.empty(),admission.retryAfterSeconds());
            try {
                try { PrivateAdminBootstrap.validatePassword(password); }
                catch(DomainFailure invalid) { return new Attempt(Optional.empty(),0); }
                var proof=store.locked(normalized,account->{
                    var credential=account.credential();
                    boolean verified=passwords.verify(password,credential.map(Credential::passwordHash).orElse(dummyHash));
                    if(!verified || credential.isEmpty())return Optional.<AuthenticationProof>empty();
                    var token=tokens.recoveryCode();prepared.add(token);
                    return Optional.of(new AuthenticationProof(token,account.issueProof(token.hash(),binding)));
                });
                if(proof.isPresent())admission.attempt().succeeded();
                return new Attempt(proof,0);
            } finally { admission.attempt().failed(); }
        } catch(RuntimeException failure) { prepared.forEach(AdminMfaUnitOfWork.RecoveryCode::close);throw failure; }
        finally { if(password!=null)Arrays.fill(password,'\0'); }
    }
}
