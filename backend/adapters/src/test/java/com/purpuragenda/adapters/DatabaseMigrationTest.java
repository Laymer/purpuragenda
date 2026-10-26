package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.postgres.*;
import org.springframework.boot.health.contributor.Status;
import java.sql.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.AbstractDataSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
@org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class DatabaseMigrationTest {
    MigrationTest fixture;
    @BeforeAll static void start(){MigrationTest.start();}
    @AfterAll static void stop(){MigrationTest.stop();}
    @BeforeEach void prepare(){fixture=new MigrationTest();fixture.newSchema();}
    @AfterEach void cleanup(){fixture.cleanup();}
    DataSource source(String role) { return new AbstractDataSource() {
        public Connection getConnection(){return fixture.connection(role);}
        public Connection getConnection(String user,String password){throw new UnsupportedOperationException();}
    }; }
    @Test void separateOperatorRunsProductionMigrationsAndRerunsWithoutDuplicateSeeds() {
        DatabaseMigration.apply(source(MigrationTest.MIGRATOR));
        assertEquals(2,fixture.scalar(MigrationTest.APP,"SELECT count(*) FROM service"));
        assertEquals(1,fixture.scalar(MigrationTest.APP,"SELECT count(*) FROM scheduling_resource"));
        DatabaseMigration.apply(source(MigrationTest.MIGRATOR));
        assertEquals(2,fixture.scalar(MigrationTest.APP,"SELECT count(*) FROM service"));
    }
    @Test void applicationRoleCannotRunMigrationsAndFailureHasNoProviderCause() {
        DatabaseMigration.apply(source(MigrationTest.MIGRATOR));
        var failure=assertThrows(IllegalStateException.class,()->DatabaseMigration.apply(source(MigrationTest.APP)));
        assertEquals("MIGRATION_FAILED",failure.getMessage());assertNull(failure.getCause());
        assertEquals(2,fixture.scalar(MigrationTest.APP,"SELECT count(*) FROM service"));
    }
    @Test void unexpectedConnectionFailureIsClosedAndCannotLeakProviderText() {
        DataSource broken=new AbstractDataSource() {
            public Connection getConnection() throws SQLException {throw new SQLException("synthetic-private-diagnostic");}
            public Connection getConnection(String user,String password) throws SQLException {return getConnection();}
        };
        var failure=assertThrows(IllegalStateException.class,()->DatabaseMigration.apply(broken));
        assertEquals("MIGRATION_FAILED",failure.getMessage());assertNull(failure.getCause());
    }
    @Test void readinessRequiresSchemaSeedsValidSettingsAndNoMaintenance() throws Exception {
        DataSource app=source(MigrationTest.APP);
        var readiness=new DatabaseReadiness(app,new PostgresScheduling(app));
        assertEquals(Status.DOWN,readiness.health().getStatus());
        fixture.migrate(9); // Earlier scheduling schema lacks the current recovery/guest authority columns.
        assertEquals(Status.DOWN,readiness.health().getStatus());
        fixture.migrate(2); // Recovery/guest grants exist, but session/proof migration 012 is still absent.
        assertEquals(11,fixture.scalar(MigrationTest.MIGRATOR,"SELECT count(*) FROM databasechangelog"));
        assertEquals(Status.DOWN,readiness.health().getStatus());
        fixture.migrate(1); // Sessions exist, but throttle migration 013 is still absent.
        assertEquals(12,fixture.scalar(MigrationTest.MIGRATOR,"SELECT count(*) FROM databasechangelog"));
        assertEquals(Status.DOWN,readiness.health().getStatus());
        DatabaseMigration.apply(source(MigrationTest.MIGRATOR));
        assertEquals(Status.UP,readiness.health().getStatus());
        try(var connection=fixture.connection(MigrationTest.APP)) {
            MigrationTest.execute(connection,"UPDATE operation_control SET maintenance=true WHERE id=1");
            assertEquals(Status.DOWN,readiness.health().getStatus());
            MigrationTest.execute(connection,"UPDATE operation_control SET maintenance=false WHERE id=1");
            MigrationTest.execute(connection,"UPDATE business_settings SET calendar='{}'::jsonb WHERE id=1");
            assertEquals(Status.DOWN,readiness.health().getStatus());
            MigrationTest.execute(connection,"DELETE FROM operation_control WHERE id=1");
            assertEquals(Status.DOWN,readiness.health().getStatus());
            assertTrue(readiness.health().getDetails().isEmpty());
        }
    }
    @Test void readinessNullDependenciesAndProviderFailureNeverProduceDetails() {
        DataSource broken=new AbstractDataSource() {
            public Connection getConnection() throws SQLException {throw new SQLException("synthetic-private-diagnostic");}
            public Connection getConnection(String user,String password) throws SQLException {return getConnection();}
        };
        assertThrows(NullPointerException.class,()->new DatabaseReadiness(null,new PostgresScheduling(broken)));
        assertThrows(NullPointerException.class,()->new DatabaseReadiness(broken,null));
        var result=new DatabaseReadiness(broken,new PostgresScheduling(broken)).health();
        assertEquals(Status.DOWN,result.getStatus());assertTrue(result.getDetails().isEmpty());
    }

}
