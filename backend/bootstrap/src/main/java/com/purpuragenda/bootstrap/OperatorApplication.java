package com.purpuragenda.bootstrap;

import com.purpuragenda.adapters.out.security.*;
import com.purpuragenda.application.PrivateAdminBootstrap;
import com.purpuragenda.application.PrivateAdminMfa;
import java.io.Console;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** Non-web private operator composition; it exposes no endpoint and runs only the interactive workflow. */
@SpringBootConfiguration
@Import(DatabaseConfiguration.class)
public class OperatorApplication {
    @Bean String databaseRole() { return "purpuragenda_app"; }
    @Bean RuntimeSettings settings(Environment environment) { return RuntimeSettings.read(environment); }
    @Bean ApplicationRunner adminSetup(DataSource source, RuntimeSettings settings) {
        return arguments -> {
            var hash = new Argon2PasswordHash();
            var setup = new AdminSetup(new PrivateAdminBootstrap(hash, new PostgresPrivateAdmin(source, settings.totp())),
                    new PrivateAdminMfa(new PostgresAdminMfa(source, settings.totp()), hash, new AdminCrypto()), console());
            if (setup.run() != 0) throw new IllegalStateException("ADMIN_SETUP_INCOMPLETE");
        };
    }

    static AdminSetup.Terminal console() {
        Console console = System.console();
        return new AdminSetup.Terminal() {
            @Override public boolean interactive() { return console != null && console.isTerminal(); }
            @Override public String line(String prompt) { return console.readLine("%s", prompt); }
            @Override public char[] secret(String prompt) { return console.readPassword("%s", prompt); }
            @Override public void show(String text) { console.writer().println(text); console.writer().flush(); }
        };
    }
}
