package com.purpuragenda.application;

import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.AccountAuthenticationUnitOfWork.*;
import com.purpuragenda.application.port.out.AdminMfaUnitOfWork.RecoveryCode;
import com.purpuragenda.application.port.out.RateLimitStore.*;
import com.purpuragenda.application.port.out.SessionStore.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccountLoginTest {
    final Instant now=Instant.parse("2026-10-26T10:00:00Z");final SessionReference previous=new SessionReference(UUID.randomUUID(),"b".repeat(64));
    final List<RecoveryCode> codes=new ArrayList<>();ProofBinding binding;char[] exchanged;Issued issued;
    boolean verified=true,exchangeAllowed=true,exchangeFails;long wait;int exchanges;
    final AdminFactorCrypto crypto=new AdminFactorCrypto() {
        public long acceptedStep(byte[] secret,String code,Instant time,long step) { throw new AssertionError("ACCOUNT_HAS_NO_TOTP"); }
        public RecoveryCode recoveryCode() { var value=new RecoveryCode("r".repeat(43).toCharArray(),"%064x".formatted(codes.size()+1));codes.add(value);return value; }
    };
    final PasswordHash passwords=new PasswordHash() { public String hash(char[] password) { return "synthetic-encoded"; }public boolean verify(char[] password,String encoded) { return verified; } };
    final RateLimitStore rate=new RateLimitStore() {
        public Decision admit(List<Charge> charges) { return wait>0?Decision.deny(wait):Decision.allow(List.of(new Reservation(RateLimitStore.Kind.ACCOUNT_ATTEMPT,"a".repeat(64),now))); }
        public boolean release(Reservation value) { return true; }
    };
    final AccountAuthenticationUnitOfWork factors=new AccountAuthenticationUnitOfWork() {
        public <T>T locked(String email,Function<Account,T> work) {
            return work.apply(new Account() {
                public Instant now() { return now; }
                public Optional<Credential> credential() { return Optional.of(new Credential(UUID.randomUUID(),UUID.randomUUID(),"synthetic-encoded",1)); }
                public Instant issueProof(String hash,ProofBinding supplied) { binding=supplied;return now.plusSeconds(60); }
            });
        }
    };
    final SessionStore sessions=new SessionStore() {
        public Issued anonymous() { throw new AssertionError(); }
        public Optional<CsrfRotation> rotateCsrf(char[] token) { throw new AssertionError(); }
        public Optional<Snapshot> resolve(char[] token) { throw new AssertionError(); }
        public Optional<Snapshot> inspect(char[] token) { throw new AssertionError(); }
        public boolean csrf(SessionReference session,char[] proof) { throw new AssertionError(); }
        public Optional<Issued> exchangeAdmin(SessionReference session,String request,char[] proof) { throw new AssertionError("ADMIN_SESSION_UNEXPECTED"); }
        public Optional<Issued> exchangeGuest(SessionReference session,GuestAccessStore.Redemption proof) { throw new AssertionError(); }
        public boolean revoke(SessionReference session) { throw new AssertionError(); }
        public Optional<Issued> exchangeAccount(SessionReference session,String request,char[] proof) {
            exchanges++;exchanged=proof;
            try {
                assertSame(previous,session);assertTrue(binding.requestHash().equals(request),"EXPECTED_REQUEST_BINDING");assertTrue(previous.browserHash().equals(binding.browserHash()),"EXPECTED_BROWSER_BINDING");
                assertTrue(Arrays.equals(codes.getLast().value(),proof),"EXPECTED_TRANSACTION_PROOF");
                if(exchangeFails)throw new IllegalStateException("SYNTHETIC_EXCHANGE_FAILED");if(!exchangeAllowed)return Optional.empty();
                issued=new Issued(new Snapshot(new SessionReference(UUID.randomUUID(),previous.browserHash()),SessionStore.Kind.ACCOUNT,null,UUID.randomUUID(),UUID.randomUUID(),null,null,now,now,now.plusSeconds(43200),now),"t".repeat(43).toCharArray(),"c".repeat(43).toCharArray());
                return Optional.of(issued);
            } finally { Arrays.fill(proof,'\0'); }
        }
    };
    AccountLogin command() { return new AccountLogin(new AccountAuthentication(factors,passwords,crypto,new RequestThrottle(rate)),sessions,crypto); }
    char[] password() { return "synthetic-account-password".toCharArray(); }
    void clear(char[] value) { assertTrue(Arrays.equals(new char[value.length],value),"SENSITIVE_BUFFER_NOT_CLEARED"); }
    AccountLogin.Outcome authenticate(char[] password) { return command().authenticate(previous,"person@example.test",password,"203.0.113.8"); }
    @Test void onlyTheInternalBoundProofCreatesARotatedAccountSessionAndAllBorrowedSecretsAreCleared() {
        char[] borrowed=password();
        try(var result=(AccountLogin.Authenticated)authenticate(borrowed)) {
            assertEquals(SessionStore.Kind.ACCOUNT,result.session().view().role());assertEquals(now.plusSeconds(1800),result.session().view().idleExpiresAt());
            assertEquals("AccountAuthentication[REDACTED]",result.toString());assertTrue(Arrays.equals("t".repeat(43).toCharArray(),result.session().cookie()));
            clear(borrowed);clear(exchanged);codes.forEach(code->clear(code.value()));clear(issued.token());clear(issued.csrf());
            result.close();clear(result.session().cookie());clear(result.session().csrf());
        }
    }
    @Test void rejectedFactorsAndStaleExchangeNeverProduceSessionAuthority() {
        verified=false;assertSame(AccountLogin.Rejected.INSTANCE,authenticate(password()));assertEquals(0,exchanges);
        verified=true;exchangeAllowed=false;assertSame(AccountLogin.Rejected.INSTANCE,authenticate(password()));assertEquals(1,exchanges);clear(exchanged);
    }
    @Test void capacityDenialDoesNoExchangeAndReturnsTheExactWait() {
        wait=47;char[] borrowed=password();assertEquals(new AccountLogin.Throttled(47),authenticate(borrowed));clear(borrowed);assertEquals(0,exchanges);
        codes.forEach(code->clear(code.value()));
    }
    @Test void sessionExchangeFailureClearsThePasswordRequestAndFactorProofWithoutReturningBearerMaterial() {
        exchangeFails=true;char[] borrowed=password();assertThrows(IllegalStateException.class,()->authenticate(borrowed));clear(borrowed);clear(exchanged);codes.forEach(code->clear(code.value()));assertNull(issued);
    }
    @Test void invalidInputsAndDependenciesFailSafelyAndConsumeBorrowedSecrets() {
        var command=command();char[] borrowed=password();assertThrows(NullPointerException.class,()->command.authenticate(null,"person@example.test",borrowed,"203.0.113.8"));clear(borrowed);
        assertSame(AccountLogin.Rejected.INSTANCE,authenticate(null));
        assertThrows(NullPointerException.class,()->new AccountLogin(null,sessions,crypto));assertThrows(NullPointerException.class,()->new AccountLogin(new AccountAuthentication(factors,passwords,crypto,new RequestThrottle(rate)),null,crypto));
        assertThrows(NullPointerException.class,()->new AccountLogin(new AccountAuthentication(factors,passwords,crypto,new RequestThrottle(rate)),sessions,null));
        assertThrows(IllegalArgumentException.class,()->new AccountLogin.Throttled(0));assertThrows(NullPointerException.class,()->new AccountLogin.Authenticated(null));
    }
}
