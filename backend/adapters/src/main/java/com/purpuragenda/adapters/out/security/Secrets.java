package com.purpuragenda.adapters.out.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** AES-GCM envelope format keyId:base64(nonce+ciphertext). No secret is logged. */
public final class Secrets {
    private final SecretKey key;private final String keyId;
    private static final SecureRandom RANDOM=new SecureRandom();
    public Secrets(Path file,String keyId){try{byte[] bytes=Files.readAllBytes(file);if(bytes.length!=32)throw new IllegalArgumentException("KEY_MUST_BE_32_BYTES");key=new SecretKeySpec(bytes,"AES");this.keyId=keyId;}catch(java.io.IOException e){throw new IllegalStateException("KEY_FILE_UNAVAILABLE");}}
    public static String token(){byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public String encrypt(String value){try{byte[] nonce=new byte[12];RANDOM.nextBytes(nonce);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));cipher.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));byte[] data=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));return keyId+":"+Base64.getEncoder().encodeToString(java.nio.ByteBuffer.allocate(nonce.length+data.length).put(nonce).put(data).array());}catch(GeneralSecurityException e){throw new IllegalStateException("ENCRYPTION_FAILED");}}
    public String decrypt(String envelope){try{String[] parts=envelope.split(":",2);if(parts.length!=2||!keyId.equals(parts[0]))throw new IllegalStateException("KEY_UNAVAILABLE");byte[] data=Base64.getDecoder().decode(parts[1]);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOf(data,12)));cipher.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));return new String(cipher.doFinal(Arrays.copyOfRange(data,12,data.length)),StandardCharsets.UTF_8);}catch(GeneralSecurityException|IllegalArgumentException e){throw new IllegalStateException("DECRYPTION_FAILED");}}
}
