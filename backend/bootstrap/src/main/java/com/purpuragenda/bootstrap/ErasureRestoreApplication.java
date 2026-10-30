package com.purpuragenda.bootstrap;

import javax.sql.DataSource;
import org.springframework.boot.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** Private fail-closed restore replay; leaves maintenance and delivery suppression enabled. */
@SpringBootConfiguration @Import(DatabaseConfiguration.class)
public class ErasureRestoreApplication {
    @Bean String databaseRole(){return "purpuragenda_app";}
    @Bean ApplicationRunner replay(DataSource source,Environment environment){return args->{
        if(!"restore".equals(environment.getRequiredProperty("app.mode")))throw new IllegalStateException("RESTORE_MODE_REQUIRED");
        var settings=RuntimeSettings.readForOperator(environment);
        var journal=new com.purpuragenda.adapters.out.postgres.EncryptedErasureJournal(java.nio.file.Path.of(environment.getRequiredProperty("app.privacy-journal-directory")),settings.delivery());
        var store=new com.purpuragenda.adapters.out.postgres.PostgresCustomerErasure(source,settings.delivery(),journal,settings.meetingHosts());
        if(args.getSourceArgs()[0].equals("erasure-reconcile")){
            var console=System.console();if(console==null||!console.isTerminal())throw new IllegalStateException("PRIVATE_TERMINAL_REQUIRED");
            var id=java.util.UUID.fromString(console.readLine("Erasure request UUID: "));
            console.writer().println(store.reconciliationEvidence(id));console.writer().flush();
            String digest=console.readLine("Exact journal digest after independent fact reconciliation: ");
            String reason=console.readLine("Reconciliation reason (10-500 characters; no personal details): ");
            store.reconcile(id,digest,reason);
        }
        store.recover(true);
    };}
}
