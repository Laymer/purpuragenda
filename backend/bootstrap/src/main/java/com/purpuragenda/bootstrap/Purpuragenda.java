package com.purpuragenda.bootstrap;

import java.util.Map;
import java.util.logging.LogManager;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;

public final class Purpuragenda {
    private Purpuragenda() { }
    public static void main(String[] arguments) {
        if(arguments.length==1 && (arguments[0].equals("admin-setup")||(arguments[0].equals("erasure-replay")||arguments[0].equals("erasure-reconcile")))) {
            // The workflow reports its own fixed terminal message; exit status alone reaches the shell.
            try { launch(arguments,Map.of()).close(); } catch(IllegalStateException refused) { System.exit(1); }
            return;
        }
        launch(arguments,Map.of());
    }
    static ConfigurableApplicationContext launch(String[] arguments,Map<String,Object> properties) {
        // Neither migration tools nor framework startup failures may print private provider diagnostics.
        LogManager.getLogManager().reset();
        try {
            if(arguments.length>1 || arguments.length==1 && !arguments[0].equals("migrate") && !arguments[0].equals("admin-setup") && !(arguments[0].equals("erasure-replay")||arguments[0].equals("erasure-reconcile")))
                throw new IllegalArgumentException();
            boolean migration=arguments.length==1 && arguments[0].equals("migrate"),operator=arguments.length==1 && !migration;
            var application=new SpringApplication(operator?((arguments[0].equals("erasure-replay")||arguments[0].equals("erasure-reconcile"))?ErasureRestoreApplication.class:OperatorApplication.class):migration?MigrationApplication.class:RuntimeApplication.class);
            application.setDefaultProperties(properties);
            application.setWebApplicationType(migration||operator?WebApplicationType.NONE:WebApplicationType.SERVLET);
            application.setBannerMode(Banner.Mode.OFF);
            var context=application.run(arguments);
            if(migration||operator) context.close();
            return context;
        } catch(RuntimeException failure) { throw new IllegalStateException("APPLICATION_START_FAILED"); }
    }
}
