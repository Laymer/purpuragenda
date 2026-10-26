package com.purpuragenda.application;

import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.AdminMfaUnitOfWork.*;
import com.purpuragenda.application.port.out.RateLimitStore.*;
import com.purpuragenda.application.port.out.RateLimitStore.Kind;
import com.purpuragenda.application.port.out.SessionStore.*;
import com.purpuragenda.domain.DomainFailure;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdministratorLoginTest {
    static final Instant NOW=Instant.parse("2026-10-26T10:00:00Z");
    static final SessionReference PREVIOUS=new SessionReference(UUID.randomUUID(),"b".repeat(64));
    static char[] password() { return "synthetic password phrase".toCharArray(); }
    static void cleared(char[] value) { assertTrue(value==null || Arrays.equals(value,new char[value.length]),"Borrowed secret must be erased"); }

    static final class Fixture {
        final List<String> events=new ArrayList<>();final List<Charge> charges=new ArrayList<>();
        final List<RecoveryCode> codes=new ArrayList<>();ProofBinding binding;
        boolean present=true,enrolled=true,factorFails,exchangeFails,exchangeAllowed=true,releaseFails;
        long accepted=6;Issued issued;char[] exchangeBuffer;String account="owner@example.test";
        Decision decision=Decision.allow(List.of(new Reservation(Kind.ADMIN_ATTEMPT,"a".repeat(64),NOW)));
        final RateLimitStore limits=new RateLimitStore() {
            public Decision admit(List<Charge> requested) { events.add("admit");charges.addAll(requested);return decision; }
            public boolean release(Reservation reservation) { events.add("release");if(releaseFails)throw new IllegalStateException("synthetic-private-detail");return true; }
        };
        final AdminFactorCrypto crypto=new AdminFactorCrypto() {
            public long acceptedStep(byte[] secret,String code,Instant captured,long previous) {
                return code.equals("123456") && previous<7?7:-1;
            }
            public RecoveryCode recoveryCode() {
                events.add("nonce");char[] value=new char[43];Arrays.fill(value,(char)('A'+codes.size()));
                var result=new RecoveryCode(value,String.format("%064x",codes.size()+1));codes.add(result);return result;
            }
        };
        final AdminMfaUnitOfWork store=new AdminMfaUnitOfWork() {
            public <T>T locked(String email,Function<Admin,T> work) {
                events.add("factor");assertEquals(account,email);
                if(factorFails)throw new IllegalStateException("synthetic-private-detail");
                return work.apply(new Admin() {
                    public Instant now() { return NOW; }
                    public Optional<Credential> credential() { return present?Optional.of(new Credential(UUID.randomUUID(),"synthetic-hash",enrolled,accepted,new Secret(new byte[20]))):Optional.empty(); }
                    public boolean initialEnrollment() { throw new AssertionError(); }
                    public void enroll(long step,List<String> hashes) { throw new AssertionError(); }
                    public void acceptStep(long step) { accepted=step;events.add("accepted-step"); }
                    public Instant issueAuthenticationProof(String hash,ProofBinding requested) {
                        events.add("persisted-proof");binding=requested;assertEquals(7,accepted);return NOW.plusSeconds(60);
                    }
                });
            }
        };
        final PasswordHash passwords=new PasswordHash() {
            public String hash(char[] value) { throw new AssertionError(); }
            public boolean verify(char[] value,String encoded) { return Arrays.equals(value,password()); }
        };
        final SessionStore sessions=new SessionStore() {
            public Issued anonymous() { throw new AssertionError(); }
            public Optional<CsrfRotation> rotateCsrf(char[] token) { throw new AssertionError(); }
            public Optional<Snapshot> resolve(char[] token) { throw new AssertionError(); }
            public Optional<Snapshot> inspect(char[] token) { throw new AssertionError(); }
            public boolean csrf(SessionReference session,char[] token) { throw new AssertionError(); }
            public Optional<Issued> exchangeGuest(SessionReference previous,GuestAccessStore.Redemption proof) { throw new AssertionError(); }
            public boolean revoke(SessionReference session) { throw new AssertionError(); }
            public Optional<Issued> exchangeAdmin(SessionReference previous,String requestHash,char[] proof) {
                events.add("exchange");exchangeBuffer=proof;
                try {
                    assertEquals(PREVIOUS,previous);assertEquals(binding.requestHash(),requestHash);
                    assertEquals(codes.getFirst().hash(),requestHash);
                    assertTrue(Arrays.equals(codes.getLast().value(),proof),"Expected the transaction-issued proof");
                    if(exchangeFails)throw new IllegalStateException("synthetic-private-detail");
                    if(!exchangeAllowed)return Optional.empty();
                    issued=new Issued(new Snapshot(new SessionReference(UUID.randomUUID(),previous.browserHash()),SessionStore.Kind.ADMIN,
                            null,null,null,UUID.randomUUID(),1L,NOW,NOW,NOW.plusSeconds(28800),NOW),"t".repeat(43).toCharArray(),"c".repeat(43).toCharArray());
                    return Optional.of(issued);
                } finally { Arrays.fill(proof,'\0'); }
            }
        };
        final PrivateAdminMfa factors=new PrivateAdminMfa(store,passwords,crypto);
        final AdministratorLogin login=new AdministratorLogin(new RequestThrottle(limits),factors,sessions,crypto);
        AdministratorLogin.Outcome authenticate(char[] borrowed,String code) { return login.authenticate(PREVIOUS,account,borrowed,code,"203.0.113.4"); }
    }

    @Test void reservesBeforeFactorWorkAndConsumesOnlyItsInternalBoundProofBeforeIssuingRotatedAuthority() {
        var f=new Fixture();char[] borrowed=password();
        try(var result=(AdministratorLogin.Authenticated)f.authenticate(borrowed,"123456")) {
            assertEquals(List.of("admit","nonce","factor","accepted-step","nonce","persisted-proof","exchange","release"),f.events);
            assertEquals(PREVIOUS.browserHash(),f.binding.browserHash());assertEquals(SessionStore.Kind.ADMIN,result.session().view().role());
            assertEquals(NOW.plusSeconds(900),result.session().view().idleExpiresAt());
            assertEquals("AdministratorAuthentication[REDACTED]",result.toString());
            assertTrue(Arrays.equals("t".repeat(43).toCharArray(),result.session().cookie()),"Expected issued cookie copy");
            cleared(borrowed);cleared(f.exchangeBuffer);cleared(f.issued.token());cleared(f.issued.csrf());
            f.codes.forEach(code->cleared(code.value()));result.close();cleared(result.session().cookie());cleared(result.session().csrf());
        }
    }
    @Test void canonicalIdnaMailboxIsSharedWithTheAttemptSubjectBeforeAnyFactorLookup() {
        var f=new Fixture();f.account="owner@xn--bcher-kva.example";f.present=false;
        for(String email:List.of("Owner@BÜCHER.example",f.account)) {
            char[] borrowed=password();assertSame(AdministratorLogin.Rejected.INSTANCE,f.login.authenticate(PREVIOUS,email,borrowed,"123456","203.0.113.4"));cleared(borrowed);
        }
        assertEquals(2,f.charges.size());
        f.charges.forEach(charge->{assertEquals(Kind.ADMIN_ATTEMPT,charge.kind());assertEquals(f.account+"\n203.0.113.4/32",charge.subject());});
        assertFalse(f.events.contains("exchange"));assertFalse(f.events.contains("release"));
    }
    @Test void deniedCapacityDoesNoFactorOrNonceWorkAndConsumesTheBorrowedPassword() {
        var f=new Fixture();f.decision=Decision.deny(37);char[] borrowed=password();
        assertEquals(new AdministratorLogin.Throttled(37),f.authenticate(borrowed,"123456"));cleared(borrowed);
        assertEquals(List.of("admit"),f.events);assertTrue(f.codes.isEmpty());
    }
    @Test void missingUnenrolledWrongPasswordAndRejectedOrReplayedTotpNeverExchangeAndKeepTheirAttempt() {
        for(String reason:List.of("missing","unenrolled","password","totp")) {
            var f=new Fixture();f.present=!reason.equals("missing");f.enrolled=!reason.equals("unenrolled");
            char[] borrowed=reason.equals("password")?"incorrect password phrase".toCharArray():password();
            assertSame(AdministratorLogin.Rejected.INSTANCE,f.authenticate(borrowed,reason.equals("totp")?"654321":"123456"));
            cleared(borrowed);assertFalse(f.events.contains("exchange"));assertFalse(f.events.contains("release"));
            f.codes.forEach(code->cleared(code.value()));
        }
        var f=new Fixture();try(var first=(AdministratorLogin.Authenticated)f.authenticate(password(),"123456")) { assertNotNull(first.session()); }
        int exchanges=Collections.frequency(f.events,"exchange");
        assertSame(AdministratorLogin.Rejected.INSTANCE,f.authenticate(password(),"123456"));assertEquals(exchanges,Collections.frequency(f.events,"exchange"));
    }
    @Test void failedSessionHandoffCannotAuthorizeFromTheAcceptedFactorAlone() {
        var f=new Fixture();f.exchangeAllowed=false;char[] borrowed=password();
        assertSame(AdministratorLogin.Rejected.INSTANCE,f.authenticate(borrowed,"123456"));
        assertTrue(f.events.contains("persisted-proof"));assertTrue(f.events.contains("exchange"));assertFalse(f.events.contains("release"));
        cleared(borrowed);cleared(f.exchangeBuffer);f.codes.forEach(code->cleared(code.value()));
    }
    @Test void providerFailuresKeepTheReservedAttemptAndEraseEveryUnreturnedSecret() {
        for(boolean factorFailure:new boolean[]{true,false}) {
            var f=new Fixture();f.factorFails=factorFailure;f.exchangeFails=!factorFailure;char[] borrowed=password();
            assertThrows(IllegalStateException.class,()->f.authenticate(borrowed,"123456"));
            cleared(borrowed);cleared(f.exchangeBuffer);f.codes.forEach(code->cleared(code.value()));assertFalse(f.events.contains("release"));
        }
    }
    @Test void failedBestEffortSlotReleasePreservesAlreadyAuthorizedSessionAndNeverReleasesTwice() {
        var f=new Fixture();f.releaseFails=true;
        try(var result=(AdministratorLogin.Authenticated)f.authenticate(password(),"123456")) { assertEquals(SessionStore.Kind.ADMIN,result.session().view().role()); }
        assertEquals(1,Collections.frequency(f.events,"release"));
    }
    @Test void invalidSubjectsCodesPasswordsAndInternalBindingsConsumeBuffersWithoutIssuingAuthority() {
        for(String code:Arrays.asList(null,"invalid")) {
            var f=new Fixture();char[] borrowed=password();assertThrows(DomainFailure.class,()->f.authenticate(borrowed,code));cleared(borrowed);
            assertEquals(List.of("admit"),f.events);
        }
        for(char[] borrowed:new char[][]{null,"short".toCharArray()}) {
            var f=new Fixture();assertThrows(DomainFailure.class,()->f.authenticate(borrowed,"123456"));cleared(borrowed);assertFalse(f.events.contains("factor"));
            f.codes.forEach(code->cleared(code.value()));
        }
        var f=new Fixture();
        char[] badEmail=password();assertThrows(DomainFailure.class,()->f.login.authenticate(PREVIOUS,"invalid",badEmail,"123456","203.0.113.4"));cleared(badEmail);
        char[] badIp=password();assertThrows(IllegalArgumentException.class,()->f.login.authenticate(PREVIOUS,f.account,badIp,"123456","invalid"));cleared(badIp);
        char[] absent=password();assertThrows(NullPointerException.class,()->f.login.authenticate(null,f.account,absent,"123456","203.0.113.4"));cleared(absent);
        assertTrue(f.events.isEmpty());
    }
    @Test void malformedConstructionFailsBeforeAnyCredentialWork() {
        var f=new Fixture();var throttle=new RequestThrottle(f.limits);
        assertThrows(NullPointerException.class,()->new AdministratorLogin(null,f.factors,f.sessions,f.crypto));
        assertThrows(NullPointerException.class,()->new AdministratorLogin(throttle,null,f.sessions,f.crypto));
        assertThrows(NullPointerException.class,()->new AdministratorLogin(throttle,f.factors,null,f.crypto));
        assertThrows(NullPointerException.class,()->new AdministratorLogin(throttle,f.factors,f.sessions,null));
        assertThrows(IllegalArgumentException.class,()->new AdministratorLogin.Throttled(0));
        assertThrows(NullPointerException.class,()->new AdministratorLogin.Authenticated(null));
        assertTrue(f.events.isEmpty());
    }
}
