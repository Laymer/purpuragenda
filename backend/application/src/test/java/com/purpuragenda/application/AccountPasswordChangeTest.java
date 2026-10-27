package com.purpuragenda.application;

import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.AccountAuthenticationUnitOfWork.Credential;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.RateLimitStore.*;
import com.purpuragenda.domain.DomainFailure;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccountPasswordChangeTest {
    final List<String> events=new ArrayList<>();final List<Charge> charges=new ArrayList<>();
    final SessionBinding session=new SessionBinding(UUID.randomUUID(),"b".repeat(64));
    Credential credential=new Credential(UUID.randomUUID(),UUID.randomUUID(),"synthetic-old-hash",1);
    Decision admission=Decision.allow(List.of(new Reservation(Kind.ACCOUNT_ATTEMPT,"a".repeat(64),Instant.EPOCH)));
    boolean verified=true,commitFails,releaseFails;int releases,replacements;
    final AccountPasswordUnitOfWork.Account context=new AccountPasswordUnitOfWork.Account() {
        public Optional<Credential> credential() { return Optional.ofNullable(credential); }
        public void replacePassword(String encoded) { assertTrue(encoded.equals("synthetic-new-hash"));events.add("replace");replacements++; }
    };
    final AccountPasswordUnitOfWork store=new AccountPasswordUnitOfWork() {
        public <T>T locked(String email,SessionBinding supplied,Function<Account,T> work) {
            assertEquals("person@xn--bcher-kva.example",email);assertSame(session,supplied);events.add("locked");
            T result=work.apply(context);if(commitFails)throw new IllegalStateException("SYNTHETIC_COMMIT_FAILED");return result;
        }
    };
    final PasswordHash passwords=new PasswordHash() {
        public String hash(char[] input) { events.add("hash");return "synthetic-new-hash"; }
        public boolean verify(char[] input,String encoded) { events.add("verify");assertTrue(encoded.equals("synthetic-old-hash"));return verified; }
    };
    final RateLimitStore limits=new RateLimitStore() {
        public Decision admit(List<Charge> requested) { events.add("throttle");charges.addAll(requested);return admission; }
        public boolean release(Reservation reservation) { releases++;if(releaseFails)throw new IllegalStateException("SYNTHETIC_RELEASE_FAILED");return true; }
    };
    AccountPasswordChange command() { return new AccountPasswordChange(store,passwords,new RequestThrottle(limits)); }
    char[] password() { return "synthetic-account-password".toCharArray(); }
    AccountPasswordChange.Result change(char[] previous,char[] replacement) { return command().change("Person@BÜCHER.example",session,previous,replacement,"203.0.113.8"); }
    void clear(char[] input) { assertTrue(Arrays.equals(new char[input.length],input),"SENSITIVE_BUFFER_NOT_CLEARED"); }
    @Test void canonicalThrottlePrecedesHashAndOldPasswordVerificationHappensInsideLockedContext() {
        char[] previous=password(),replacement=password();assertTrue(change(previous,replacement).changed());clear(previous);clear(replacement);
        assertEquals(List.of("throttle","hash","locked","verify","replace"),events);assertEquals(1,replacements);assertEquals(1,releases);
        assertEquals("person@xn--bcher-kva.example\n203.0.113.8/32",charges.getFirst().subject());assertEquals(Kind.ACCOUNT_ATTEMPT,charges.getFirst().kind());
    }
    @Test void absentSessionAuthorityAndWrongPasswordNeverChangeOrReleaseFailureBudget() {
        credential=null;assertFalse(change(password(),password()).changed());assertFalse(events.contains("verify"));
        credential=new Credential(UUID.randomUUID(),UUID.randomUUID(),"synthetic-old-hash",1);verified=false;
        assertFalse(change(password(),password()).changed());assertEquals(0,replacements);assertEquals(0,releases);
    }
    @Test void deniedAndInvalidPasswordsClearBuffersWithoutCryptoOrStorageWork() {
        admission=Decision.deny(61);char[] previous=password(),replacement=password();var denied=change(previous,replacement);
        assertFalse(denied.changed());assertEquals(61,denied.retryAfterSeconds());clear(previous);clear(replacement);assertEquals(List.of("throttle"),events);
        admission=Decision.allow(List.of(new Reservation(Kind.ACCOUNT_ATTEMPT,"a".repeat(64),Instant.EPOCH)));events.clear();
        for(boolean invalidOld:new boolean[]{false,true}) {
            char[] old=invalidOld?null:password(),next=invalidOld?password():"short".toCharArray();
            assertEquals("INVALID_ACCOUNT_PASSWORD",assertThrows(DomainFailure.class,()->change(old,next)).code());if(old!=null)clear(old);clear(next);
        }
        assertFalse(events.contains("hash"));assertFalse(events.contains("locked"));
    }
    @Test void commitFailurePropagatesButRateReleaseFailureCannotHideCommittedSignOut() {
        commitFails=true;char[] old=password(),next=password();assertThrows(IllegalStateException.class,()->change(old,next));clear(old);clear(next);assertEquals(0,releases);
        commitFails=false;releaseFails=true;assertTrue(change(password(),password()).changed());assertEquals(1,releases);
    }
    @Test void invalidArgumentsAndResultShapeRemainSafeAndClearBorrowedPasswords() {
        char[] old=password(),next=password();assertThrows(RuntimeException.class,()->command().change("invalid",session,old,next,"203.0.113.8"));clear(old);clear(next);
        assertThrows(NullPointerException.class,()->command().change("person@example.test",null,password(),password(),"203.0.113.8"));
        assertThrows(NullPointerException.class,()->new AccountPasswordChange(null,passwords,new RequestThrottle(limits)));
        assertThrows(NullPointerException.class,()->new AccountPasswordChange(store,null,new RequestThrottle(limits)));
        assertThrows(NullPointerException.class,()->new AccountPasswordChange(store,passwords,null));
        assertThrows(IllegalArgumentException.class,()->new AccountPasswordChange.Result(false,-1));assertThrows(IllegalArgumentException.class,()->new AccountPasswordChange.Result(true,1));
        assertEquals("AccountPasswordChangeResult[REDACTED]",new AccountPasswordChange.Result(false,0).toString());
    }
}
