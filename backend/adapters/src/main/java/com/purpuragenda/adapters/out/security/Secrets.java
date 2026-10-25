package com.purpuragenda.adapters.out.security;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Separate instances/keys protect TOTP secrets and short-lived delivery envelopes. */
public final class Secrets {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_PLAINTEXT_BYTES = 65_536;
    private static final int MAX_ENVELOPE_CHARS = 87_500;
    private final SecretKey key;
    private final String keyId;

    public Secrets(Path file, String keyId) {
        if (keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,64}"))
            throw failure("INVALID_KEY_ID");
        this.keyId = keyId;
        byte[] bytes = null;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(33);
            if (bytes.length != 32) throw failure("KEY_MUST_BE_32_BYTES");
            key = new SecretKeySpec(bytes, "AES");
        } catch (IOException | NullPointerException e) {
            throw failure("KEY_FILE_UNAVAILABLE");
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    public static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }

    public static String hash(String value) {
        if (value == null) throw failure("INVALID_HASH_INPUT");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw failure("HASH_UNAVAILABLE"); }
    }

    public String encrypt(String value) {
        if (value == null || value.length() > MAX_PLAINTEXT_BYTES)
            throw failure("INVALID_PLAINTEXT");
        byte[] plaintext = value.getBytes(StandardCharsets.UTF_8);
        try {
            if (plaintext.length > MAX_PLAINTEXT_BYTES) throw failure("INVALID_PLAINTEXT");
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce);
            byte[] data = cipher.doFinal(plaintext);
            return keyId + ":" + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + data.length).put(nonce).put(data).array());
        } catch (GeneralSecurityException e) { throw failure("ENCRYPTION_FAILED"); }
        finally { Arrays.fill(plaintext, (byte) 0); }
    }

    public String decrypt(String envelope) {
        if (envelope == null || envelope.length() > MAX_ENVELOPE_CHARS)
            throw failure("DECRYPTION_FAILED");
        byte[] plaintext = null;
        try {
            String[] parts = envelope.split(":", 2);
            if (parts.length != 2 || !keyId.equals(parts[0])) throw failure("DECRYPTION_FAILED");
            byte[] data = Base64.getDecoder().decode(parts[1]);
            if (data.length < 28 || data.length > MAX_PLAINTEXT_BYTES + 28)
                throw failure("DECRYPTION_FAILED");
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(data, 12));
            plaintext = cipher.doFinal(data, 12, data.length - 12);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw failure("DECRYPTION_FAILED");
        } finally { if (plaintext != null) Arrays.fill(plaintext, (byte) 0); }
    }

    private Cipher cipher(int mode, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private static IllegalStateException failure(String code) { return new IllegalStateException(code); }
}
