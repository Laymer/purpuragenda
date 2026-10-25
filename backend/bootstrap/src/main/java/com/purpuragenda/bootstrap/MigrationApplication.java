package com.purpuragenda.bootstrap;

import com.purpuragenda.adapters.out.postgres.DatabaseMigration;
import javax.sql.DataSource;
import org.springframework.boot.*;
import org.springframework.context.annotation.*;

@SpringBootConfiguration
@Import(DatabaseConfiguration.class)
public class MigrationApplication {
    @Bean String databaseRole() { return "purpuragenda_migrator"; }
    @Bean ApplicationRunner migrate(DataSource source) { return arguments->DatabaseMigration.apply(source); }
}
