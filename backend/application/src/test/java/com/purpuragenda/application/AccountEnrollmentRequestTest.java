package com.purpuragenda.application;

import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.AccountEnrollmentStore.Enrolled;
import com.purpuragenda.application.port.out.RateLimitStore.*;
import com.purpuragenda.domain.DomainFailure;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccountEnrollmentRequestTest {
    int hashes, writes, releases;
    boolean accepted, releaseFails, storeFails;
    Decision decision=Decision.allow(List.of(new Reservation(Kind.ACCOUNT_ENROLLMENT_IP,"a".repeat(64),Instant.EPOCH)));
    List<Charge> charges;
    final RateLimitStore limits=new RateLimitStore() {
        public Decision admit(List<Charge> requested) { charges=requested;return decision; }
        public boolean release(Reservation reservation) { releases++;if(releaseFails)throw new IllegalStateException("SYNTHETIC_STORAGE");return true; }
    };
    final PasswordHash passwords=new PasswordHash() {
        public String hash(char[] password) { hashes++;assertNotNull(charges);return "synthetic-encoded"; }
        public boolean verify(char[] password,String encoded) { return false; }
    };
    final AccountEnrollment enrollment=new AccountEnrollment(request->{
        writes++;if(storeFails)throw new IllegalStateException("SYNTHETIC_ROLLBACK");
        return accepted?Optional.of(new Enrolled(UUID.randomUUID(),UUID.randomUUID(),Instant.EPOCH)):Optional.empty();
    },passwords);
    final AccountEnrollmentRequest command=new AccountEnrollmentRequest(enrollment,new RequestThrottle(limits));
    AccountEnrollmentRequest.Result enroll(char[] secret) { return command.enroll("A".repeat(43),"b".repeat(64),secret,"2001:db8:1234:5678::9"); }
    char[] secret() { return "synthetic-account-password".toCharArray(); }

    @Test void deniedNetworkNeverHashesOrRedeemsAndAlwaysClearsThePassword() {
        decision=Decision.deny(17);char[] secret=secret();var result=enroll(secret);
        assertFalse(result.enrolled());assertEquals(17,result.retryAfterSeconds());assertEquals(0,hashes);assertEquals(0,writes);assertEquals(0,releases);
        assertTrue(Arrays.equals(new char[secret.length],secret));
        assertEquals(List.of(new Charge(Kind.ACCOUNT_ENROLLMENT_IP,"20010db812345678/64")),charges);
    }
    @Test void successfulCommittedEnrollmentReleasesOnlyItsReservedAttempt() {
        accepted=true;char[] secret=secret();assertTrue(enroll(secret).enrolled());
        assertEquals(1,hashes);assertEquals(1,writes);assertEquals(1,releases);assertTrue(Arrays.equals(new char[secret.length],secret));
    }
    @Test void rejectedProofOrTransactionFailureRetainsTheNetworkCharge() {
        assertFalse(enroll(secret()).enrolled());assertEquals(0,releases);
        storeFails=true;char[] secret=secret();assertThrows(IllegalStateException.class,()->enroll(secret));assertEquals(0,releases);
        assertTrue(Arrays.equals(new char[secret.length],secret));
    }
    @Test void committedSuccessIsNotHiddenByAnUnavailableRelease() {
        accepted=true;releaseFails=true;assertTrue(enroll(secret()).enrolled());assertEquals(1,releases);
    }
    @Test void invalidCredentialOrNetworkCannotLeavePasswordMaterialOrReachStorage() {
        char[] shortSecret="short".toCharArray();assertThrows(DomainFailure.class,()->enroll(shortSecret));assertEquals(0,hashes);assertEquals(0,writes);assertEquals(0,releases);
        char[] secret=secret();assertThrows(IllegalArgumentException.class,()->command.enroll("A".repeat(43),"b".repeat(64),secret,"not-an-ip"));
        assertTrue(Arrays.equals(new char[secret.length],secret));assertEquals(0,hashes);
    }
    @Test void resultCannotExposeInconsistentOrPrivateState() {
        assertThrows(IllegalArgumentException.class,()->new AccountEnrollmentRequest.Result(true,1));
        assertThrows(IllegalArgumentException.class,()->new AccountEnrollmentRequest.Result(false,-1));
        assertEquals("AccountEnrollmentResult[REDACTED]",new AccountEnrollmentRequest.Result(false,0).toString());
        assertThrows(NullPointerException.class,()->new AccountEnrollmentRequest(null,new RequestThrottle(limits)));
        assertThrows(NullPointerException.class,()->new AccountEnrollmentRequest(enrollment,null));
    }
}
