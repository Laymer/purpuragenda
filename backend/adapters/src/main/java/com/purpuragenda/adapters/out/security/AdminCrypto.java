package com.purpuragenda.adapters.out.security;

import com.purpuragenda.application.port.out.AdminFactorCrypto;
import com.purpuragenda.application.port.out.AdminMfaUnitOfWork.RecoveryCode;
import java.security.*;
import java.time.Instant;
import java.util.*;

public final class AdminCrypto implements AdminFactorCrypto {
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
}
