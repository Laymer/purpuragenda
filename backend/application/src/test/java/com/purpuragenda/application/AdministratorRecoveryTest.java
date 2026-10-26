package com.purpuragenda.application;

import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.RateLimitStore.*;
import com.purpuragenda.application.port.out.SessionStore.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdministratorRecoveryTest {
    final PrivateAdminRecoveryTest core=new PrivateAdminRecoveryTest();
    final List<Charge> charges=new ArrayList<>();boolean deny,releaseFails,bindingFails;int release,bindings;
    final RateLimitStore rate=new RateLimitStore() {
        public Decision admit(List<Charge> value) { charges.addAll(value);return deny?Decision.deny(19):Decision.allow(List.of(new Reservation(RateLimitStore.Kind.ADMIN_ATTEMPT,"a".repeat(64),Instant.EPOCH))); }
        public boolean release(Reservation value) { release++;if(releaseFails)throw new IllegalStateException("SYNTHETIC_RELEASE_FAILED");return true; }
    };
    final AdministratorRecovery recovery=new AdministratorRecovery(core.recovery,new RequestThrottle(rate),session->{
        bindings++;assertFalse(charges.isEmpty());if(bindingFails)throw new IllegalStateException("SYNTHETIC_BINDING_FAILED");return core.binding;
    });
    Snapshot session(SessionStore.Kind kind) { return new Snapshot(new SessionReference(UUID.randomUUID(),"b".repeat(64)),kind,null,null,null,null,null,Instant.EPOCH,Instant.EPOCH,Instant.EPOCH.plusSeconds(600),null); }
    @Test void admissionPrecedesRestrictedFactorsAndOnlyCommittedSuccessReleasesCapacity() {
        char[] password=core.password(),code=core.token();
        try(var result=recovery.begin(session(SessionStore.Kind.ANONYMOUS),"Owner@Example.test",password,code,"203.0.113.4")) {
            assertTrue(result.value().isPresent());assertEquals(0,result.retryAfterSeconds());assertEquals(1,bindings);assertEquals(1,release);
            assertEquals(Instant.EPOCH.plusSeconds(600),result.value().orElseThrow().expiresAt());
            assertEquals("AdministratorRecoveryAttempt[REDACTED]",result.toString());
        }
        core.cleared(password);core.cleared(code);core.erasedSecrets();
        try(var result=recovery.reenroll(session(SessionStore.Kind.ANONYMOUS),"owner@example.test",core.password(),core.token(),"123456","203.0.113.4")) {
            assertEquals(10,result.value().orElseThrow().codes().size());assertEquals(2,release);assertEquals(1,core.writes);
        }
    }
    @Test void deniedAndAuthenticatedSessionsCannotReachBindingOrFactorStoreAndEraseInputs() {
        for(var kind:List.of(SessionStore.Kind.ADMIN,SessionStore.Kind.GUEST,SessionStore.Kind.ACCOUNT)) {
            char[] password=core.password(),code=core.token();
            try(var result=recovery.begin(session(kind),"owner@example.test",password,code,"203.0.113.4")) { assertTrue(result.value().isEmpty()); }
            core.cleared(password);core.cleared(code);
        }
        assertTrue(charges.isEmpty());deny=true;char[] password=core.password(),code=core.token();
        try(var result=recovery.begin(session(SessionStore.Kind.ANONYMOUS),"owner@example.test",password,code,"203.0.113.4")) { assertEquals(19,result.retryAfterSeconds()); }
        core.cleared(password);core.cleared(code);assertEquals(0,bindings);assertEquals(0,release);assertTrue(core.issued.isEmpty());
    }
    @Test void rejectedFactorsAndStorageFailureKeepTheChargeAndEraseInputs() {
        core.authorized=false;
        try(var result=recovery.begin(session(SessionStore.Kind.ANONYMOUS),"owner@example.test",core.password(),core.token(),"203.0.113.4")) { assertTrue(result.value().isEmpty()); }
        assertEquals(0,release);bindingFails=true;char[] password=core.password(),code=core.token();
        assertThrows(IllegalStateException.class,()->recovery.begin(session(SessionStore.Kind.ANONYMOUS),"owner@example.test",password,code,"203.0.113.4"));
        core.cleared(password);core.cleared(code);assertEquals(0,release);
    }
    @Test void uncertainReleaseRetainsSuccessfulRestrictedResultAndInputsAreAlwaysWiped() {
        releaseFails=true;
        try(var result=recovery.begin(session(SessionStore.Kind.ANONYMOUS),"owner@example.test",core.password(),core.token(),"203.0.113.4")) { assertTrue(result.value().isPresent()); }
        char[] password=core.password();
        assertThrows(NullPointerException.class,()->recovery.begin(null,"owner@example.test",password,null,"203.0.113.4"));core.cleared(password);
        assertThrows(IllegalArgumentException.class,()->new AdministratorRecovery.Attempt<>(Optional.empty(),-1));
        assertThrows(IllegalArgumentException.class,()->new AdministratorRecovery.Attempt<>(Optional.of(()->{}),1));
        assertThrows(NullPointerException.class,()->new AdministratorRecovery.Attempt<>(null,0));
        try(var empty=new AdministratorRecovery.Attempt<AutoCloseable>(Optional.empty(),0)) { assertEquals(0,empty.retryAfterSeconds()); }
        var broken=new AdministratorRecovery.Attempt<AutoCloseable>(Optional.of(()->{throw new Exception("SYNTHETIC");}),0);
        assertEquals("RECOVERY_CLEANUP_FAILED",assertThrows(IllegalStateException.class,broken::close).getMessage());
        assertThrows(NullPointerException.class,()->new AdministratorRecovery(null,new RequestThrottle(rate),s->core.binding));
        assertThrows(NullPointerException.class,()->new AdministratorRecovery(core.recovery,null,s->core.binding));
        assertThrows(NullPointerException.class,()->new AdministratorRecovery(core.recovery,new RequestThrottle(rate),null));
    }
}
