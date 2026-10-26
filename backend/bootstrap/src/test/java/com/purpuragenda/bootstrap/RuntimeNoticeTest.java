package com.purpuragenda.bootstrap;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeNoticeTest {
    @TempDir Path directory;
    final MockEnvironment env=new MockEnvironment();
    @Test void allNonOperationalModesUseClearlySyntheticPlaintextAndAnExplicitVersion() {
        for(String mode:List.of("local","test","restore")) {
            env.setProperty("app.mode",mode);var notice=RuntimeNotice.read(env);
            assertTrue(notice.synthetic());assertEquals("local-v1",notice.version());assertTrue(notice.text().startsWith("Synthetic local"));
        }
        env.setProperty("app.notice-version","local-v2");assertEquals("local-v2",RuntimeNotice.read(env).version());
        env.setProperty("app.mode","unknown");invalid();
    }
    @Test void operationalReadsBoundedUtf8PlaintextWithNoImplicitLocalDefault() throws Exception {
        Path file=Files.writeString(directory.resolve("notice"),"Synthetic test fixture for configuration testing.\r\nCafé <plain text>");
        env.withProperty("app.mode","operational").withProperty("app.notice-version","reviewed-v1").withProperty("app.notice-file",file.toString());
        var notice=RuntimeNotice.read(env);assertFalse(notice.synthetic());assertEquals("reviewed-v1",notice.version());
        assertTrue(notice.text().contains("\nCafé <plain text>"));
        // Content is not interpreted as HTML, and loading configuration never constitutes legal approval.
        for(String version:List.of("local-v1","LOCAL-v1","bad version","")) { env.setProperty("app.notice-version",version);invalid(); }
    }
    @Test void missingOversizedMalformedAndLinkedInputsFailWithoutPathsOrProviderErrors() throws Exception {
        env.setProperty("app.mode","operational");invalid();env.setProperty("app.notice-version","reviewed-v1");invalid();
        Path file=directory.resolve("notice");env.setProperty("app.notice-file",file.toString());invalid();
        Files.write(file,new byte[65537]);invalid();Files.write(file,new byte[]{(byte)0xc3,(byte)0x28});invalid();
        Files.writeString(file," ");invalid();Files.writeString(file,"text\u0000");invalid();
        Files.writeString(file,"valid");Path link=directory.resolve("link");Files.createSymbolicLink(link,file);env.setProperty("app.notice-file",link.toString());invalid();
        env.setProperty("app.notice-file",directory.toString());invalid();
        env.setProperty("app.notice-file","invalid\u0000path");invalid();
        assertEquals("INVALID_BOOKING_NOTICE_CONFIGURATION",assertThrows(IllegalArgumentException.class,()->RuntimeNotice.read(new MockEnvironment())).getMessage());
    }
    private void invalid() {
        var failure=assertThrows(IllegalArgumentException.class,()->RuntimeNotice.read(env));
        assertEquals("INVALID_BOOKING_NOTICE_CONFIGURATION",failure.getMessage());assertNull(failure.getCause());
    }
}
