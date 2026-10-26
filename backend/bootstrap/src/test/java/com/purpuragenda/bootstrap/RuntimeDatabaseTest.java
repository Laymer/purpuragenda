package com.purpuragenda.bootstrap;

import com.purpuragenda.application.*;
import com.purpuragenda.application.port.out.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.logging.LogManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RuntimeDatabaseTest {
    @TempDir static Path directory;
    static PostgreSQLContainer postgres;
    static Map<String,Object> properties;
    @BeforeAll static void start() throws Exception {
        LogManager.getLogManager().reset();
        try {
            postgres=new PostgreSQLContainer("postgres@sha256:bbf83594798e144bff365a9c191c5d779f181fb3135902064ab8ff11918020fc")
                    .withDatabaseName("runtime_test").withUsername("postgres").withPassword(token()).withCommand("postgres","-c","fsync=on");
            postgres.start();postgres.execInContainer("mkdir","-p","/run/secrets");
            String app=token(),migrator=token();
            postgres.copyFileToContainer(Transferable.of(app),"/run/secrets/app_password");
            postgres.copyFileToContainer(Transferable.of(migrator),"/run/secrets/migrator_password");
            postgres.copyFileToContainer(MountableFile.forHostPath(Path.of("../../ops/docker/init-database.sh").toAbsolutePath().normalize()),"/tmp/init-database.sh");
            assertEquals(0,postgres.execInContainer("bash","/tmp/init-database.sh").getExitCode(),"RUNTIME_ROLE_BOOTSTRAP_FAILED");
            Files.writeString(directory.resolve("app"),app);Files.writeString(directory.resolve("migrator"),migrator);
            Files.write(directory.resolve("totp"),SecureRandom.getSeed(32));Files.write(directory.resolve("delivery"),SecureRandom.getSeed(32));
            Files.write(directory.resolve("rate"),SecureRandom.getSeed(32));
            properties=new HashMap<>(Map.of("app.database-url",postgres.getJdbcUrl().split("\\?")[0],"app.database-user","purpuragenda_app",
                    "app.database-password-file",directory.resolve("app").toString(),"app.mode","local",
                    "app.public-origin","http://localhost:8080","app.totp-key-file",directory.resolve("totp").toString(),
                    "app.delivery-key-file",directory.resolve("delivery").toString(),"app.totp-key-id","totp-test-v1",
                    "app.delivery-key-id","delivery-test-v1","server.port","0"));
            properties.putAll(Map.of("app.security.rate-key-file",directory.resolve("rate").toString(),"app.security.rate-key-id","rate-test-v1"));
            properties.putAll(Map.of("app.smtp-host","127.0.0.1","app.smtp-port","1","app.mail-from","synthetic@example.test"));
        } catch(Exception failure) { throw new AssertionError("RUNTIME_POSTGRES_START_FAILED"); }
    }
    @AfterAll static void stop(){if(postgres!=null)postgres.stop();}
    static String token(){return HexFormat.of().formatHex(SecureRandom.getSeed(32));}
    static HttpResponse<String> get(int port,String path) throws Exception {
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
        }
    }
    static int port(org.springframework.context.ConfigurableApplicationContext context) {
        return context.getEnvironment().getRequiredProperty("local.server.port",Integer.class);
    }
    static void sql(String sql) throws Exception {
        try(var c=DriverManager.getConnection(postgres.getJdbcUrl(),"postgres",postgres.getPassword());var s=c.createStatement()){s.execute(sql);}
    }
    @Test @Order(1) void unmigratedDatabaseIsNeverReady() throws Exception {
        try(var context=Purpuragenda.launch(new String[0],properties)) {
            var health=get(port(context),"/actuator/health/readiness");
            assertEquals(503,health.statusCode());assertEquals("{\"status\":\"DOWN\"}",health.body());
        }
    }
    @Test @Order(2) void separateMigrationIsNonWebAndRepeatable() throws Exception {
        var migration=new HashMap<>(properties);migration.put("app.database-user","purpuragenda_migrator");
        migration.put("app.database-password-file",directory.resolve("migrator").toString());
        for(int i=0;i<2;i++) {
            var context=Purpuragenda.launch(new String[]{"migrate"},migration);assertFalse(context.isActive());
            assertNull(context.getEnvironment().getProperty("local.server.port"));
        }
        try(var c=DriverManager.getConnection(postgres.getJdbcUrl(),"purpuragenda_app",Files.readString(directory.resolve("app")));
            var s=c.createStatement();var rows=s.executeQuery("SELECT count(*) FROM service")) {
            assertTrue(rows.next());assertEquals(2,rows.getInt(1));
            assertThrows(SQLException.class,()->s.execute("CREATE TABLE forbidden_ddl(id integer)"));
            assertThrows(SQLException.class,()->s.execute("SELECT * FROM databasechangelog"));
        }
    }
    @Test @Order(3) void runtimeComposesCurrentPortsAndExposesOnlySafeHealth() throws Exception {
        try(var context=Purpuragenda.launch(new String[0],properties)) {
            var health=get(port(context),"/actuator/health/readiness");assertEquals(200,health.statusCode());
            assertEquals("{\"status\":\"UP\"}",health.body());
            assertEquals(404,get(port(context),"/actuator/env").statusCode());
            assertEquals(404,get(port(context),"/api/v1/admin/register").statusCode());
            assertNotNull(context.getBean(PrivateAdminBootstrap.class));assertNotNull(context.getBean(PrivateAdminMfa.class));
            assertNotNull(context.getBean(PrivateAdminRecovery.class));
            assertTrue(context.getBean(GuestAccessStore.class).redeem(new GuestAccessStore.Redemption(
                    "A".repeat(43),GuestAccessStore.Kind.CREATE,"b".repeat(64))).isEmpty());
            assertEquals(AuthDeliveryWorker.Outcome.IDLE,context.getBean(AuthDeliveryWorker.class).tick());
            assertNotNull(context.getBean(SchedulingUnitOfWork.class).read(view->view.policy()));
            sql("UPDATE operation_control SET maintenance=true WHERE id=1");
            assertEquals(503,get(port(context),"/actuator/health/readiness").statusCode());
            sql("UPDATE operation_control SET maintenance=false WHERE id=1");
            assertEquals(200,get(port(context),"/actuator/health/readiness").statusCode());
            sql("DELETE FROM operation_control WHERE id=1");
            assertEquals(503,get(port(context),"/actuator/health/readiness").statusCode());
            sql("INSERT INTO operation_control(id) VALUES(1)");
        }
    }
    @Test @Order(4) void restoreCompositionSuppressesAllDeliveryAndRuntimeRejectsDdlRole() {
        var restore=new HashMap<>(properties);restore.put("app.mode","restore");
        try(var context=Purpuragenda.launch(new String[0],restore)) {
            assertEquals(MailDelivery.Result.SUPPRESSED,context.getBean(MailDelivery.class).send(null));
            assertEquals(AuthDeliveryWorker.Outcome.SUPPRESSED,context.getBean(AuthDeliveryWorker.class).tick());
        }
        var wrong=new HashMap<>(properties);wrong.put("app.database-user","purpuragenda_migrator");
        var failure=assertThrows(IllegalStateException.class,()->Purpuragenda.launch(new String[0],wrong));
        assertEquals("APPLICATION_START_FAILED",failure.getMessage());assertNull(failure.getCause());
        assertThrows(IllegalStateException.class,()->Purpuragenda.launch(new String[]{"migrate"},properties));
    }
    @Test @Order(5) void readinessReleasesProbeConnectionBeforePolicyValidation() {
        try(var context=Purpuragenda.launch(new String[0],properties)) {
            context.getBean(RuntimeWorkers.class).stop(); // Isolate the probe from scheduled users of the same pool.
            var pool=context.getBean(com.zaxxer.hikari.HikariDataSource.class);
            pool.setMinimumIdle(0);pool.setMaximumPoolSize(1);pool.getHikariPoolMXBean().softEvictConnections();
            var readiness=context.getBean(com.purpuragenda.adapters.out.postgres.DatabaseReadiness.class);
            assertEquals(org.springframework.boot.health.contributor.Status.UP,readiness.health().getStatus());
            assertEquals(0,pool.getHikariPoolMXBean().getActiveConnections());
        }
    }
    static byte[] decodeBase32(String value) {
        var out=new java.io.ByteArrayOutputStream();int buffer=0,bits=0;
        for(char c: value.toCharArray()) {
            buffer=(buffer<<5)|"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);bits+=5;
            if(bits>=8){out.write((buffer>>(bits-8))&0xff);bits-=8;buffer&=(1<<bits)-1;}
        }
        return out.toByteArray();
    }
    static long count(String query) throws Exception {
        try(var c=DriverManager.getConnection(postgres.getJdbcUrl(),"postgres",postgres.getPassword());var s=c.createStatement();var r=s.executeQuery(query)){r.next();return r.getLong(1);}
    }
    @Test @Order(6) void operatorRefusesWithoutPrivateTerminalAndCreatesNothing() throws Exception {
        sql("DELETE FROM admin_user");
        var failure=assertThrows(IllegalStateException.class,()->Purpuragenda.launch(new String[]{"admin-setup"},properties));
        assertEquals("APPLICATION_START_FAILED",failure.getMessage());assertNull(failure.getCause());
        assertEquals(0,count("SELECT count(*) FROM admin_user"));
        assertThrows(IllegalStateException.class,()->Purpuragenda.launch(new String[]{"admin-setup","extra"},properties));
    }
    @Test @Order(7) void operatorWorkflowEnrollsOnceThroughRealPrivateUseCasesAndRefusesRepeat() throws Exception {
        sql("DELETE FROM admin_user");
        String password="synthetic operator phrase "+token().substring(0,8);
        try(var context=Purpuragenda.launch(new String[0],properties)) {
            var bootstrap=context.getBean(PrivateAdminBootstrap.class);var mfa=context.getBean(PrivateAdminMfa.class);
            var wrong=new AdminSetupTest.FakeTerminal(true);
            wrong.lines.addAll(List.of("owner@example.test","000000","000000","000000","000000","000000"));
            wrong.secrets.addAll(List.of(password,password));
            assertEquals(1,new AdminSetup(bootstrap,mfa,wrong).run());
            assertEquals(1,count("SELECT count(*) FROM admin_user WHERE NOT enrolled"));
            assertEquals(0,count("SELECT count(*) FROM admin_recovery_code"));
            assertTrue(wrong.shown.getLast().contains("enrollment is incomplete"));
            sql("DELETE FROM admin_user");
            var terminal=new AdminSetupTest.FakeTerminal(true) {
                @Override public String line(String prompt) {
                    if(!prompt.startsWith("Authenticator")) return super.line(prompt);
                    String key=shown.getFirst().lines().filter(l->l.startsWith("Key: ")).findFirst().orElseThrow().substring(5);
                    try {
                        var generator=new com.eatthepath.otp.TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30),6,"HmacSHA1");
                        return String.format("%06d",generator.generateOneTimePassword(new javax.crypto.spec.SecretKeySpec(decodeBase32(key),"HmacSHA1"),java.time.Instant.now()));
                    } catch(java.security.GeneralSecurityException failure) { throw new AssertionError(); }
                }
            };
            terminal.lines.add("owner@example.test");terminal.secrets.addAll(List.of(password,password));
            assertEquals(0,new AdminSetup(bootstrap,mfa,terminal).run());
            String output=String.join("\n",terminal.shown);
            assertTrue(output.contains("otpauth://totp/Purpuragenda:owner%40example.test?secret="));
            assertEquals(10,output.lines().filter(l->l.matches("[A-Za-z0-9_-]{43}")).count());
            assertFalse(output.contains(password));
            assertEquals(1,count("SELECT count(*) FROM admin_user WHERE enrolled AND enabled"));
            assertEquals(10,count("SELECT count(*) FROM admin_recovery_code"));
            String key=terminal.shown.getFirst().lines().filter(l->l.startsWith("Key: ")).findFirst().orElseThrow().substring(5);
            String plain=Base64.getEncoder().encodeToString(decodeBase32(key));
            assertEquals(0,count("SELECT count(*) FROM admin_user WHERE position('"+plain+"' in totp_secret)>0 OR totp_secret=''"));
            var repeat=new AdminSetupTest.FakeTerminal(true);
            repeat.lines.add("other@example.test");repeat.secrets.addAll(List.of(password,password));
            assertEquals(1,new AdminSetup(bootstrap,mfa,repeat).run());
            assertEquals(List.of("An administrator already exists; nothing was changed."),repeat.shown);
            assertEquals(1,count("SELECT count(*) FROM admin_user"));
        }
    }

    static HttpResponse<String> call(int port,String method,String path,String... headers) throws Exception {
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(5))
                    .method(method,HttpRequest.BodyPublishers.noBody());
            for(int i=0;i<headers.length;i+=2)request.header(headers[i],headers[i+1]);
            return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        }
    }
    static String field(String body,String name) { return body.replaceAll(".*\"" + name + "\":\"([^\"]+)\".*","$1"); }
    static String text(String sql) throws Exception {
        try(var c=DriverManager.getConnection(postgres.getJdbcUrl(),"postgres",postgres.getPassword());var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getString(1);}
    }
    static final String ORIGIN="http://localhost:8080";
    @Test @Order(8) void sessionLifecycleOverRealHttpUsesPersistedSessionsOriginAndCsrf() throws Exception {
        sql("DELETE FROM app_session");
        try(var context=Purpuragenda.launch(new String[0],properties)) {
            int port=port(context);
            var denied=call(port,"POST","/api/v1/session/bootstrap");
            assertEquals(403,denied.statusCode());assertTrue(denied.body().contains("\"code\":\"ORIGIN_REJECTED\""));
            assertEquals("no-store",denied.headers().firstValue("Cache-Control").orElseThrow());assertEquals("0",text("SELECT count(*) FROM app_session"));
            assertEquals(403,call(port,"POST","/api/v1/session/bootstrap","Origin","http://127.0.0.1:"+port).statusCode());
            assertEquals(403,call(port,"POST","/api/v1/session/bootstrap","Origin",ORIGIN,"Sec-Fetch-Site","cross-site").statusCode());
            var boot=call(port,"POST","/api/v1/session/bootstrap","Origin",ORIGIN,"Sec-Fetch-Site","same-origin","X-Forwarded-Host","evil.example");
            assertEquals(200,boot.statusCode());assertEquals("no-store",boot.headers().firstValue("Cache-Control").orElseThrow());
            String setCookie=boot.headers().firstValue("Set-Cookie").orElseThrow();
            assertTrue(setCookie.startsWith("purpuragenda-local=") && setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Lax") && !setCookie.contains("Secure"));
            String cookie=setCookie.substring(0,setCookie.indexOf(';')),csrf=field(boot.body(),"csrfToken");
            assertEquals("1",text("SELECT count(*) FROM app_session"));assertEquals("ANONYMOUS",field(boot.body(),"role"));
            assertEquals("0",text("SELECT count(*) FROM app_session WHERE token_hash='"+cookie.substring(cookie.indexOf('=')+1)+"' OR csrf_hash='"+csrf+"'"));
            var state=call(port,"GET","/api/v1/session","Cookie",cookie);
            assertEquals(200,state.statusCode());assertFalse(state.body().contains("csrfToken"));
            assertEquals(401,call(port,"GET","/api/v1/session").statusCode());
            assertEquals(401,call(port,"POST","/api/v1/session/logout","Origin",ORIGIN,"X-CSRF-Token",csrf).statusCode());
            assertEquals(403,call(port,"POST","/api/v1/session/logout","Origin",ORIGIN,"Cookie",cookie).statusCode());
            assertEquals(403,call(port,"POST","/api/v1/session/logout","Origin","http://evil.example","Cookie",cookie,"X-CSRF-Token",csrf).statusCode());
            var again=call(port,"POST","/api/v1/session/bootstrap","Origin",ORIGIN,"Cookie",cookie);
            assertEquals(200,again.statusCode());assertTrue(again.headers().firstValue("Set-Cookie").isEmpty());
            String rotated=field(again.body(),"csrfToken");assertNotEquals(csrf,rotated);assertEquals("1",text("SELECT count(*) FROM app_session"));
            assertEquals(403,call(port,"POST","/api/v1/session/logout","Origin",ORIGIN,"Cookie",cookie,"X-CSRF-Token",csrf).statusCode());
            var out=call(port,"POST","/api/v1/session/logout","Origin",ORIGIN,"Cookie",cookie,"X-CSRF-Token",rotated);
            assertEquals(204,out.statusCode());assertTrue(out.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
            assertEquals("1",text("SELECT count(*) FROM app_session WHERE revoked_at IS NOT NULL"));
            assertEquals(401,call(port,"POST","/api/v1/session/logout","Origin",ORIGIN,"Cookie",cookie,"X-CSRF-Token",rotated).statusCode());
            assertEquals(401,call(port,"GET","/api/v1/session","Cookie",cookie).statusCode());
            assertEquals(401,call(port,"POST","/api/v1/admin/register","Origin",ORIGIN,"Cookie",cookie,"X-CSRF-Token",rotated).statusCode());
        }
    }
    @Test @Order(9) void expiredSessionsAreRefusedAndRebootstrapIssuesFreshAuthority() throws Exception {
        sql("DELETE FROM app_session");
        try(var context=Purpuragenda.launch(new String[0],properties)) {
            int port=port(context);
            var boot=call(port,"POST","/api/v1/session/bootstrap","Origin",ORIGIN);
            String cookie=boot.headers().firstValue("Set-Cookie").orElseThrow();cookie=cookie.substring(0,cookie.indexOf(';'));
            sql("UPDATE app_session SET issued_at=issued_at-interval '31 minutes',last_seen_at=last_seen_at-interval '31 minutes',absolute_expires_at=absolute_expires_at-interval '31 minutes'");
            assertEquals(401,call(port,"GET","/api/v1/session","Cookie",cookie).statusCode());
            assertEquals(401,call(port,"POST","/api/v1/session/logout","Origin",ORIGIN,"Cookie",cookie,"X-CSRF-Token",field(boot.body(),"csrfToken")).statusCode());
            var fresh=call(port,"POST","/api/v1/session/bootstrap","Origin",ORIGIN,"Cookie",cookie);
            assertEquals(200,fresh.statusCode());assertTrue(fresh.headers().firstValue("Set-Cookie").orElseThrow().startsWith("purpuragenda-local="));
            assertNotEquals(cookie,fresh.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0]);
            assertEquals("2",text("SELECT count(*) FROM app_session"));
            assertEquals(200,get(port,"/actuator/health/readiness").statusCode());
        }
    }

    @Test @Order(10) void durableAuthMailResumesAfterRestartAndRestoreNeverStartsDelivery() throws Exception {
        AuthDeliveryStore.Receipt issued;
        try(var context=Purpuragenda.launch(new String[0],properties)) {
            context.getBean(RuntimeWorkers.class).stop();
            issued=context.getBean(AuthDeliveryStore.class).issue(new AuthDeliveryStore.Issue(
                    "synthetic-worker@example.test",AuthDeliveryStore.Purpose.CREATE,null,"b".repeat(64)));
        }
        String job=" FROM notification_job WHERE id='"+issued.jobId()+"'";
        assertEquals(1,count("SELECT count(*)"+job+" AND state='QUEUED' AND encrypted_envelope IS NOT NULL"));
        try(var capture=new org.testcontainers.containers.GenericContainer<>(org.testcontainers.utility.DockerImageName.parse(
                "axllent/mailpit:v1.32.0@sha256:0790692be935ea4420d085694f7985b1f692ca6a8771ab0e0bd8ba75ac180498"))
                .withEnv("MP_QUIET","true").withExposedPorts(1025)) {
            capture.start();
            var configured=new HashMap<>(properties);configured.put("app.smtp-port",Integer.toString(capture.getMappedPort(1025)));
            try(var context=Purpuragenda.launch(new String[0],configured)) {
                assertTrue(context.getBean(RuntimeWorkers.class).isRunning());
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
                while(count("SELECT count(*)"+job+" AND state='SENT'")==0 && System.nanoTime()<deadline)Thread.sleep(25);
                assertEquals(1,count("SELECT count(*)"+job+" AND state='SENT' AND encrypted_envelope IS NULL AND attempts=1"));
            }
            try(var context=Purpuragenda.launch(new String[0],configured)) {
                assertEquals(200,get(port(context),"/actuator/health/readiness").statusCode());
                assertEquals(1,count("SELECT count(*)"+job+" AND state='SENT' AND attempts=1"));
                context.getBean(RuntimeWorkers.class).stop();
                context.getBean(AuthDeliveryStore.class).issue(new AuthDeliveryStore.Issue(
                        "synthetic-restore@example.test",AuthDeliveryStore.Purpose.CREATE,null,"c".repeat(64)));
            }
            configured.put("app.mode","restore");
            try(var context=Purpuragenda.launch(new String[0],configured)) {
                assertFalse(context.getBean(RuntimeWorkers.class).isRunning());
                assertEquals(1,count("SELECT count(*) FROM notification_job WHERE state='QUEUED' AND template='AUTH' AND attempts=0"));
            }
        }
    }
}
