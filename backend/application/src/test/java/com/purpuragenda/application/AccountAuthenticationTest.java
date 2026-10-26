package com.purpuragenda.application;

import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.AccountAuthenticationUnitOfWork.*;
import com.purpuragenda.application.port.out.AdminMfaUnitOfWork.RecoveryCode;
import com.purpuragenda.application.port.out.RateLimitStore.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccountAuthenticationTest {
    final Instant now=Instant.parse("2026-10-26T10:00:00Z");
    final SessionStore.ProofBinding binding=new SessionStore.ProofBinding("b".repeat(64),"c".repeat(64));
    final List<Charge> charges=new ArrayList<>();final List<String> events=new ArrayList<>();final List<RecoveryCode> issued=new ArrayList<>();
    Credential credential=new Credential(UUID.randomUUID(),UUID.randomUUID(),"synthetic-hash",1);
    Decision decision=Decision.allow(List.of(new Reservation(Kind.ACCOUNT_ATTEMPT,"a".repeat(64),now)));
    int verifies,proofs,releases;boolean verified=true,commitFails,releaseFails;
    String lookedUp,verifiedHash;
    final Account context=new Account() {
        public Instant now() { return now; }
        public Optional<Credential> credential() { return Optional.ofNullable(credential); }
        public Instant issueProof(String hash,SessionStore.ProofBinding supplied) { proofs++;assertSame(binding,supplied);return now.plusSeconds(60); }
    };
    final AccountAuthenticationUnitOfWork store=new AccountAuthenticationUnitOfWork() {
        public <T>T locked(String email,Function<Account,T> work) {
            events.add("locked");lookedUp=email;T result=work.apply(context);
            if(commitFails)throw new IllegalStateException("SYNTHETIC_COMMIT_FAILED");return result;
        }
    };
    final RateLimitStore limits=new RateLimitStore() {
        public Decision admit(List<Charge> requested) { events.add("throttle");charges.addAll(requested);return decision; }
        public boolean release(Reservation reservation) { releases++;if(releaseFails)throw new IllegalStateException("SYNTHETIC_RELEASE_FAILED");return true; }
    };
    final PasswordHash passwords=new PasswordHash() {
        public String hash(char[] secret) { return "synthetic-dummy"; }
        public boolean verify(char[] secret,String hash) { events.add("verify");verifies++;verifiedHash=hash;return verified; }
    };
    final AdminFactorCrypto tokens=new AdminFactorCrypto() {
        public long acceptedStep(byte[] secret,String code,Instant time,long previous) { throw new AssertionError("ACCOUNT_HAS_NO_TOTP"); }
        public RecoveryCode recoveryCode() { var token=new RecoveryCode("A".repeat(43).toCharArray(),"d".repeat(64));issued.add(token);return token; }
    };
    AccountAuthentication command() { return new AccountAuthentication(store,passwords,tokens,new RequestThrottle(limits)); }
    char[] password() { return "synthetic-account-password".toCharArray(); }
    void cleared(char[] secret) { assertTrue(Arrays.equals(new char[secret.length],secret),"SENSITIVE_BUFFER_NOT_CLEARED"); }

    @Test void canonicalIdentityIsChargedBeforeLookupAndPasswordWorkAndProofIsCloseable() {
        char[] secret=password();
        try(var attempt=command().authenticate("Person@BÜCHER.example",secret,"203.0.113.9",binding)) {
            assertEquals("person@xn--bcher-kva.example",lookedUp);
            assertEquals("person@xn--bcher-kva.example\n203.0.113.9/32",charges.getFirst().subject());
            assertEquals(Kind.ACCOUNT_ATTEMPT,charges.getFirst().kind());assertEquals(List.of("throttle","locked","verify"),events);
            assertEquals(1,verifies);assertEquals(1,proofs);assertEquals(1,releases);assertEquals(0,attempt.retryAfterSeconds());
            assertEquals(now.plusSeconds(60),attempt.proof().orElseThrow().expiresAt());
            assertEquals("AccountCredential[REDACTED]",credential.toString());assertEquals("AccountAuthenticationProof[REDACTED]",attempt.proof().get().toString());
            assertEquals("AccountAuthenticationAttempt[REDACTED]",attempt.toString());cleared(secret);
        }
        cleared(issued.getFirst().value());
    }
    @Test void unknownAndWrongPasswordKeepReservedAttemptsAndNeverIssueProofs() {
        var command=command();credential=null;
        try(var attempt=command.authenticate("unknown@example.test",password(),"203.0.113.9",binding)) { assertTrue(attempt.proof().isEmpty());assertEquals(0,attempt.retryAfterSeconds()); }
        assertEquals("synthetic-dummy",verifiedHash);assertEquals(1,verifies);assertEquals(0,proofs);
        credential=new Credential(UUID.randomUUID(),UUID.randomUUID(),"synthetic-hash",1);verified=false;
        try(var attempt=command.authenticate("known@example.test",password(),"203.0.113.9",binding)) { assertTrue(attempt.proof().isEmpty()); }
        assertEquals(2,verifies);assertEquals(0,proofs);assertEquals(0,releases);assertTrue(issued.isEmpty());
    }
    @Test void throttlesAndInvalidPasswordsDoNoPasswordOrProofWorkAndConsumeInputBuffers() {
        var command=command();decision=Decision.deny(77);char[] secret=password();
        try(var denied=command.authenticate("known@example.test",secret,"203.0.113.9",binding)) { assertTrue(denied.proof().isEmpty());assertEquals(77,denied.retryAfterSeconds()); }
        cleared(secret);assertEquals(List.of("throttle"),events);
        decision=Decision.allow(List.of(new Reservation(Kind.ACCOUNT_ATTEMPT,"a".repeat(64),now)));
        for(char[] invalid:new char[][]{null,"short".toCharArray()}) {
            try(var rejected=command.authenticate("known@example.test",invalid,"203.0.113.9",binding)) { assertTrue(rejected.proof().isEmpty()); }
            if(invalid!=null)cleared(invalid);
        }
        assertEquals(0,verifies);assertEquals(0,proofs);assertEquals(0,releases);
    }
    @Test void commitOrReleaseFailureClearsUnreturnedProofsAndPasswords() {
        var command=command();
        for(boolean release:new boolean[]{false,true}) {
            commitFails=!release;releaseFails=release;char[] secret=password();
            assertThrows(IllegalStateException.class,()->command.authenticate("known@example.test",secret,"203.0.113.9",binding));
            cleared(secret);cleared(issued.getLast().value());
        }
    }
    @Test void malformedIdentityBindingAndDependenciesFailWithoutExposingOrRetainingSecrets() {
        var command=command();char[] secret=password(),borrowed=password();
        assertThrows(RuntimeException.class,()->command.authenticate("not-email",secret,"203.0.113.9",binding));cleared(secret);
        assertThrows(NullPointerException.class,()->command.authenticate("known@example.test",borrowed,"203.0.113.9",null));cleared(borrowed);
        assertThrows(NullPointerException.class,()->new AccountAuthentication(null,passwords,tokens,new RequestThrottle(limits)));
        assertThrows(NullPointerException.class,()->new AccountAuthentication(store,null,tokens,new RequestThrottle(limits)));
        assertThrows(NullPointerException.class,()->new AccountAuthentication(store,passwords,null,new RequestThrottle(limits)));
        assertThrows(NullPointerException.class,()->new AccountAuthentication(store,passwords,tokens,null));
        assertThrows(IllegalArgumentException.class,()->new AccountAuthentication.Attempt(Optional.empty(),-1));
        assertThrows(NullPointerException.class,()->new AccountAuthentication.Attempt(null,0));
        assertThrows(NullPointerException.class,()->new AuthenticationProof(null,now));
    }
}
