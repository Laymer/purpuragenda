package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.security.AdminCrypto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdminCryptoTest {
    @Test void recoveryCodesUseFullEntropyCanonicalAlphabetAndSeparateSha256Digests() throws Exception {
        var crypto=new AdminCrypto();Set<String> hashes=new HashSet<>();
        for(int i=0;i<10;i++)try(var code=crypto.recoveryCode()) {
            char[] value=code.value();assertEquals(43,value.length);byte[] ascii=new byte[value.length];
            for(int j=0;j<value.length;j++) { assertTrue(Character.isLetterOrDigit(value[j]) || value[j]=='-' || value[j]=='_');ascii[j]=(byte)value[j]; }
            assertEquals(32,Base64.getUrlDecoder().decode(ascii).length);
            assertTrue(code.hash().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ascii))));
            assertTrue(crypto.digest(value).orElseThrow().equals(code.hash()));
            assertTrue(hashes.add(code.hash()));assertEquals("AdminRecoveryCode[REDACTED]",code.toString());
            Arrays.fill(value,'\0');Arrays.fill(ascii,(byte)0);code.close();assertTrue(Arrays.equals(new char[43],code.value()));
        }
    }
    @Test void totpAdapterDelegatesStrictInvalidInputAndReplayPolicy() {
        var crypto=new AdminCrypto();assertEquals(-1,crypto.acceptedStep(new byte[20],"invalid",Instant.EPOCH,-1));
    }
    @Test void recoveryDigestRejectsMalformedOrNonAsciiInputsWithoutChangingCallerBuffer() {
        var crypto=new AdminCrypto();assertTrue(crypto.digest(null).isEmpty());assertTrue(crypto.digest(new char[42]).isEmpty());
        for(char invalid:new char[]{'@','[','`','{','/',';',':','é','\0'}) {
            char[] value="a".repeat(43).toCharArray();value[20]=invalid;
            assertTrue(crypto.digest(value).isEmpty());assertTrue(value[20]==invalid);
        }
        assertTrue(crypto.digest(("AZaz09_-".repeat(5)+"abc").toCharArray()).isPresent());
    }
}
