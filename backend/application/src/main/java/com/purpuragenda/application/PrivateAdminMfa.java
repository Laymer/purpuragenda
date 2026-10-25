package com.purpuragenda.application;

import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.AdminMfaUnitOfWork.*;
import com.purpuragenda.domain.customer.Input;
import java.util.*;
import java.util.function.Function;

/** Private factor workflow only. Never issues a session or reusable authorization capability. */
public final class PrivateAdminMfa {
    private final AdminMfaUnitOfWork store;
    private final PasswordHash passwords;
    private final AdminFactorCrypto factors;
    public PrivateAdminMfa(AdminMfaUnitOfWork store,PasswordHash passwords,AdminFactorCrypto factors) {
        this.store=Objects.requireNonNull(store);this.passwords=Objects.requireNonNull(passwords);this.factors=Objects.requireNonNull(factors);
    }
    public Optional<Secret> provision(String email,char[] password) {
        List<Secret> prepared=new ArrayList<>();
        try {
            return withPassword(email,password,admin->{
                var value=admin.credential();
                if(value.isEmpty() || value.get().enrolled() || !passwords.verify(password,value.get().passwordHash())) return Optional.empty();
                byte[] bytes=value.get().secret().bytes();
                try { var secret=new Secret(bytes);prepared.add(secret);return Optional.of(secret); }
                finally { Arrays.fill(bytes,(byte)0); }
            });
        } catch(RuntimeException failure) { prepared.forEach(Secret::close);throw failure; }
    }
    public Optional<Enrollment> enroll(String email,char[] password,String code) {
        List<RecoveryCode> issued=new ArrayList<>();
        try {
            return withPassword(email,password,admin->{
                var value=admin.credential();
                if(value.isEmpty() || value.get().enrolled() || !passwords.verify(password,value.get().passwordHash())) return Optional.empty();
                long step=step(admin,value.get(),code);if(step<0)return Optional.empty();
                for(int i=0;i<10;i++)issued.add(factors.recoveryCode());
                admin.enroll(step,issued.stream().map(RecoveryCode::hash).toList());
                return Optional.of(new Enrollment(issued));
            });
        } catch(RuntimeException failure) { issued.forEach(RecoveryCode::close);throw failure; }
    }
    public boolean authenticate(String email,char[] password,String code) {
        return withPassword(email,password,admin->{
            var value=admin.credential();
            if(value.isEmpty() || !value.get().enrolled() || !passwords.verify(password,value.get().passwordHash()))return false;
            long step=step(admin,value.get(),code);if(step<0)return false;
            admin.acceptStep(step);return true;
        });
    }
    private long step(Admin admin,Credential value,String code) {
        byte[] secret=value.secret().bytes();
        try { return factors.acceptedStep(secret,code,admin.now(),value.acceptedStep()); }
        finally { Arrays.fill(secret,(byte)0); }
    }
    private <T> T withPassword(String email,char[] password,Function<Admin,T> work) {
        try { PrivateAdminBootstrap.validatePassword(password);return store.locked(Input.email(email),work); }
        finally { if(password!=null)Arrays.fill(password,'\0'); }
    }
}
