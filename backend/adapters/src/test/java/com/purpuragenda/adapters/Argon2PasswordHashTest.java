package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.security.Argon2PasswordHash;
import com.purpuragenda.domain.DomainFailure;
import java.util.Arrays;
import java.util.Base64;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import com.purpuragenda.adapters.out.security.PostgresPrivateAdmin;
import com.purpuragenda.adapters.out.security.Secrets;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Argon2PasswordHashTest {
    final Argon2PasswordHash hashes=new Argon2PasswordHash();
    static final String PREFIX="$argon2id$v=19$m=65536,t=3,p=1$";
    static String fixture() { return PREFIX+Base64.getEncoder().withoutPadding().encodeToString(new byte[16])+"$"+Base64.getEncoder().withoutPadding().encodeToString(new byte[32]); }
    @Test void realArgonUsesFixedCostsUniqueSaltsAndConstantTimeVerification() {
        char[] password="synthetic unicode 😀 phrase".toCharArray(), original=password.clone();
        String first=hashes.hash(password), second=hashes.hash(password);
        assertTrue(first.startsWith(PREFIX)); assertTrue(Argon2PasswordHash.validEncoding(first)); assertFalse(first.equals(second));
        assertTrue(hashes.verify(password,first)); assertTrue(hashes.verify(password,second));
        assertFalse(hashes.verify("synthetic incorrect phrase".toCharArray(),first));
        assertTrue(Arrays.equals(original,password),"BORROWED_PASSWORD_CHANGED"); Arrays.fill(password,'\0'); Arrays.fill(original,'\0');
    }
    @Test void whitespaceAndCanonicalUnicodeAreNotSilentlyChanged() {
        char[] value="  synthetic password  ".toCharArray(); String encoded=hashes.hash(value);
        assertTrue(hashes.verify(value,encoded)); assertFalse(hashes.verify("synthetic password".toCharArray(),encoded));
        char[] decomposed=("e\u0301".repeat(15)).toCharArray(); String other=hashes.hash(decomposed);
        assertTrue(hashes.verify(decomposed,other)); assertFalse(hashes.verify(("é".repeat(15)).toCharArray(),other));
    }
    @Test void malformedOrUnboundedStoredParametersCannotDriveArgon() {
        String valid=fixture(); assertTrue(Argon2PasswordHash.validEncoding(valid));
        String[] malformed={null,"",valid.replace("65536","999999999"),valid.replace("t=3","t=4"),valid.replace("p=1","p=2"),
            valid.replace("v=19","v=16"),valid.replace("argon2id","argon2i"),valid+"=",valid.substring(0,valid.length()-1),
            valid.replace("$AAAAAAAAAAAAAAAAAAAAAA$","!AAAAAAAAAAAAAAAAAAAAAA$"),
            valid.replace("$AAAAAAAAAAAAAAAAAAAAAA$","$AAAAAAAAAAAAAAAAAAAAA!$"),
            valid.substring(0,valid.length()-1)+"!",valid.substring(0,valid.length()-1)+"B",
            valid.substring(0,PREFIX.length()+22)+"!"+valid.substring(PREFIX.length()+23),
            valid.replace("$AAAAAAAAAAAAAAAAAAAAAA$","$AAAAAAAAAAAAAAAAAAAAAB$")};
        for(String encoded:malformed) {
            assertFalse(Argon2PasswordHash.validEncoding(encoded));
            assertFalse(hashes.verify("synthetic password phrase".toCharArray(),encoded));
        }
    }
    @Test void invalidPasswordsFailClosedBeforeExpensiveHashing() {
        for(char[] value:new char[][]{null,new char[0],"short".toCharArray(),"a".repeat(257).toCharArray(),("x".repeat(15)+"\n").toCharArray()}) {
            assertThrows(DomainFailure.class,()->hashes.hash(value)); assertFalse(hashes.verify(value,fixture()));
        }
    }
    @Test void unavailableDatabaseNeverExposesItsProviderError(@TempDir Path directory) throws Exception {
        Path key=directory.resolve("totp.key"); Files.write(key,new byte[32]);
        var unavailable=new AbstractDataSource() {
            @Override public java.sql.Connection getConnection() throws SQLException { throw new SQLException("synthetic-private-provider-marker"); }
            @Override public java.sql.Connection getConnection(String user,String password) throws SQLException { return getConnection(); }
        };
        var store=new PostgresPrivateAdmin(unavailable,new Secrets(key,"totp-v1"));
        var failure=assertThrows(IllegalStateException.class,()->store.createUnenrolled("owner@example.test",fixture()));
        assertEquals("ADMIN_BOOTSTRAP_UNAVAILABLE",failure.getMessage()); assertNull(failure.getCause());
    }
    @Test void unavailableProviderConfigurationHasOnlyFixedFailureCode() {
        String key="org.bouncycastle.argon2.max_memory_exp",previous=System.getProperty(key);
        try {
            System.setProperty(key,"3");
            var failure=assertThrows(IllegalStateException.class,()->hashes.hash("synthetic password phrase".toCharArray()));
            assertEquals("PASSWORD_HASH_UNAVAILABLE",failure.getMessage()); assertNull(failure.getCause());
        } finally { if(previous==null) System.clearProperty(key); else System.setProperty(key,previous); }
    }
}
