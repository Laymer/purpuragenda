package com.purpuragenda.bootstrap;

import java.util.Map;
import java.util.logging.LogManager;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;

public final class Purpuragenda {
    private Purpuragenda() { }
    public static void main(String[] arguments) { launch(arguments,Map.of()); }
    static ConfigurableApplicationContext launch(String[] arguments,Map<String,Object> properties) {
        // Neither migration tools nor framework startup failures may print private provider diagnostics.
        LogManager.getLogManager().reset();
        try {
            if(arguments.length>1 || arguments.length==1 && !arguments[0].equals("migrate"))
                throw new IllegalArgumentException();
            boolean migration=arguments.length==1;
            var application=new SpringApplication(migration?MigrationApplication.class:RuntimeApplication.class);
            application.setDefaultProperties(properties);
            application.setWebApplicationType(migration?WebApplicationType.NONE:WebApplicationType.SERVLET);
            application.setBannerMode(Banner.Mode.OFF);
            var context=application.run();
            if(migration) context.close();
            return context;
        } catch(RuntimeException failure) { throw new IllegalStateException("APPLICATION_START_FAILED"); }
    }
}
