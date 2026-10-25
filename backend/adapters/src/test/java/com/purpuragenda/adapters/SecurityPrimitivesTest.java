package com.purpuragenda.adapters;
import com.purpuragenda.adapters.out.security.Secrets;
import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.jupiter.api.Assertions.*;

class SecurityPrimitivesTest {
    @Test void totpMatchesRfc6238Sha1Vectors()throws Exception{
        var generator=new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30),8,"HmacSHA1");var key=new SecretKeySpec("12345678901234567890".getBytes(StandardCharsets.US_ASCII),"HmacSHA1");long[] times={59,1111111109,1111111111,1234567890,2000000000,20000000000L};String[] expected={"94287082","07081804","14050471","89005924","69279037","65353130"};for(int i=0;i<times.length;i++)assertEquals(expected[i],String.format(Locale.ROOT,"%08d",generator.generateOneTimePassword(key,Instant.ofEpochSecond(times[i]))));
    }
    @Test void authenticatedEncryptionRejectsTamperingAndTokensHave256RandomBits()throws Exception{
        Path file=Files.createTempFile("purpuragenda-test-key-",".key");Files.write(file,new byte[32]);try{var secrets=new Secrets(file,"test-key");String token=Secrets.token(),encrypted=secrets.encrypt(token);assertEquals(32,Base64.getUrlDecoder().decode(token).length);assertFalse(encrypted.contains(token));assertEquals(token,secrets.decrypt(encrypted));assertNotEquals(encrypted,secrets.encrypt(token));String altered=encrypted.substring(0,encrypted.length()-3)+"AAA";assertThrows(IllegalStateException.class,()->secrets.decrypt(altered));assertThrows(IllegalStateException.class,()->new Secrets(file,"other-key").decrypt(encrypted));assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",Secrets.hash("abc"));Set<String> tokens=new HashSet<>();for(int i=0;i<100;i++)tokens.add(Secrets.token());assertEquals(100,tokens.size());}finally{Files.deleteIfExists(file);}
    }
    @Test void argon2BaselineIsMeasuredWithoutWeakeningIt()throws Exception{
        var encoder=new Argon2PasswordEncoder(16,32,1,65536,3);String password=Secrets.token();long began=System.nanoTime();String encoded=encoder.encode(password);double seconds=(System.nanoTime()-began)/1_000_000_000d;assertTrue(encoded.startsWith("$argon2id$v=19$m=65536,t=3,p=1$"));assertTrue(encoder.matches(password,encoded));assertFalse(encoder.matches("different synthetic password",encoded));Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/argon2-benchmark.json"),"{\"memoryKiB\":65536,\"iterations\":3,\"parallelism\":1,\"encodingSeconds\":"+seconds+"}");
    }
}
