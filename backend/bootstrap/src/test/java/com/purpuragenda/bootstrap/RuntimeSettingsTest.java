package com.purpuragenda.bootstrap;

import com.purpuragenda.application.port.out.MailDelivery;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeSettingsTest {
    @TempDir Path directory;
    Map<String,String> values;
    @BeforeEach void prepare() throws Exception {
        Files.write(directory.resolve("totp"),new byte[32]);
        byte[] delivery=new byte[32];Arrays.fill(delivery,(byte)1);Files.write(directory.resolve("delivery"),delivery);
        Files.writeString(directory.resolve("password"),"synthetic-provider-value\n");
        values=new HashMap<>(Map.of("app.mode","local","app.public-origin","http://localhost:8080",
                "app.totp-key-file",directory.resolve("totp").toString(),"app.delivery-key-file",directory.resolve("delivery").toString(),
                "app.totp-key-id","totp-v1","app.delivery-key-id","delivery-v1","app.smtp-host","127.0.0.1",
                "app.smtp-port","1025","app.mail-from","synthetic@example.test"));
    }
    MockEnvironment environment(){var e=new MockEnvironment();values.forEach(e::withProperty);return e;}
    void invalid(String key,String value) {
        String previous=values.put(key,value);
        var error=assertThrows(IllegalArgumentException.class,()->RuntimeSettings.read(environment()));
        assertEquals("INVALID_RUNTIME_CONFIGURATION",error.getMessage());assertNull(error.getCause());
        if(previous==null)values.remove(key);else values.put(key,previous);
    }
    @Test void localAndTestUseSeparateKeysCaptureAndSyntheticMeetingHost() {
        for(String mode:List.of("local","test")) {
            values.put("app.mode",mode);RuntimeSettings settings=RuntimeSettings.read(environment());
            assertFalse(settings.suppressed());assertEquals("http://localhost:8080",settings.origin().toString());
            assertEquals(Set.of("zoom.us","meetings.example.invalid"),settings.meetingHosts());
            assertNotSame(settings.totp(),settings.delivery());
            assertEquals("RuntimeSettings[configuration=REDACTED]",settings.toString());
        }
    }
    @Test void restoreAlwaysSuppressesWithoutRequiringAnySmtpConfiguration() {
        values.put("app.mode","restore");values.keySet().removeIf(k->k.contains("smtp") || k.equals("app.mail-from"));
        var settings=RuntimeSettings.read(environment());assertTrue(settings.suppressed());
        assertEquals(MailDelivery.Result.SUPPRESSED,settings.mail().send(null));
        assertEquals(Set.of("zoom.us"),settings.meetingHosts());
    }
    @Test void operationalRequiresExplicitHttpsNoticeTlsAndPrivateCredentialFile() {
        values.putAll(Map.of("app.mode","operational","app.public-origin","https://bookings.example.com",
                "app.notice-version","reviewed-v1","app.smtp-host","smtp.example.com","app.smtp-security","STARTTLS_REQUIRED",
                "app.smtp-user","synthetic-operator","app.smtp-password-file",directory.resolve("password").toString()));
        var settings=RuntimeSettings.read(environment());assertFalse(settings.suppressed());
        assertEquals(Set.of("zoom.us"),settings.meetingHosts());
        assertEquals("https://bookings.example.com",settings.origin().toString());
        invalid("app.public-origin","https://LOCALHOST");invalid("app.public-origin","https://LOCALHOST.");
        invalid("app.public-origin","https://SYNTHETIC.INVALID.");invalid("app.public-origin","https://SYNTHETIC.INVALID");
        invalid("app.public-origin","https://SYNTHETIC.TEST");invalid("app.public-origin","http://bookings.example.com");invalid("app.public-origin","https://localhost");
        invalid("app.public-origin","https://bookings.example.invalid");invalid("app.public-origin","https://bookings.example.test");
        invalid("app.notice-version","local-synthetic");invalid("app.notice-version","LOCAL-SYNTHETIC");invalid("app.notice-version","");invalid("app.smtp-host","mailpit");
        invalid("app.smtp-security","NONE");invalid("app.smtp-password-file",directory.resolve("missing").toString());
    }
    @Test void malformedOriginsUnknownModesAndExternalLocalSmtpFailClosed() {
        invalid("app.mode","unknown");
        for(String origin:List.of("broken","http://user@localhost","http://localhost/path","http://localhost/?private=x",
                "http://localhost/#fragment","http://localhost:0","http://localhost:65536","https://localhost","http://outside.example.com")) invalid("app.public-origin",origin);
        values.put("app.public-origin","http://LOCALHOST/");assertEquals("http://localhost/",RuntimeSettings.read(environment()).origin().toString());
        values.put("app.public-origin","http://127.0.0.1/");assertNotNull(RuntimeSettings.read(environment()));
        invalid("app.smtp-host","smtp.example.com");invalid("app.smtp-port","invalid");invalid("app.smtp-port","0");
        invalid("app.mail-from","invalid");
    }
    @Test void missingReusedOrInvalidKeysCannotStartRuntime() throws Exception {
        invalid("app.totp-key-id","delivery-v1");invalid("app.delivery-key-file",directory.resolve("totp").toString());
        invalid("app.totp-key-file",directory.resolve("missing").toString());
        Files.write(directory.resolve("short"),new byte[1]);invalid("app.totp-key-file",directory.resolve("short").toString());
        Files.createSymbolicLink(directory.resolve("key-link"),directory.resolve("totp"));
        invalid("app.totp-key-file",directory.resolve("key-link").toString());invalid("app.delivery-key-file",directory.resolve("key-link").toString());
        values.remove("app.mode");assertThrows(IllegalArgumentException.class,()->RuntimeSettings.read(environment()));
    }
    @Test void secretFilesPreserveSpacesAndRejectControlsEmptyLargeAndLinkedInput() throws Exception {
        Path secret=directory.resolve("value");Files.writeString(secret," padded synthetic value \r\n");
        assertEquals(" padded synthetic value ",DatabaseConfiguration.secret(secret.toString()));
        for(String value:List.of("","bad\nvalue","bad\r","x".repeat(4097))) {
            Files.writeString(secret,value);var failure=assertThrows(IllegalArgumentException.class,()->DatabaseConfiguration.secret(secret.toString()));
            assertEquals("INVALID_SECRET_FILE",failure.getMessage());assertNull(failure.getCause());
        }
        Path link=directory.resolve("linked");Files.createSymbolicLink(link,secret);
        assertThrows(IllegalArgumentException.class,()->DatabaseConfiguration.secret(link.toString()));
        assertThrows(IllegalArgumentException.class,()->DatabaseConfiguration.secret(directory.toString()));
    }
    @Test void databaseRoleAndUrlAreValidatedBeforeConnection() {
        var e=new MockEnvironment().withProperty("app.database-url","jdbc:postgresql://127.0.0.1:5432/synthetic")
                .withProperty("app.database-user","purpuragenda_migrator");
        var config=new DatabaseConfiguration();
        assertThrows(IllegalArgumentException.class,()->config.dataSource(e,"purpuragenda_app"));
        e.withProperty("app.database-user","purpuragenda_app").withProperty("app.database-url","jdbc:h2:mem:synthetic");
        assertThrows(IllegalArgumentException.class,()->config.dataSource(e,"purpuragenda_app"));
    }
    @Test void unexpectedCommandCannotStartOrExposeAnyArguments() {
        for(String[] args:new String[][]{{"synthetic-private-argument"},{"migrate","extra"}}) {
            var failure=assertThrows(IllegalStateException.class,()->Purpuragenda.main(args));
            assertEquals("APPLICATION_START_FAILED",failure.getMessage());assertNull(failure.getCause());
        }
    }
    @Test void trustedProxyPeersDefaultEmptyAndAcceptOnlyExactLiteralAddresses() {
        assertEquals(Set.of(),RuntimeSettings.read(environment()).trustedProxyPeers());
        values.put("app.security.trusted-proxy-peers","");assertEquals(Set.of(),RuntimeSettings.read(environment()).trustedProxyPeers());
        values.put("app.security.trusted-proxy-peers","172.20.0.5,::1,172.20.0.5");
        assertEquals(Set.of("172.20.0.5","0:0:0:0:0:0:0:1"),RuntimeSettings.read(environment()).trustedProxyPeers());
        for(String bad:List.of(" ","172.20.0.5,","172.20.0.5, 172.20.0.6","caddy","10.0.0.0/8","*","172.20.0.5%eth0","[::1]","300.1.1.1","01.2.3.4",
                "1.1.1.1,2.2.2.2,3.3.3.3,4.4.4.4,5.5.5.5,6.6.6.6,7.7.7.7,8.8.8.8,9.9.9.9,10.0.0.1,10.0.0.2,10.0.0.3,10.0.0.4,10.0.0.5,10.0.0.6,10.0.0.7,10.0.0.8"))
            invalid("app.security.trusted-proxy-peers",bad);
    }
    @Test void canonicalOriginMatchesBrowserSerializationAndCookieNamesFollowTheMode() {
        values.put("app.public-origin","http://LOCALHOST:80/");values.put("app.mode","test");
        var local=RuntimeSettings.read(environment());assertEquals("http://localhost",local.canonicalOrigin());assertTrue(local.localCookies());
        values.put("app.public-origin","http://localhost:8080");assertEquals("http://localhost:8080",RuntimeSettings.read(environment()).canonicalOrigin());
        values.put("app.mode","restore");assertTrue(RuntimeSettings.read(environment()).localCookies());
        values.putAll(Map.of("app.mode","operational","app.public-origin","https://Bookings.Example.com:443","app.notice-version","reviewed-v1","app.smtp-host","smtp.example.com",
                "app.smtp-security","STARTTLS_REQUIRED","app.smtp-user","synthetic-operator","app.smtp-password-file",directory.resolve("password").toString()));
        var operational=RuntimeSettings.read(environment());assertEquals("https://bookings.example.com",operational.canonicalOrigin());assertFalse(operational.localCookies());
        values.put("app.public-origin","https://bookings.example.com:8443");assertEquals("https://bookings.example.com:8443",RuntimeSettings.read(environment()).canonicalOrigin());
    }
}
