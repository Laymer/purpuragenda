package com.purpuragenda.adapters.out.security;

import com.purpuragenda.application.port.out.AdminRecoveryCrypto;
import com.purpuragenda.application.port.out.AdminMfaUnitOfWork.RecoveryCode;
import java.security.*;
import java.time.Instant;
import java.util.*;

public final class AdminCrypto implements AdminRecoveryCrypto {
    private static final SecureRandom RANDOM=new SecureRandom();
    @Override public long acceptedStep(byte[] secret,String code,Instant now,long previousStep) {
        return Totp.acceptedStep(secret,code,now,previousStep);
    }
    @Override public RecoveryCode recoveryCode() {
        byte[] entropy=new byte[32],ascii=null,digest=null;char[] value=null;
        RANDOM.nextBytes(entropy);
        try {
            ascii=Base64.getUrlEncoder().withoutPadding().encode(entropy);
            digest=MessageDigest.getInstance("SHA-256").digest(ascii);
            value=new char[ascii.length];for(int i=0;i<ascii.length;i++)value[i]=(char)ascii[i];
            return new RecoveryCode(value,HexFormat.of().formatHex(digest));
        } catch(GeneralSecurityException unavailable) { throw new IllegalStateException("ADMIN_CRYPTO_UNAVAILABLE"); }
        finally {
            Arrays.fill(entropy,(byte)0);if(ascii!=null)Arrays.fill(ascii,(byte)0);
            if(digest!=null)Arrays.fill(digest,(byte)0);if(value!=null)Arrays.fill(value,'\0');
        }
    }
    @Override public Optional<String> digest(char[] value) {
        if(value==null || value.length!=43)return Optional.empty();
        byte[] ascii=new byte[value.length];
        try {
            for(int i=0;i<value.length;i++) {
                char c=value[i];
                if(!(c>='A' && c<='Z' || c>='a' && c<='z' || c>='0' && c<='9' || c=='_' || c=='-'))return Optional.empty();
                ascii[i]=(byte)c;
            }
            return Optional.of(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ascii)));
        } catch(GeneralSecurityException unavailable) { throw new IllegalStateException("ADMIN_CRYPTO_UNAVAILABLE"); }
        finally { Arrays.fill(ascii,(byte)0); }
    }
}
