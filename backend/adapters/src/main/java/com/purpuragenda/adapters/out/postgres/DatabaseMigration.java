package com.purpuragenda.adapters.out.postgres;

import java.util.Map;
import javax.sql.DataSource;
import liquibase.*;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.ui.LoggerUIService;

/** Operator-only composition uses the migration role; the serving process never calls this. */
public final class DatabaseMigration {
    private DatabaseMigration() { }
    public static void apply(DataSource source) {
        try {
            Scope.child(Map.of(Scope.Attr.ui.name(),new LoggerUIService()),()-> {
                try(var connection=source.getConnection()) {
                    var database=DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
                    try(var migration=new Liquibase("db/changelog/db.changelog-master.yaml",new ClassLoaderResourceAccessor(),database)) {
                        migration.update(new Contexts(),new LabelExpression());
                    }
                }
            });
        } catch(Exception failure) { throw new IllegalStateException("MIGRATION_FAILED"); }
    }
}
