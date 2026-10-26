package com.purpuragenda.application;

import com.purpuragenda.application.port.out.*;
import java.util.*;

/** Only a consumed persisted password proof creates an ACCOUNT session; credentials and proof stay server-internal. */
public final class AccountLogin {
    public sealed interface Outcome permits Authenticated,Rejected,Throttled { }
    public enum Rejected implements Outcome { INSTANCE }
    public record Throttled(long retryAfterSeconds) implements Outcome {
        public Throttled { if(retryAfterSeconds<1)throw new IllegalArgumentException("INVALID_RETRY_AFTER"); }
    }
    public record Authenticated(SessionAccess.Started session) implements Outcome,AutoCloseable {
        public Authenticated { Objects.requireNonNull(session); }
        @Override public void close() { session.close(); }
        @Override public String toString() { return "AccountAuthentication[REDACTED]"; }
    }
    private final AccountAuthentication factors;private final SessionStore sessions;private final AdminFactorCrypto crypto;
    public AccountLogin(AccountAuthentication factors,SessionStore sessions,AdminFactorCrypto crypto) {
        this.factors=Objects.requireNonNull(factors);this.sessions=Objects.requireNonNull(sessions);this.crypto=Objects.requireNonNull(crypto);
    }
    public Outcome authenticate(SessionStore.SessionReference previous,String email,char[] password,String clientAddress) {
        try {
            Objects.requireNonNull(previous);
            try(var request=crypto.recoveryCode();var proof=factors.authenticate(email,password,clientAddress,new SessionStore.ProofBinding(previous.browserHash(),request.hash()))) {
                if(proof.retryAfterSeconds()>0)return new Throttled(proof.retryAfterSeconds());
                if(proof.proof().isEmpty())return Rejected.INSTANCE;
                var exchanged=sessions.exchangeAccount(previous,request.hash(),proof.proof().get().token().value());
                if(exchanged.isEmpty())return Rejected.INSTANCE;
                try(var issued=exchanged.get()) {
                    char[] bearer=issued.token(),csrf=issued.csrf();
                    try { return new Authenticated(new SessionAccess.Started(SessionAccess.view(issued.session()),bearer,csrf)); }
                    finally { Arrays.fill(bearer,'\0');Arrays.fill(csrf,'\0'); }
                }
            }
        } finally { if(password!=null)Arrays.fill(password,'\0'); }
    }
}
