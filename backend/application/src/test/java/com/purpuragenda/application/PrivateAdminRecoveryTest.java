package com.purpuragenda.application;

import com.purpuragenda.application.ReenrollmentGrantPolicy.Binding;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.AdminMfaUnitOfWork.*;
import com.purpuragenda.application.port.out.AdminRecoveryUnitOfWork.*;
import com.purpuragenda.application.port.out.AdminRecoveryUnitOfWork.Admin;
import com.purpuragenda.domain.DomainFailure;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrivateAdminRecoveryTest {
    final Binding binding=new Binding(new byte[32],new byte[32]);
    final List<RecoveryCode> issued=new ArrayList<>();final List<Secret> secrets=new ArrayList<>();
    Identity identity=new Identity(UUID.randomUUID(),"synthetic-hash");boolean authorized=true,failCommit;long step=7;int writes;
    final Admin context=new Admin() {
        public Instant now() { return Instant.EPOCH; }
        public Optional<Identity> identity() { return Optional.ofNullable(identity); }
        public Optional<Secret> begin(String hash,Binding value,String token) { assertSame(binding,value);return secret(); }
        public Optional<Secret> prove(String hash,Binding value) { assertSame(binding,value);return secret(); }
        private Optional<Secret> secret() {
            if(!authorized)return Optional.empty();byte[] bytes=new byte[20];Arrays.fill(bytes,(byte)7);
            var secret=new Secret(bytes);secrets.add(secret);return Optional.of(secret);
        }
        public void reenroll(long accepted,List<String> hashes) { assertEquals(7,accepted);assertEquals(10,new HashSet<>(hashes).size());writes++; }
    };
    final AdminRecoveryUnitOfWork store=new AdminRecoveryUnitOfWork() {
        public <T>T locked(String email,Function<Admin,T> work) { assertEquals("owner@example.test",email);T result=work.apply(context);if(failCommit)throw new IllegalStateException("SYNTHETIC_COMMIT_FAILURE");return result; }
    };
    final PasswordHash passwords=new PasswordHash() {
        public String hash(char[] value) { throw new AssertionError("NO_PASSWORD_RESET"); }
        public boolean verify(char[] value,String hash) { return Arrays.equals(value,password()); }
    };
    final AdminRecoveryCrypto crypto=new AdminRecoveryCrypto() {
        public Optional<String> digest(char[] value) { return value!=null && value.length==43?Optional.of("a".repeat(64)):Optional.empty(); }
        public long acceptedStep(byte[] secret,String code,Instant now,long previous) { assertEquals(-1,previous);assertEquals(Instant.EPOCH,now);return step; }
        public RecoveryCode recoveryCode() { var code=new RecoveryCode(token(),String.format("%064x",issued.size()));issued.add(code);return code; }
    };
    final PrivateAdminRecovery recovery=new PrivateAdminRecovery(store,passwords,crypto);
    char[] password() { return "synthetic private password".toCharArray(); }
    char[] token() { char[] token=new char[43];Arrays.fill(token,'a');return token; }
    void cleared(char[] value) { assertTrue(Arrays.equals(new char[value.length],value),"SENSITIVE_BUFFER_NOT_CLEARED"); }
    void erasedSecrets() { secrets.forEach(value->assertTrue(Arrays.equals(new byte[20],value.bytes()),"SECRET_NOT_CLEARED")); }
    @Test void recoveryReturnsOnlyBoundReenrollmentAndConsumesInputBuffers() {
        char[] password=password(),code=token();
        try(var result=recovery.begin("Owner@Example.test",password,code,binding).orElseThrow()) {
            cleared(password);cleared(code);assertSame(issued.getFirst(),result.token());assertSame(secrets.getFirst(),result.secret());
            assertEquals("AdminReenrollment[REDACTED]",result.toString());assertEquals("AdminRecoveryIdentity[REDACTED]",identity.toString());
        }
        issued.forEach(value->cleared(value.value()));erasedSecrets();assertEquals(0,writes);
    }
    @Test void rejectedRecoveryNeverExposesPreparedTokenOrMutatesEnrollment() {
        assertTrue(recovery.begin("owner@example.test",password(),null,binding).isEmpty());
        assertTrue(recovery.begin("owner@example.test","incorrect synthetic password".toCharArray(),token(),binding).isEmpty());
        authorized=false;assertTrue(recovery.begin("owner@example.test",password(),token(),binding).isEmpty());
        assertEquals(1,issued.size());cleared(issued.getFirst().value());assertTrue(secrets.isEmpty());
        identity=null;assertTrue(recovery.begin("owner@example.test",password(),token(),binding).isEmpty());assertEquals(0,writes);
    }
    @Test void reenrollmentRequiresPasswordGrantAndFreshFactorBeforeIssuingTenCodes() {
        assertTrue(recovery.reenroll("owner@example.test",password(),null,binding,"synthetic").isEmpty());
        assertTrue(recovery.reenroll("owner@example.test","incorrect synthetic password".toCharArray(),token(),binding,"synthetic").isEmpty());
        authorized=false;assertTrue(recovery.reenroll("owner@example.test",password(),token(),binding,"synthetic").isEmpty());authorized=true;
        step=-1;assertTrue(recovery.reenroll("owner@example.test",password(),token(),binding,"synthetic").isEmpty());erasedSecrets();step=7;
        char[] password=password(),token=token();
        try(var result=recovery.reenroll("owner@example.test",password,token,binding,"synthetic").orElseThrow()) {
            cleared(password);cleared(token);assertEquals(10,result.codes().size());assertEquals(1,writes);erasedSecrets();
        }
        issued.forEach(value->cleared(value.value()));identity=null;
        assertTrue(recovery.reenroll("owner@example.test",password(),token(),binding,"synthetic").isEmpty());
    }
    @Test void commitFailureErasesUnreturnedGrantSecretAndReenrollmentCodes() {
        failCommit=true;char[] password=password(),code=token();
        assertThrows(IllegalStateException.class,()->recovery.begin("owner@example.test",password,code,binding));cleared(password);cleared(code);erasedSecrets();issued.forEach(value->cleared(value.value()));
        char[] nextPassword=password(),token=token();
        assertThrows(IllegalStateException.class,()->recovery.reenroll("owner@example.test",nextPassword,token,binding,"synthetic"));
        cleared(nextPassword);cleared(token);erasedSecrets();assertEquals(11,issued.size());issued.forEach(value->cleared(value.value()));
    }
    @Test void invalidInputAlwaysClearsOwnedBuffers() {
        char[] shortPassword="short".toCharArray(),code=token();
        assertThrows(DomainFailure.class,()->recovery.begin("owner@example.test",shortPassword,code,binding));cleared(shortPassword);cleared(code);
        char[] password=password(),token=token();assertThrows(DomainFailure.class,()->recovery.reenroll("invalid",password,token,binding,"synthetic"));cleared(password);cleared(token);
        assertThrows(DomainFailure.class,()->recovery.begin("owner@example.test",null,null,binding));
        assertThrows(DomainFailure.class,()->recovery.reenroll("owner@example.test",null,null,binding,"synthetic"));
        assertThrows(NullPointerException.class,()->recovery.begin("owner@example.test",password(),token(),null));
        assertThrows(NullPointerException.class,()->recovery.reenroll("owner@example.test",password(),token(),null,"synthetic"));assertEquals(0,writes);
    }
}
