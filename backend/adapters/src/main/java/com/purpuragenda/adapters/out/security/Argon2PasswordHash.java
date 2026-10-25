package com.purpuragenda.adapters.out.security;

import com.purpuragenda.application.PrivateAdminBootstrap;
import com.purpuragenda.application.port.out.PasswordHash;
import com.purpuragenda.domain.DomainFailure;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.bouncycastle.crypto.PasswordConverter;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/** Fixed Argon2id v19/64MiB/3/p1; stored cost parameters never control resource use. */
public final class Argon2PasswordHash implements PasswordHash {
    private static final String PREFIX="$argon2id$v=19$m=65536,t=3,p=1$";
    private static final Base64.Encoder ENCODER=Base64.getEncoder().withoutPadding();
    private static final SecureRandom RANDOM=new SecureRandom();
    @Override public String hash(char[] password) {
        PrivateAdminBootstrap.validatePassword(password);
        byte[] salt=new byte[16]; RANDOM.nextBytes(salt);
        byte[] derived=derive(password,salt);
        try { return PREFIX+ENCODER.encodeToString(salt)+"$"+ENCODER.encodeToString(derived); }
        finally { Arrays.fill(salt,(byte)0); Arrays.fill(derived,(byte)0); }
    }
    @Override public boolean verify(char[] password,String encoded) {
        byte[][] material=parse(encoded);
        if(material==null) return false;
        byte[] derived=null;
        try {
            PrivateAdminBootstrap.validatePassword(password);
            derived=derive(password,material[0]);
            return MessageDigest.isEqual(material[1],derived);
        } catch(DomainFailure invalid) { return false; }
        finally { clear(material); if(derived!=null) Arrays.fill(derived,(byte)0); }
    }
    public static boolean validEncoding(String encoded) {
        byte[][] material=parse(encoded);
        if(material==null) return false;
        clear(material); return true;
    }
    private static byte[][] parse(String encoded) {
        if(encoded==null || encoded.length()!=PREFIX.length()+66 || !encoded.startsWith(PREFIX)) return null;
        String tail=encoded.substring(PREFIX.length());
        if(tail.charAt(22)!='$') return null;
        byte[][] material=new byte[2][];
        try {
            String salt=tail.substring(0,22), hash=tail.substring(23);
            material[0]=Base64.getDecoder().decode(salt); material[1]=Base64.getDecoder().decode(hash);
            if(!ENCODER.encodeToString(material[0]).equals(salt) || !ENCODER.encodeToString(material[1]).equals(hash)) {
                clear(material); return null;
            }
            return material;
        } catch(IllegalArgumentException malformed) { clear(material); return null; }
    }
    private static void clear(byte[][] values) { for(byte[] value:values) if(value!=null) Arrays.fill(value,(byte)0); }
    private static byte[] derive(char[] password,byte[] salt) {
        char[] copy=password.clone(); byte[] bytes=null, output=new byte[32];
        Argon2Parameters.Builder builder=null; Argon2Parameters parameters=null;
        try {
            builder=new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13).withMemoryAsKB(65536)
                    .withIterations(3).withParallelism(1).withSalt(salt);
            parameters=builder.build();
            bytes=PasswordConverter.UTF8.convert(copy);
            var generator=new Argon2BytesGenerator(); generator.init(parameters); generator.generateBytes(bytes,output);
            return output;
        } catch(RuntimeException unavailable) {
            Arrays.fill(output,(byte)0); throw new IllegalStateException("PASSWORD_HASH_UNAVAILABLE");
        } finally {
            Arrays.fill(copy,'\0'); if(bytes!=null) Arrays.fill(bytes,(byte)0);
            if(builder!=null) builder.clear(); if(parameters!=null) parameters.clear();
        }
    }
}
