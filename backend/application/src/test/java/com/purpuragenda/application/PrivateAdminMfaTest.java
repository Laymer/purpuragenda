package com.purpuragenda.application;

import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.AdminMfaUnitOfWork.*;
import com.purpuragenda.domain.DomainFailure;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrivateAdminMfaTest {
    final List<RecoveryCode> issued=new ArrayList<>();
    final Instant now=Instant.parse("2026-10-25T10:00:00Z");
    Credential credential=new Credential(UUID.randomUUID(),"synthetic-hash",false,-1,new Secret(new byte[20]));
    long nextStep=7,lastAccepted=-1,previous;int writes;boolean failCommit;boolean initial=true;byte[] borrowed;Object prepared;
    final Admin context=new Admin() {
        public Instant now() { return now; }
        public Optional<Credential> credential() { return Optional.ofNullable(credential); }
        public boolean initialEnrollment() { return initial; }
        public void enroll(long step,List<String> hashes) { assertEquals(10,new HashSet<>(hashes).size());writes++;lastAccepted=step; }
        public void acceptStep(long step) { writes++;lastAccepted=step; }
    };
    final AdminMfaUnitOfWork store=new AdminMfaUnitOfWork() {
        public <T>T locked(String email,Function<Admin,T> work) {
            assertTrue(email.equals("owner@example.test"));T result=work.apply(context);prepared=result;
            if(failCommit)throw new IllegalStateException("SYNTHETIC_COMMIT_FAILURE");return result;
        }
    };
    final PasswordHash passwords=new PasswordHash() {
        public String hash(char[] value) { throw new AssertionError("NO_PASSWORD_RESET"); }
        public boolean verify(char[] value,String hash) { return Arrays.equals(value,password()); }
    };
    final AdminFactorCrypto crypto=new AdminFactorCrypto() {
        public long acceptedStep(byte[] secret,String code,Instant captured,long last) {
            assertSame(now,captured);borrowed=secret;previous=last;return nextStep;
        }
        public RecoveryCode recoveryCode() {
            char[] value=new char[43];Arrays.fill(value,'A');value[0]=(char)('A'+issued.size());
            var code=new RecoveryCode(value,String.format("%064x",issued.size()));issued.add(code);return code;
        }
    };
    final PrivateAdminMfa mfa=new PrivateAdminMfa(store,passwords,crypto);
    char[] password() { return "synthetic password phrase".toCharArray(); }
    void enabled() { credential=new Credential(credential.id(),credential.passwordHash(),true,6,credential.secret()); }
    void cleared(char[] value) { assertTrue(Arrays.equals(new char[value.length],value),"SENSITIVE_BUFFER_NOT_CLEARED"); }
    @Test void provisioningRequiresPasswordAndUnenrolledIdentityAndReturnsIndependentSecret() {
        byte[] initial=new byte[20];Arrays.fill(initial,(byte)7);
        credential=new Credential(credential.id(),credential.passwordHash(),false,-1,new Secret(initial));
        char[] password=password();try(var provision=mfa.provision("Owner@Example.test",password).orElseThrow()) {
            cleared(password);assertTrue(Arrays.equals(initial,provision.bytes()));provision.close();assertTrue(Arrays.equals(new byte[20],provision.bytes()));
        }
        assertTrue(mfa.provision("owner@example.test","incorrect password phrase".toCharArray()).isEmpty());
        enabled();assertTrue(mfa.provision("owner@example.test",password()).isEmpty());
        credential=null;assertTrue(mfa.provision("owner@example.test",password()).isEmpty());assertEquals(0,writes);
    }
    @Test void enrollmentConsumesOneStepAndIssuesTenCodesOnlyAfterBothFactors() {
        char[] password=password();try(var result=mfa.enroll("owner@example.test",password,"synthetic").orElseThrow()) {
            cleared(password);assertEquals(10,result.codes().size());assertEquals(1,writes);assertEquals(7,lastAccepted);assertEquals(-1,previous);
            assertTrue(Arrays.equals(new byte[20],borrowed));assertEquals("AdminEnrollment[REDACTED]",result.toString());
            result.close();result.codes().forEach(code->cleared(code.value()));
        }
    }
    @Test void missingWrongPasswordAlreadyEnrolledOrRejectedCodeCannotEnroll() {
        assertTrue(mfa.enroll("owner@example.test","incorrect password phrase".toCharArray(),"synthetic").isEmpty());
        nextStep=-1;assertTrue(mfa.enroll("owner@example.test",password(),"synthetic").isEmpty());
        enabled();assertTrue(mfa.enroll("owner@example.test",password(),"synthetic").isEmpty());
        credential=null;assertTrue(mfa.enroll("owner@example.test",password(),"synthetic").isEmpty());
        assertEquals(0,writes);assertTrue(issued.isEmpty());
    }
    @Test void authenticationRequiresEnrolledPasswordAndNewTotpStepWithoutIssuingSession() {
        assertFalse(mfa.authenticate("owner@example.test",password(),"synthetic"));enabled();
        assertFalse(mfa.authenticate("owner@example.test","incorrect password phrase".toCharArray(),"synthetic"));
        nextStep=-1;assertFalse(mfa.authenticate("owner@example.test",password(),"synthetic"));nextStep=7;
        char[] password=password();assertTrue(mfa.authenticate("owner@example.test",password,"synthetic"));cleared(password);
        assertEquals(6,previous);assertEquals(7,lastAccepted);assertEquals(1,writes);assertTrue(issued.isEmpty());
        credential=null;assertFalse(mfa.authenticate("owner@example.test",password(),"synthetic"));
    }
    @Test void recoveryHistoryBlocksPasswordOnlyProvisioningAndEnrollmentButNotFullFactorVerification() {
        initial=false;char[] password=password();
        assertTrue(mfa.provision("owner@example.test",password).isEmpty());cleared(password);
        assertTrue(mfa.enroll("owner@example.test",password(),"synthetic").isEmpty());assertEquals(0,writes);assertTrue(issued.isEmpty());
        enabled();assertTrue(mfa.authenticate("owner@example.test",password(),"synthetic"));assertEquals(1,writes);
    }
    @Test void failedCommitErasesUnreturnedProvisioningSecretCopy() {
        byte[] initial=new byte[20];Arrays.fill(initial,(byte)7);
        credential=new Credential(credential.id(),credential.passwordHash(),false,-1,new Secret(initial));
        failCommit=true;char[] password=password();
        assertThrows(IllegalStateException.class,()->mfa.provision("owner@example.test",password));cleared(password);
        var returned=(Optional<?>)prepared;var secret=(Secret)returned.orElseThrow();
        assertTrue(Arrays.equals(new byte[20],secret.bytes()),"UNRETURNED_PROVISIONING_COPY_NOT_ERASED");
        assertTrue(Arrays.equals(initial,credential.secret().bytes()),"CREDENTIAL_OWNER_BUFFER_CHANGED");Arrays.fill(initial,(byte)0);
    }
    @Test void failedCommitClearsEveryIssuedRecoveryCodeAndPassword() {
        failCommit=true;char[] password=password();
        assertThrows(IllegalStateException.class,()->mfa.enroll("owner@example.test",password,"synthetic"));
        cleared(password);assertEquals(10,issued.size());issued.forEach(code->cleared(code.value()));
    }
    @Test void validationFailuresConsumePasswordBeforeAnyStoreAccess() {
        char[] invalid="short".toCharArray();assertThrows(DomainFailure.class,()->mfa.authenticate("owner@example.test",invalid,"synthetic"));cleared(invalid);
        char[] password=password();assertThrows(DomainFailure.class,()->mfa.provision("invalid",password));cleared(password);
        assertThrows(DomainFailure.class,()->mfa.enroll("owner@example.test",null,"synthetic"));assertEquals(0,writes);
    }
    @Test void secretValuesCopyInputsAndRedactEveryDefaultRepresentation() {
        char[] value=password();var code=new RecoveryCode(value,"synthetic-hash");Arrays.fill(value,'x');
        assertTrue(Arrays.equals(password(),code.value()));char[] copy=code.value();Arrays.fill(copy,'x');assertFalse(Arrays.equals(copy,code.value()));
        assertEquals("AdminRecoveryCode[REDACTED]",code.toString());assertEquals("AdminCredential[REDACTED]",credential.toString());
        assertEquals("AdminSecret[REDACTED]",credential.secret().toString());code.close();cleared(code.value());
        byte[] raw=new byte[20];Arrays.fill(raw,(byte)7);try(var secret=new Secret(raw)) {
            raw[0]=1;byte[] exposed=secret.bytes();exposed[0]=2;assertTrue(secret.bytes()[0]==7);Arrays.fill(exposed,(byte)0);
        }Arrays.fill(raw,(byte)0);
        var enrollment=new Enrollment(List.of(code));assertThrows(UnsupportedOperationException.class,()->enrollment.codes().add(code));
    }
}
