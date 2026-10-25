package com.purpuragenda.adapters;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.logging.LogManager;
import liquibase.*;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.ui.LoggerUIService;
import org.junit.jupiter.api.*;
import com.github.dockerjava.api.model.ExposedPort;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import static org.junit.jupiter.api.Assertions.*;

/** Real production migrations and roles. Missing Docker/image/dependencies fail, never skip. */
@Timeout(180)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class MigrationTest {
    // Exact 18.6-bookworm digest; TC2.0.5 misparses combined tag@digest repository names.
    static final String IMAGE = "postgres@sha256:bbf83594798e144bff365a9c191c5d779f181fb3135902064ab8ff11918020fc";
    static final String MIGRATOR="purpuragenda_migrator", APP="purpuragenda_app";
    static final String SERVICE="10000000-0000-0000-0000-000000000001";
    static final Instant START=Instant.parse("2026-11-01T10:00:00Z");
    static PostgreSQLContainer postgres;
    static String appPassword, migratorPassword, jdbcUrl;
    String schema;
    final List<String> schemas=new ArrayList<>();

    @BeforeAll static void start() {
        LogManager.getLogManager().reset(); // Never print provider errors or SQL with fixture values.
        appPassword=HexFormat.of().formatHex(SecureRandom.getSeed(32));
        migratorPassword=HexFormat.of().formatHex(SecureRandom.getSeed(32));
        String phase="IMAGE_CONFIGURATION";
        try {
            postgres=new PostgreSQLContainer(IMAGE).withDatabaseName("purpuragenda_test").withUsername("postgres")
                    .withPassword(HexFormat.of().formatHex(SecureRandom.getSeed(32)))
                    .withCommand("postgres","-c","fsync=on");
            phase="CONTAINER_START"; postgres.start();
            jdbcUrl=postgres.getJdbcUrl();
            phase="ROLE_FILES";
            postgres.execInContainer("mkdir","-p","/run/secrets");
            postgres.copyFileToContainer(Transferable.of(appPassword),"/run/secrets/app_password");
            postgres.copyFileToContainer(Transferable.of(migratorPassword),"/run/secrets/migrator_password");
            Path script=Path.of(System.getProperty("basedir"),"../../ops/docker/init-database.sh").normalize();
            postgres.copyFileToContainer(MountableFile.forHostPath(script),"/tmp/init-database.sh");
            phase="ROLE_BOOTSTRAP";
            assertEquals(0,postgres.execInContainer("bash","/tmp/init-database.sh").getExitCode(),"ROLE_BOOTSTRAP_FAILED");
        } catch(Exception failure) {
            throw new AssertionError("POSTGRES_TEST_START_FAILED:"+phase+":"+failure.getClass().getSimpleName());
        }
    }
    @AfterAll static void stop() { if(postgres!=null) postgres.stop(); }
    @BeforeEach void fresh() { newSchema(); migrate(0); }
    @AfterEach void cleanup() {
        try(Connection c=connection("postgres")) {
            for(String name:schemas) execute(c,"DROP SCHEMA "+name+" CASCADE");
        } catch(SQLException failure) { throw safe(failure); }
    }
    void newSchema() {
        schema="migration_"+UUID.randomUUID().toString().replace("-",""); schemas.add(schema);
        try(Connection c=connection("postgres")) {
            execute(c,"CREATE SCHEMA "+schema+" AUTHORIZATION "+MIGRATOR);
            execute(c,"GRANT USAGE ON SCHEMA "+schema+" TO "+APP);
        } catch(SQLException failure) { throw safe(failure); }
        try(Connection c=connection(MIGRATOR)) {
            execute(c,"ALTER DEFAULT PRIVILEGES IN SCHEMA "+schema+" GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO "+APP);
            execute(c,"ALTER DEFAULT PRIVILEGES IN SCHEMA "+schema+" GRANT USAGE,SELECT ON SEQUENCES TO "+APP);
        } catch(SQLException failure) { throw safe(failure); }
    }
    Connection connection(String role) {
        Properties properties=new Properties(); properties.setProperty("user",role);
        properties.setProperty("password",role.equals(APP)?appPassword:role.equals(MIGRATOR)?migratorPassword:postgres.getPassword());
        properties.setProperty("currentSchema",schema==null?"public":schema+",public");
        try { return DriverManager.getConnection(jdbcUrl,properties); }
        catch(SQLException failure) { throw safe(failure); }
    }
    void migrate(int count) {
        try {
            Scope.child(Map.of(Scope.Attr.ui.name(),new LoggerUIService()),()->{
                try(Connection c=connection(MIGRATOR)) {
                    var database=DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
                    database.setDefaultSchemaName(schema);
                    try(var migration=new Liquibase("db/changelog/db.changelog-master.yaml",new ClassLoaderResourceAccessor(),database)) {
                        if(count==0) migration.update(new Contexts(),new LabelExpression());
                        else migration.update(count,new Contexts(),new LabelExpression());
                    }
                }
            });
        } catch(Exception failure) { throw new AssertionError("MIGRATION_FAILED"); }
    }
    static AssertionError safe(SQLException failure) { return new AssertionError("SQLSTATE="+failure.getSQLState()); }
    static void execute(Connection c,String sql,Object... values) {
        try(var statement=c.prepareStatement(sql)) {
            for(int i=0;i<values.length;i++) statement.setObject(i+1,values[i] instanceof Instant time?Timestamp.from(time):values[i]);
            statement.execute();
        } catch(SQLException failure) { throw safe(failure); }
    }
    long scalar(String role,String sql) {
        try(Connection c=connection(role); var statement=c.createStatement(); var result=statement.executeQuery(sql)) {
            assertTrue(result.next()); return result.getLong(1);
        } catch(SQLException failure) { throw safe(failure); }
    }
    void denied(String state,String sql,Object... values) {
        try(Connection c=connection(APP)) {
            AssertionError failure=assertThrows(AssertionError.class,()->{execute(c,sql,values); execute(c,"SET CONSTRAINTS ALL IMMEDIATE");});
            assertEquals("SQLSTATE="+state,failure.getMessage());
        } catch(SQLException failure) { throw safe(failure); }
    }
    static String snapshot() {
        return """
            {"service":{"id":"%s","version":1,"name":"Synthetic service","durationMinutes":30,
            "price":{"minor":1500,"currency":"EUR"},"active":true},
            "policy":{"version":1,"gridMinutes":30,"dailyCapacity":3,"minimumNotice":"PT24H",
            "pendingLifetime":"PT24H","expiryBuffer":"PT1H","customerCutoff":"PT24H"},
            "intake":{"firstName":"Alex","lastName":"Synthetic","telephone":"+32400123456",
            "birthDate":"1990-01-01","birthTime":"10:30","birthplace":"Synthetic city","message":""},
            "creationEmail":"synthetic@example.test","displayZone":"Europe/Brussels","privacyNoticeVersion":"test"}
            """.formatted(SERVICE);
    }
    UUID booking(Instant start,String state,String document) {
        return booking(start,state,document,"ADMINISTRATOR");
    }
    UUID booking(Instant start,String state,String document,String origin) {
        UUID id=UUID.randomUUID(), customer=UUID.randomUUID();
        boolean confirmed=Set.of("CONFIRMED","COMPLETED","NO_SHOW").contains(state);
        boolean occupied=confirmed || state.equals("PENDING_CONFIRMATION");
        String meeting=Long.toString(100000000000L+Math.floorMod(id.getLeastSignificantBits(),800000000000L));
        try(Connection c=connection(APP)) {
            c.setAutoCommit(false);
            execute(c,"INSERT INTO customer_identity VALUES(?,?,?::timestamptz)",customer,customer+"@example.test","2026-10-01T00:00:00Z");
            execute(c,"""
                INSERT INTO booking(id,reference,customer_id,service_id,state,origin,created_at,start_at,
                  session_minutes,session_end,reserved_end,business_date,expires_at,expiry_ceiling,delivery_email,
                  meeting_id,meeting_url,confirmation_basis,version,calendar_revision,previously_confirmed)
                VALUES(?,?,?,?::uuid,?,?,'2026-10-01T00:00:00Z',?,30,?,?,
                  (?::timestamptz AT TIME ZONE 'Europe/Brussels')::date,
                  '2026-10-02T00:00:00Z','2026-10-02T00:00:00Z','synthetic@example.test',?,?,?,1,0,?)
                """,id,"synthetic-"+id,customer,SERVICE,state,origin,start,start.plusSeconds(1800),start.plusSeconds(7200),start,
                    confirmed?meeting:null,confirmed?"https://zoom.us/j/"+meeting:null,confirmed?"MANUAL_APPROVAL":null,confirmed);
            execute(c,"INSERT INTO booking_snapshot VALUES(?,?::jsonb)",id,document);
            execute(c,"INSERT INTO booking_occupancy VALUES(?,1,tstzrange(?,?,'[)'),?::timestamptz)",id,start,start.plusSeconds(7200),occupied?null:"2026-10-02T00:00:00Z");
            c.commit(); return id;
        } catch(SQLException failure) { throw safe(failure); }
    }

    @Test void freshSchemaHasAllChangesAndSeeds() {
        assertEquals(180006,scalar(APP,"SELECT current_setting('server_version_num')::integer"));
        assertEquals(1,scalar(APP,"SELECT (current_setting('fsync')='on')::integer"));
        assertEquals(11,scalar(MIGRATOR,"SELECT count(*) FROM databasechangelog"));
        assertEquals(2,scalar(APP,"SELECT count(*) FROM service"));
        assertEquals(1500,scalar(APP,"SELECT price_minor FROM service WHERE duration_minutes=30"));
        assertEquals(3000,scalar(APP,"SELECT price_minor FROM service WHERE duration_minutes=60"));
    }
    @Test void upgradeAndRepeatedMigrationPreserveEditedSeedsAndHistory() {
        newSchema(); migrate(6);
        UUID id=booking(START,"PENDING_CONFIRMATION",snapshot());
        try(Connection c=connection(APP)) { execute(c,"UPDATE service SET name='Synthetic owner edit' WHERE id=?::uuid",SERVICE); }
        catch(SQLException failure) { throw safe(failure); }
        migrate(0); migrate(0);
        assertEquals(11,scalar(MIGRATOR,"SELECT count(*) FROM databasechangelog"));
        assertEquals(1,scalar(APP,"SELECT count(*) FROM service WHERE name='Synthetic owner edit'"));
        assertEquals(1,scalar(APP,"SELECT count(*) FROM booking WHERE id='"+id+"'"));
    }
    @Test void upgradeFailsInsteadOfCertifyingInconsistentAppliedHistory() {
        newSchema(); migrate(6);
        booking(START,"PENDING_CONFIRMATION","{}"); // Reproduces the historical NULL-comparison bypass.
        assertEquals("MIGRATION_FAILED",assertThrows(AssertionError.class,()->migrate(0)).getMessage());
        assertEquals(6,scalar(MIGRATOR,"SELECT count(*) FROM databasechangelog"));
    }
    @Test void pendingConfirmedAndOutcomesRetainAbsoluteOverlapBarrier() {
        for(String state:List.of("PENDING_CONFIRMATION","CONFIRMED","COMPLETED","NO_SHOW")) {
            Instant time=START.plusSeconds(86400L*List.of("PENDING_CONFIRMATION","CONFIRMED","COMPLETED","NO_SHOW").indexOf(state));
            booking(time,state,snapshot());
            AssertionError failure=assertThrows(AssertionError.class,()->booking(time.plusSeconds(3600),"CONFIRMED",snapshot()));
            assertEquals("SQLSTATE=23P01",failure.getMessage());
            booking(time.plusSeconds(7200),"PENDING_CONFIRMATION",snapshot());
        }
    }
    @Test void terminalReleaseAllowsReuseButCannotDeleteOrReassignOccupancy() {
        UUID id=booking(START,"PENDING_CONFIRMATION",snapshot());
        denied("23514","DELETE FROM booking_occupancy WHERE booking_id=?",id);
        denied("23514","UPDATE booking_occupancy SET booking_id=? WHERE booking_id=?",UUID.randomUUID(),id);
        try(Connection c=connection(APP)) {
            c.setAutoCommit(false);
            execute(c,"UPDATE booking SET state='CANCELLED' WHERE id=?",id);
            execute(c,"UPDATE booking_occupancy SET released_at='2026-10-02T00:00:00Z' WHERE booking_id=?",id); c.commit();
        } catch(SQLException failure) { throw safe(failure); }
        booking(START,"PENDING_CONFIRMATION",snapshot());
    }
    @Test void missingAndMalformedSnapshotsCannotEvadeNullSensitiveChecks() {
        for(String invalid:List.of("{}",snapshot().replace("\"durationMinutes\":30","\"durationMinutes\":null"),
                snapshot().replace("\"PT24H\"","null"),snapshot().replace("\"durationMinutes\":30","\"durationMinutes\":60"))) {
            assertEquals("SQLSTATE=23514",assertThrows(AssertionError.class,()->booking(START,"PENDING_CONFIRMATION",invalid)).getMessage());
        }
        booking(START,"PENDING_CONFIRMATION",snapshot().replace("\"PT24H\"","86400").replace("\"PT1H\"","3600"));
    }
    @Test void finiteTimesExactDurationsDatesAndPendingCeilingsAreMandatory() {
        UUID id=booking(START,"PENDING_CONFIRMATION",snapshot());
        for(String change:List.of("expiry_ceiling=NULL","expiry_ceiling=expiry_ceiling+interval '1 second'",
                "expires_at=expiry_ceiling+interval '1 microsecond'","session_end=session_end+interval '1 minute'",
                "reserved_end=reserved_end+interval '1 minute'","business_date=business_date+1",
                "start_at='infinity'","previously_confirmed=true")) {
            denied("23514","UPDATE booking SET "+change+" WHERE id=?",id);
        }
        denied("23514","UPDATE booking_occupancy SET occupied=tstzrange(?,?,'[]') WHERE booking_id=?",START,START.plusSeconds(7200),id);
        denied("23514","UPDATE booking_occupancy SET occupied='empty' WHERE booking_id=?",id);
    }
    @Test void elapsedSnapshotDaysRemainTwentyFourHoursAcrossAutumnDst() {
        try(Connection c=connection(APP)) {
            execute(c,"SET TIME ZONE 'Europe/Brussels'");
            try(var statement=c.createStatement(); var result=statement.executeQuery("""
                SELECT extract(epoch FROM (TIMESTAMPTZ '2026-10-24 12:00:00Z'+snapshot_elapsed('"P1D"'::jsonb))
                  -TIMESTAMPTZ '2026-10-24 12:00:00Z')
                """)) {
                assertTrue(result.next()); assertEquals(86400,result.getLong(1));
            }
        } catch(SQLException failure) { throw safe(failure); }
    }
    @Test void actualMoneyIsIndependentAndFactsRemainImmutable() {
        UUID id=booking(START,"PENDING_CONFIRMATION",snapshot(),"CUSTOMER"), receipt=UUID.randomUUID();
        try(Connection c=connection(APP)) {
            c.setAutoCommit(false);
            execute(c,"INSERT INTO payment_entry VALUES(?,?,'RECEIPT',?,'EUR','BANK','synthetic-reference','2026-10-01T00:00:00Z',NULL)",receipt,id,Long.MAX_VALUE);
            execute(c,"INSERT INTO request_charge VALUES(?,?,DATE '2026-10-01','CUSTOMER')",id,scalarCustomer(id));
            execute(c,"INSERT INTO customer_request_day VALUES(?,DATE '2026-10-01',1)",scalarCustomer(id));
            execute(c,"INSERT INTO audit_event(id,booking_id,actor_kind,type,occurred_at) VALUES(?,?,'ADMINISTRATOR','SYNTHETIC','2026-10-01T00:00:00Z')",UUID.randomUUID(),id);
            c.commit();
        } catch(SQLException failure) { throw safe(failure); }
        assertEquals(1,scalar(APP,"SELECT count FROM customer_request_day"));
        for(String table:List.of("payment_entry","request_charge","booking_snapshot","policy_version","audit_event")) {
            denied("23514","DELETE FROM "+table);
        }
        denied("23514","UPDATE payment_entry SET amount_minor=1 WHERE id=?",receipt);
        denied("23514","UPDATE booking_snapshot SET document='{}' WHERE booking_id=?",id);
        denied("23503","DELETE FROM customer_identity");
        denied("23514","UPDATE service SET price_minor=100000001 WHERE id=?::uuid",SERVICE);
    }
    UUID scalarCustomer(UUID id) {
        try(Connection c=connection(APP); var statement=c.prepareStatement("SELECT customer_id FROM booking WHERE id=?")) {
            statement.setObject(1,id); try(var result=statement.executeQuery()) { assertTrue(result.next()); return result.getObject(1,UUID.class); }
        } catch(SQLException failure) { throw safe(failure); }
    }
    @Test void appCannotMigrateOrReadMetadataAndFutureTablesReceiveDmlGrants() {
        denied("42501","CREATE TABLE forbidden_table(id integer)");
        denied("42501","ALTER TABLE service ADD COLUMN forbidden integer");
        denied("42501","SELECT * FROM databasechangelog");
        denied("42501","UPDATE databasechangeloglock SET locked=false");
        try(Connection c=connection(MIGRATOR)) { execute(c,"CREATE TABLE future_table(id integer)"); }
        catch(SQLException failure) { throw safe(failure); }
        try(Connection c=connection(APP)) { execute(c,"INSERT INTO future_table VALUES(1)"); }
        catch(SQLException failure) { throw safe(failure); }
        assertEquals(1,scalar(APP,"SELECT count(*) FROM future_table"));
        // Exercise the production bootstrap's public-schema default grants too.
        String table="public.future_"+schema;
        try(Connection c=connection(MIGRATOR)) { execute(c,"CREATE TABLE "+table+"(id integer)"); }
        catch(SQLException failure) { throw safe(failure); }
        try(Connection c=connection(APP)) { execute(c,"INSERT INTO "+table+" VALUES(1)"); }
        catch(SQLException failure) { throw safe(failure); }
        assertEquals(1,scalar(APP,"SELECT count(*) FROM "+table));
        assertEquals(0,scalar(APP,"SELECT count(*) FROM pg_roles WHERE rolname=current_user AND (rolsuper OR rolcreaterole OR rolcreatedb)"));
    }
    @Test void dataAndMigrationHistorySurviveReconnectAndContainerRestart() {
        booking(START,"PENDING_CONFIRMATION",snapshot());
        assertEquals(1,scalar(APP,"SELECT count(*) FROM booking"));
        postgres.getDockerClient().restartContainerCmd(postgres.getContainerId()).exec();
        // Docker may allocate a new ephemeral host port. TC's initial inspect data is cached.
        var inspect=postgres.getDockerClient().inspectContainerCmd(postgres.getContainerId()).exec();
        assertTrue(Boolean.TRUE.equals(inspect.getState().getRunning()),"POSTGRES_RESTART_CONTAINER_STOPPED");
        var bindings=inspect.getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(5432));
        assertNotNull(bindings,"POSTGRES_RESTART_PORT_MISSING");
        assertTrue(bindings.length>0,"POSTGRES_RESTART_PORT_MISSING");
        jdbcUrl="jdbc:postgresql://"+postgres.getHost()+":"+bindings[0].getHostPortSpec()+"/purpuragenda_test";
        long deadline=System.nanoTime()+Duration.ofSeconds(45).toNanos();
        boolean ready=false;
        while(System.nanoTime()<deadline) {
            try(Connection ignored=DriverManager.getConnection(jdbcUrl,postgres.getUsername(),postgres.getPassword())) { ready=true; break; }
            catch(SQLException unavailable) { try { Thread.sleep(100); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError("RESTART_INTERRUPTED"); } }
        }
        assertTrue(ready,"POSTGRES_RESTART_FAILED");
        assertEquals(1,scalar(APP,"SELECT count(*) FROM booking"));
        assertEquals(11,scalar(MIGRATOR,"SELECT count(*) FROM databasechangelog"));
    }
}
