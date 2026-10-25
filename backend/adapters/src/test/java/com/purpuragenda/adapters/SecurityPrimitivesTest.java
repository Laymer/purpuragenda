package com.purpuragenda.adapters;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import com.purpuragenda.adapters.out.security.Secrets;
import com.purpuragenda.adapters.out.security.Totp;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SecurityPrimitivesTest {
    @TempDir Path directory;

    private Secrets secrets(String id, byte value) throws Exception {
        Path path = directory.resolve(id + ".key");
        byte[] key = new byte[32]; Arrays.fill(key, value);
        Files.write(path, key); Arrays.fill(key, (byte) 0);
        return new Secrets(path, id);
    }

    private static void safeFailure(String code, org.junit.jupiter.api.function.Executable action) {
        var error = assertThrows(IllegalStateException.class, action);
        assertEquals(code, error.getMessage()); assertNull(error.getCause());
    }

    @Test void encryptionUsesFreshNoncesAndRoundTripsUnicodeAndBoundaries() throws Exception {
        var encryption = secrets("delivery-v1", (byte) 7);
        for (String input : new String[]{"", "Synthetic unicode: é 😀", "x".repeat(65_536)}) {
            String first = encryption.encrypt(input), second = encryption.encrypt(input);
            assertTrue(!first.equals(second));
            assertTrue(MessageDigest.isEqual(input.getBytes(StandardCharsets.UTF_8),
                    encryption.decrypt(first).getBytes(StandardCharsets.UTF_8)));
            assertTrue(first.startsWith("delivery-v1:"));
        }
        safeFailure("INVALID_PLAINTEXT", () -> encryption.encrypt(null));
        safeFailure("INVALID_PLAINTEXT", () -> encryption.encrypt("x".repeat(65_537)));
        safeFailure("INVALID_PLAINTEXT", () -> encryption.encrypt("é".repeat(40_000)));
    }

    @Test void authenticatedEnvelopeRejectsTamperedFieldsAndSeparateKeys() throws Exception {
        var encryption = secrets("delivery-v1", (byte) 1);
        String envelope = encryption.encrypt("Synthetic content"), prefix = "delivery-v1:";
        byte[] packed = Base64.getDecoder().decode(envelope.substring(prefix.length()));
        for (int offset : new int[]{0, 11, 12, packed.length - 1}) {
            byte[] changed = packed.clone(); changed[offset] ^= 1;
            safeFailure("DECRYPTION_FAILED", () -> encryption.decrypt(prefix + Base64.getEncoder().encodeToString(changed)));
        }
        var otherKey = secrets("delivery-v1", (byte) 2);
        safeFailure("DECRYPTION_FAILED", () -> otherKey.decrypt(envelope));
        var differentId = secrets("totp-v1", (byte) 1);
        safeFailure("DECRYPTION_FAILED", () -> differentId.decrypt(envelope));
        safeFailure("DECRYPTION_FAILED", () -> differentId.decrypt(envelope.replace(prefix, "totp-v1:")));
    }

    @Test void loadedKeyIsStableAfterTheMountedFileChanges() throws Exception {
        var encryption = secrets("stable", (byte) 5);
        String plaintext = "Synthetic stable content";
        String envelope = encryption.encrypt(plaintext);
        Files.write(directory.resolve("stable.key"), new byte[32]);
        Files.delete(directory.resolve("stable.key"));
        assertTrue(MessageDigest.isEqual(plaintext.getBytes(StandardCharsets.UTF_8),
                encryption.decrypt(envelope).getBytes(StandardCharsets.UTF_8)));
    }

    @Test void malformedOrOversizedEnvelopesFailSafely() throws Exception {
        var encryption = secrets("test", (byte) 0);
        for (String envelope : new String[]{null, "", "test", "other:AA==", "test:!", "test:",
                "test:" + Base64.getEncoder().encodeToString(new byte[27]),
                "test:" + Base64.getEncoder().encodeToString(new byte[65_565]), "x".repeat(87_501)})
            safeFailure("DECRYPTION_FAILED", () -> encryption.decrypt(envelope));
    }

    @Test void keyFilesAndIdsAreBoundedAndUnavailableFilesFailSafely() throws Exception {
        Path path = directory.resolve("synthetic.key");
        for (int size : new int[]{0, 31, 33, 100_000}) {
            Files.write(path, new byte[size]);
            safeFailure("KEY_MUST_BE_32_BYTES", () -> new Secrets(path, "test"));
        }
        for (String id : new String[]{null, "", "id:bad", "bad\n", "x".repeat(65)})
            safeFailure("INVALID_KEY_ID", () -> new Secrets(path, id));
        safeFailure("KEY_FILE_UNAVAILABLE", () -> new Secrets(null, "test"));
        safeFailure("KEY_FILE_UNAVAILABLE", () -> new Secrets(directory.resolve("absent"), "test"));
        safeFailure("KEY_FILE_UNAVAILABLE", () -> new Secrets(directory, "test"));
        Path link = directory.resolve("link.key"); Files.createSymbolicLink(link, path);
        safeFailure("KEY_FILE_UNAVAILABLE", () -> new Secrets(link, "test"));
    }

    @Test void tokensHaveExactShapeAndSha256MatchesPublicVector() {
        var tokens = new HashSet<String>();
        for (int i = 0; i < 128; i++) {
            String token = Secrets.token();
            assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
            assertEquals(32, Base64.getUrlDecoder().decode(token).length);
            assertTrue(tokens.add(token)); assertTrue(Secrets.hash(token).matches("[a-f0-9]{64}"));
        }
        assertTrue(Secrets.hash("abc").equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
        safeFailure("INVALID_HASH_INPUT", () -> Secrets.hash(null));
    }

    @Test void allRfc6238VectorsMatchIncludingBeyond2038() throws Exception {
        long[] times = {59, 1111111109, 1111111111, 1234567890, 2000000000, 20000000000L};
        String[][] expected = {
            {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"},
            {"46119246", "68084774", "67062674", "91819424", "90698825", "77737706"},
            {"90693936", "25091201", "99943326", "93441116", "38618901", "47863826"}
        };
        String[] algorithms = {"HmacSHA1", "HmacSHA256", "HmacSHA512"};
        String[] keys = {"12345678901234567890", "12345678901234567890123456789012",
                "1234567890123456789012345678901234567890123456789012345678901234"};
        for (int algorithm = 0; algorithm < algorithms.length; algorithm++) {
            var generator = new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30), 8, algorithms[algorithm]);
            var key = new SecretKeySpec(keys[algorithm].getBytes(StandardCharsets.US_ASCII), algorithms[algorithm]);
            for (int i = 0; i < times.length; i++) {
                String actual = String.format(Locale.ROOT, "%08d", generator.generateOneTimePassword(key, Instant.ofEpochSecond(times[i])));
                assertTrue(expected[algorithm][i].equals(actual), "Public RFC6238 vector mismatch");
            }
        }
    }

    @Test void sixDigitTotpAcceptsOnlyAdjacentUnconsumedSteps() throws Exception {
        byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        var generator = new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30), 6, "HmacSHA1");
        var key = new SecretKeySpec(secret, "HmacSHA1");
        Instant now = Instant.ofEpochSecond(1_234_567_890L); long current = now.getEpochSecond() / 30;
        for (long step = current - 2; step <= current + 2; step++) {
            String code = String.format(Locale.ROOT, "%06d", generator.generateOneTimePassword(key, Instant.ofEpochSecond(step * 30)));
            assertTrue(Totp.acceptedStep(secret, code, now, -1) == (Math.abs(step - current) <= 1 ? step : -1));
            assertTrue(Totp.acceptedStep(secret, code, now, step) == -1);
        }
        assertTrue(Totp.acceptedStep(secret, "755224", Instant.EPOCH, -1) == 0);
        assertTrue(Totp.acceptedStep(secret, "287082", Instant.ofEpochSecond(59), -1) == 1);
        assertTrue(Totp.acceptedStep(secret, "287082", Instant.ofEpochSecond(60), -1) == 1);
        byte[] first = Totp.newSecret(), second = Totp.newSecret();
        assertEquals(20, first.length); assertTrue(!MessageDigest.isEqual(first, second));
        for (String bad : new String[]{null, "", "12345", "1234567", "１２３４５６", "12345a"})
            assertTrue(Totp.acceptedStep(secret, bad, now, -1) == -1);
        assertTrue(Totp.acceptedStep(null, "123456", now, -1) == -1);
        assertTrue(Totp.acceptedStep(new byte[19], "123456", now, -1) == -1);
        assertTrue(Totp.acceptedStep(secret, "123456", null, -1) == -1);
        assertTrue(Totp.acceptedStep(secret, "123456", Instant.ofEpochSecond(-1), -1) == -1);
        assertTrue(Totp.acceptedStep(secret, "123456", Instant.MAX, -1) == -1);
        assertTrue(Totp.acceptedStep(secret, "123456", now, -2) == -1);
        Arrays.fill(first, (byte) 0); Arrays.fill(second, (byte) 0); Arrays.fill(secret, (byte) 0);
    }

    @Test void totpBoundsEveryCandidateToTheLibraryMillisecondRange() throws Exception {
        byte[] secret = new byte[20];
        Instant unsupported = Instant.ofEpochSecond(Long.MAX_VALUE / 1000 + 1);
        assertTrue(Totp.acceptedStep(secret, "123456", unsupported, -1) == -1);
        long lastStep = Long.MAX_VALUE / 1000 / 30;
        assertTrue(Totp.acceptedStep(secret, "123456", Instant.ofEpochSecond(lastStep * 30), -1) == -1);
        Instant safe = Instant.ofEpochSecond((lastStep - 1) * 30 + 29);
        var generator = new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30), 6, "HmacSHA1");
        String code = String.format(Locale.ROOT, "%06d", generator.generateOneTimePassword(
                new SecretKeySpec(secret, "HmacSHA1"), safe));
        assertTrue(Totp.acceptedStep(secret, code, safe, -1) == lastStep - 1);
        Arrays.fill(secret, (byte) 0);
    }

    @Test void argon2idMatchesRfc9106Section53Vector() {
        byte[] password = new byte[32], salt = new byte[16], secret = new byte[8], associated = new byte[12];
        Arrays.fill(password, (byte) 1); Arrays.fill(salt, (byte) 2);
        Arrays.fill(secret, (byte) 3); Arrays.fill(associated, (byte) 4);
        var parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13).withIterations(3)
                .withMemoryAsKB(32).withParallelism(4).withSalt(salt).withSecret(secret)
                .withAdditional(associated).build();
        var generator = new Argon2BytesGenerator(); generator.init(parameters);
        byte[] result = new byte[32]; generator.generateBytes(password, result);
        assertTrue(HexFormat.of().formatHex(result).equals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659"));
        parameters.clear(); Arrays.fill(result, (byte) 0); Arrays.fill(password, (byte) 0);
        Arrays.fill(salt, (byte) 0); Arrays.fill(secret, (byte) 0); Arrays.fill(associated, (byte) 0);
    }

    @Test void approvedArgon2idParametersRemain64MiBThreeIterationsOneLane() throws Exception {
        var parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13).withMemoryAsKB(65_536)
                .withIterations(3).withParallelism(1).withSalt(new byte[16]).build();
        var generator = new Argon2BytesGenerator(); generator.init(parameters);
        byte[] password = "Synthetic benchmark input".getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[32], repeated = new byte[32], changed = new byte[32];
        long began = System.nanoTime(); generator.generateBytes(password, result); long elapsed = System.nanoTime() - began;
        generator.generateBytes(password, repeated); password[0] ^= 1; generator.generateBytes(password, changed);
        assertTrue(MessageDigest.isEqual(result, repeated)); assertTrue(!MessageDigest.isEqual(result, changed));
        assertEquals(65_536, parameters.getMemory()); assertEquals(3, parameters.getIterations()); assertEquals(1, parameters.getLanes());
        Files.writeString(Path.of("target/argon2-benchmark.json"),
                "{\"memoryKiB\":65536,\"iterations\":3,\"parallelism\":1,\"encodingNanos\":" + elapsed + "}");
        parameters.clear(); Arrays.fill(password, (byte) 0); Arrays.fill(result, (byte) 0);
        Arrays.fill(repeated, (byte) 0); Arrays.fill(changed, (byte) 0);
    }
}
