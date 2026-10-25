package com.purpuragenda.application;

import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.domain.DomainFailure;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchedulingValuesTest {
    final UUID actor=UUID.randomUUID(), target=UUID.randomUUID();
    @Test void scopesCannotCollideAcrossActorOperationOrTarget() {
        Scope scope=new Scope(actor,"CREATE",null);
        assertEquals(actor+"/CREATE/-",scope.storageKey());
        assertNotEquals(scope.storageKey(),new Scope(actor,"CREATE",target).storageKey());
        assertNotEquals(scope.storageKey(),new Scope(UUID.randomUUID(),"CREATE",null).storageKey());
        assertNotEquals(scope.storageKey(),new Scope(actor,"CANCEL",null).storageKey());
        for(String operation:Arrays.asList(null,"","create","CREATE/OTHER","A".repeat(65)))
            assertThrows(DomainFailure.class,()->new Scope(actor,operation,null));
        assertThrows(DomainFailure.class,()->new Scope(null,"CREATE",null));
    }
    @Test void lengthPrefixedFingerprintsAreCanonicalAndUnambiguous() {
        assertEquals(CommandKey.fingerprint(List.of("é","x")),CommandKey.fingerprint(List.of("é","x")));
        assertNotEquals(CommandKey.fingerprint(List.of("ab","c")),CommandKey.fingerprint(List.of("a","bc")));
        assertNotEquals(CommandKey.fingerprint(List.of("","a")),CommandKey.fingerprint(List.of("a")));
        assertTrue(CommandKey.fingerprint(List.of()).matches("[0-9a-f]{64}"));
        assertThrows(DomainFailure.class,()->CommandKey.fingerprint(null));
        assertThrows(DomainFailure.class,()->CommandKey.fingerprint(Arrays.asList("x",null)));
    }
    @Test void invalidIdempotencyMaterialIsRejectedWithoutEchoingIt() {
        Scope scope=new Scope(actor,"CREATE",target);
        String fingerprint=CommandKey.fingerprint(List.of("synthetic"));
        CommandKey key=new CommandKey(scope,"a".repeat(120),fingerprint);
        assertEquals(scope,key.scope()); assertEquals(120,key.key().length()); assertEquals(fingerprint,key.fingerprint());
        for(String invalid:Arrays.asList(null,""," ","a".repeat(121),"synthetic\n"))
            assertEquals("INVALID_IDEMPOTENCY_KEY",assertThrows(DomainFailure.class,()->new CommandKey(scope,invalid,fingerprint)).code());
        assertThrows(DomainFailure.class,()->new CommandKey(null,"key",fingerprint));
        for(String invalid:Arrays.asList(null,"","A".repeat(64),"g".repeat(64)))
            assertEquals("INVALID_FINGERPRINT",assertThrows(DomainFailure.class,()->new CommandKey(scope,"key",invalid)).code());
    }
    @Test void receiptsHaveClosedSafeCodesAndConsistentVersions() {
        assertTrue(new Receipt(Code.OK,target,1).success());
        assertTrue(new Receipt(Code.OK,null,0).success());
        assertFalse(Receipt.failure(Code.SLOT_UNAVAILABLE).success());
        assertEquals(Code.IDEMPOTENCY_KEY_REUSED,Receipt.failure(Code.IDEMPOTENCY_KEY_REUSED).code());
        assertThrows(DomainFailure.class,()->new Receipt(null,null,0));
        assertThrows(DomainFailure.class,()->new Receipt(Code.OK,target,-1));
        assertThrows(DomainFailure.class,()->new Receipt(Code.OK,target,0));
        assertThrows(DomainFailure.class,()->new Receipt(Code.OK,null,1));
        assertThrows(DomainFailure.class,()->new Receipt(Code.SLOT_UNAVAILABLE,target,0));
        assertThrows(DomainFailure.class,()->new Receipt(Code.SLOT_UNAVAILABLE,null,1));
        assertThrows(DomainFailure.class,()->Receipt.failure(Code.OK));
        Failure failure=new Failure(Failure.Kind.RETRY_LATER);
        assertEquals(Failure.Kind.RETRY_LATER,failure.kind()); assertEquals("RETRY_LATER",failure.getMessage()); assertNull(failure.getCause());
        assertThrows(NullPointerException.class,()->new Failure(null));
    }
}
