package com.purpuragenda.application;

import com.purpuragenda.application.port.out.PasswordHash;
import com.purpuragenda.application.port.out.PrivateAdminStore;
import com.purpuragenda.domain.DomainFailure;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrivateAdminBootstrapTest {
    final AtomicInteger calls=new AtomicInteger();
    final PasswordHash hasher=new PasswordHash() {
        public String hash(char[] value) { calls.incrementAndGet(); return "synthetic-hash"; }
        public boolean verify(char[] value,String hash) { throw new AssertionError("NO_AUTHENTICATION"); }
    };
    void cleared(char[] value) { assertTrue(Arrays.equals(value,new char[value.length]),"PASSWORD_BUFFER_NOT_CLEARED"); }
    @Test void canonicalPrivateIdentityAndConsumedBufferReachOnlyUnenrolledStore() {
        char[] password="synthetic phrase with spaces".toCharArray();
        var bootstrap=new PrivateAdminBootstrap(hasher,(email,hash)->{
            assertTrue(email.equals("owner@example.test")); assertTrue(hash.equals("synthetic-hash"));
            return PrivateAdminStore.Result.CREATED;
        });
        assertEquals(PrivateAdminStore.Result.CREATED,bootstrap.create(" Owner@Example.test ",password));
        assertEquals(1,calls.get()); cleared(password);
    }
    @Test void existingAdminDoesNotBecomeAnAuthenticationResult() {
        char[] password="synthetic replacement phrase".toCharArray();
        var bootstrap=new PrivateAdminBootstrap(hasher,(email,hash)->PrivateAdminStore.Result.ALREADY_EXISTS);
        assertEquals(PrivateAdminStore.Result.ALREADY_EXISTS,bootstrap.create("owner@example.test",password)); cleared(password);
    }
    @Test void invalidEmailOrPasswordNeverWritesAndAlwaysClearsCallerBuffer() {
        var bootstrap=new PrivateAdminBootstrap(hasher,(email,hash)->{throw new AssertionError("UNEXPECTED_WRITE");});
        char[] password="synthetic phrase".toCharArray();
        assertThrows(DomainFailure.class,()->bootstrap.create("invalid",password)); cleared(password); assertEquals(0,calls.get());
        char[] shortPassword="short".toCharArray();
        assertThrows(DomainFailure.class,()->bootstrap.create("owner@example.test",shortPassword)); cleared(shortPassword);
        assertThrows(DomainFailure.class,()->bootstrap.create("owner@example.test",null));
    }
    @Test void failedHashOrPersistenceStillConsumesPassword() {
        char[] first="synthetic first phrase".toCharArray(), second="synthetic second phrase".toCharArray();
        var failHash=new PasswordHash() {
            public String hash(char[] value) { throw new IllegalStateException("HASH_UNAVAILABLE"); }
            public boolean verify(char[] value,String encoded) { return false; }
        };
        assertThrows(IllegalStateException.class,()->new PrivateAdminBootstrap(failHash,(email,hash)->PrivateAdminStore.Result.CREATED).create("owner@example.test",first));
        assertThrows(IllegalStateException.class,()->new PrivateAdminBootstrap(hasher,(email,hash)->{throw new IllegalStateException("STORE_UNAVAILABLE");}).create("owner@example.test",second));
        cleared(first); cleared(second);
    }
    @Test void passwordBoundsCountCodepointsWithoutNormalizationOrWhitespaceChanges() {
        for(String value:new String[]{" ".repeat(15),"a".repeat(128),"😀".repeat(15),"😀".repeat(128),"e\u0301".repeat(8)})
            assertDoesNotThrow(()->PrivateAdminBootstrap.validatePassword(value.toCharArray()));
        for(String value:new String[]{"","a".repeat(14),"a".repeat(129),"😀".repeat(129),"a".repeat(14)+"\uD800", "a".repeat(14)+"\uDC00", "a".repeat(14)+"\uD800x", "a".repeat(14)+"\n", "a".repeat(14)+"\u007f"})
            assertThrows(DomainFailure.class,()->PrivateAdminBootstrap.validatePassword(value.toCharArray()));
    }
}
