package com.purpuragenda.adapters.in.rest;

import java.security.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

final class RequestSecrets {
    private static final SecureRandom RANDOM=new SecureRandom();
    private RequestSecrets(){}
    static String nonce(){byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    static String hash(String input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
