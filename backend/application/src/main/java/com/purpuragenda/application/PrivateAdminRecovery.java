package com.purpuragenda.application;

import com.purpuragenda.application.ReenrollmentGrantPolicy.Binding;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.AdminMfaUnitOfWork.*;
import com.purpuragenda.domain.customer.Input;
import java.util.*;

/** Private recovery workflow. Browser/request binding must come from the session boundary. */
public final class PrivateAdminRecovery {
    private final AdminRecoveryUnitOfWork store;
    private final PasswordHash passwords;
    private final AdminRecoveryCrypto crypto;
    public PrivateAdminRecovery(AdminRecoveryUnitOfWork store,PasswordHash passwords,AdminRecoveryCrypto crypto) {
        this.store=Objects.requireNonNull(store);this.passwords=Objects.requireNonNull(passwords);this.crypto=Objects.requireNonNull(crypto);
    }
    public record Reenrollment(RecoveryCode token,Secret secret) implements AutoCloseable {
        @Override public void close() { token.close();secret.close(); }
        @Override public String toString() { return "AdminReenrollment[REDACTED]"; }
    }
    /** Exact bound retries rotate the bearer token; they never extend or restart recovery. */
    public Optional<Reenrollment> begin(String email,char[] password,char[] recoveryCode,Binding binding) {
        List<RecoveryCode> issued=new ArrayList<>();List<Secret> prepared=new ArrayList<>();
        try {
            PrivateAdminBootstrap.validatePassword(password);Objects.requireNonNull(binding);
            var digest=crypto.digest(recoveryCode);if(digest.isEmpty())return Optional.empty();
            var result=store.locked(Input.email(email),admin->{
                var identity=admin.identity();
                if(identity.isEmpty() || !passwords.verify(password,identity.get().passwordHash()))return Optional.<Reenrollment>empty();
                var token=crypto.recoveryCode();issued.add(token);
                var secret=admin.begin(digest.get(),binding,token.hash());
                secret.ifPresent(prepared::add);
                return secret.map(value->new Reenrollment(token,value));
            });
            if(result.isEmpty())issued.forEach(RecoveryCode::close);
            return result;
        } catch(RuntimeException failure) { issued.forEach(RecoveryCode::close);prepared.forEach(Secret::close);throw failure; }
        finally { clear(password);clear(recoveryCode); }
    }
    public Optional<Enrollment> reenroll(String email,char[] password,char[] token,Binding binding,String code) {
        List<RecoveryCode> issued=new ArrayList<>();
        try {
            PrivateAdminBootstrap.validatePassword(password);Objects.requireNonNull(binding);
            var digest=crypto.digest(token);if(digest.isEmpty())return Optional.empty();
            return store.locked(Input.email(email),admin->{
                var identity=admin.identity();
                if(identity.isEmpty() || !passwords.verify(password,identity.get().passwordHash()))return Optional.empty();
                var value=admin.prove(digest.get(),binding);if(value.isEmpty())return Optional.empty();
                long step;
                try(var secret=value.get()) {
                    byte[] bytes=secret.bytes();
                    try { step=crypto.acceptedStep(bytes,code,admin.now(),-1); }
                    finally { Arrays.fill(bytes,(byte)0); }
                }
                if(step<0)return Optional.empty();
                for(int i=0;i<10;i++)issued.add(crypto.recoveryCode());
                admin.reenroll(step,issued.stream().map(RecoveryCode::hash).toList());
                return Optional.of(new Enrollment(issued));
            });
        } catch(RuntimeException failure) { issued.forEach(RecoveryCode::close);throw failure; }
        finally { clear(password);clear(token); }
    }
    private static void clear(char[] value) { if(value!=null)Arrays.fill(value,'\0'); }
}
