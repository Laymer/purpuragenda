package com.purpuragenda.application;

import com.purpuragenda.application.port.out.AccountEnrollmentStore;
import com.purpuragenda.application.port.out.AccountEnrollmentStore.*;
import com.purpuragenda.application.port.out.PasswordHash;
import com.purpuragenda.domain.DomainFailure;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccountEnrollmentTest {
    static final String TOKEN="A".repeat(43),BROWSER="b".repeat(64);
    final List<Enrollment> stored=new ArrayList<>();int hashes;Optional<Enrolled> result=Optional.empty();
    final PasswordHash passwords=new PasswordHash() {
        public String hash(char[] password) { hashes++;return "encoded-"+password.length; }
        public boolean verify(char[] password,String encoded) { return false; }
    };
    final AccountEnrollmentStore store=request -> { stored.add(request);return result; };
    final AccountEnrollment command=new AccountEnrollment(store,passwords);
    static char[] password(int length) { return "p".repeat(length).toCharArray(); }

    @Test void validPasswordIsHashedOnceBeforeTheStoreSeesOnlyTheEncodedValueAndTheBufferIsCleared() {
        result=Optional.of(new Enrolled(UUID.randomUUID(),UUID.randomUUID(),Instant.EPOCH));char[] password=password(15);
        assertEquals(result,command.enroll(TOKEN,BROWSER,password));
        assertEquals(1,hashes);assertEquals("encoded-15",stored.getFirst().passwordHash());assertArrayEquals(new char[15],password);
        assertEquals("AccountEnrollment[REDACTED]",stored.getFirst().toString());assertEquals("AccountEnrolled[REDACTED]",result.get().toString());
    }

    @Test void policyInvalidPasswordsNeverReachHashingOrStorageAndAreAlwaysCleared() {
        for(char[] invalid:new char[][]{password(14),password(129),"short".toCharArray(),(("a".repeat(20))+"\u0007").toCharArray(),(("a".repeat(20))+"\ud800").toCharArray()}) {
            char[] copy=invalid.clone();
            assertEquals("INVALID_ACCOUNT_PASSWORD",assertThrows(DomainFailure.class,()->command.enroll(TOKEN,BROWSER,invalid)).code());
            assertArrayEquals(new char[copy.length],invalid);
        }
        assertEquals("INVALID_ACCOUNT_PASSWORD",assertThrows(DomainFailure.class,()->command.enroll(TOKEN,BROWSER,null)).code());
        assertEquals(0,hashes);assertTrue(stored.isEmpty());
    }

    @Test void rejectedProofIsEmptyAndRequestShapeIsValidated() {
        assertTrue(command.enroll(TOKEN,BROWSER,password(15)).isEmpty());
        for(String[] bad:new String[][]{{"short",BROWSER},{TOKEN,"nothex"},{null,BROWSER},{TOKEN,null}})
            assertThrows(IllegalArgumentException.class,()->command.enroll(bad[0],bad[1],password(15)));
        assertThrows(IllegalArgumentException.class,()->new Enrollment(TOKEN,BROWSER,""));
        assertThrows(IllegalArgumentException.class,()->new Enrollment(TOKEN,BROWSER,null));
        assertThrows(NullPointerException.class,()->new AccountEnrollment(null,passwords));
        assertThrows(NullPointerException.class,()->new AccountEnrollment(store,null));
    }
}
