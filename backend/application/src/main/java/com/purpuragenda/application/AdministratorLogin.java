package com.purpuragenda.application;

import com.purpuragenda.application.port.out.*;
import com.purpuragenda.domain.customer.Input;
import java.util.*;
import static com.purpuragenda.domain.DomainFailure.require;

/** Credential attempts reserve distributed capacity before factor work. Only a consumed persisted proof authorizes. */
public final class AdministratorLogin {
    public sealed interface Outcome permits Authenticated, Rejected, Throttled { }
    public enum Rejected implements Outcome { INSTANCE }
    public record Throttled(long retryAfterSeconds) implements Outcome {
        public Throttled { if(retryAfterSeconds<1)throw new IllegalArgumentException("INVALID_RETRY_AFTER"); }
    }
    public record Authenticated(SessionAccess.Started session) implements Outcome, AutoCloseable {
        public Authenticated { Objects.requireNonNull(session); }
        @Override public void close() { session.close(); }
        @Override public String toString() { return "AdministratorAuthentication[REDACTED]"; }
    }

    private final RequestThrottle throttle;
    private final PrivateAdminMfa factors;
    private final SessionStore sessions;
    private final AdminFactorCrypto crypto;
    public AdministratorLogin(RequestThrottle throttle,PrivateAdminMfa factors,SessionStore sessions,AdminFactorCrypto crypto) {
        this.throttle=Objects.requireNonNull(throttle);this.factors=Objects.requireNonNull(factors);
        this.sessions=Objects.requireNonNull(sessions);this.crypto=Objects.requireNonNull(crypto);
    }

    /** Consumes password on every path. The request binding and factor proof remain entirely server-internal. */
    public Outcome authenticate(SessionStore.SessionReference previous,String email,char[] password,String totp,String clientAddress) {
        try {
            Objects.requireNonNull(previous);
            String account=Input.email(email);
            var admission=throttle.adminAttempt(account,clientAddress);
            if(!admission.allowed())return new Throttled(admission.retryAfterSeconds());
            boolean authenticated=false;
            try {
                require(totp!=null && totp.matches("[0-9]{6}"),"INVALID_ADMIN_CREDENTIALS");
                // A separate random hash binds this exact factor transaction to its one session exchange.
                try(var request=crypto.recoveryCode()) {
                    var proof=factors.authenticateForSession(account,password,totp,
                            new SessionStore.ProofBinding(previous.browserHash(),request.hash()));
                    if(proof.isEmpty())return Rejected.INSTANCE;
                    try(var value=proof.get()) {
                        var issued=sessions.exchangeAdmin(previous,request.hash(),value.token().value());
                        if(issued.isEmpty())return Rejected.INSTANCE;
                        try(var session=issued.get()) {
                            char[] bearer=session.token(),csrf=session.csrf();
                            try {
                                var result=new Authenticated(new SessionAccess.Started(SessionAccess.view(session.session()),bearer,csrf));
                                authenticated=true;
                                return result;
                            } finally { Arrays.fill(bearer,'\0');Arrays.fill(csrf,'\0'); }
                        }
                    }
                }
            } finally {
                if(authenticated) {
                    try { admission.attempt().succeeded(); }
                    catch(RuntimeException conservative) { /* Keep the charged slot if release is unavailable or uncertain. */ }
                } else admission.attempt().failed();
            }
        } finally { if(password!=null)Arrays.fill(password,'\0'); }
    }
}
