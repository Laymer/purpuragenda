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
            properties=new HashMap<>(Map.of("app.database-url",postgres.getJdbcUrl().split("\\?")[0],"app.database-user","purpuragenda_app",
                    "app.database-password-file",directory.resolve("app").toString(),"app.mode","local",
                    "app.public-origin","http://localhost:8080","app.totp-key-file",directory.resolve("totp").toString(),
                    "app.delivery-key-file",directory.resolve("delivery").toString(),"app.totp-key-id","totp-test-v1",
                    "app.delivery-key-id","delivery-test-v1","server.port","0"));
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
            var pool=context.getBean(com.zaxxer.hikari.HikariDataSource.class);
            pool.setMinimumIdle(0);pool.setMaximumPoolSize(1);pool.getHikariPoolMXBean().softEvictConnections();
            var readiness=context.getBean(com.purpuragenda.adapters.out.postgres.DatabaseReadiness.class);
            assertEquals(org.springframework.boot.health.contributor.Status.UP,readiness.health().getStatus());
            assertEquals(0,pool.getHikariPoolMXBean().getActiveConnections());
        }
    }

}
