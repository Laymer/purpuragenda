package com.purpuragenda.bootstrap;

import com.zaxxer.hikari.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
public class DatabaseConfiguration {
    @Bean(destroyMethod="close") HikariDataSource dataSource(Environment environment,@Qualifier("databaseRole") String databaseRole) {
        String url=environment.getRequiredProperty("app.database-url");
        String user=environment.getRequiredProperty("app.database-user");
        if(!url.matches("jdbc:postgresql://[A-Za-z0-9.:-]+/[A-Za-z0-9_]+")
                || !user.equals(databaseRole)) throw new IllegalArgumentException("INVALID_DATABASE_CONFIGURATION");
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername(user);
        config.setPassword(secret(environment.getRequiredProperty("app.database-password-file")));
        config.setMaximumPoolSize(12);config.setConnectionTimeout(5000);config.setValidationTimeout(2000);
        config.setInitializationFailTimeout(5000);config.setPoolName("purpuragenda");
        return new HikariDataSource(config);
    }
    static String secret(String name) {
        try {
            Path path=Path.of(name);
            if(Files.isSymbolicLink(path) || !Files.isRegularFile(path) || Files.size(path)>4096) throw new IllegalArgumentException();
            String value=Files.readString(path,StandardCharsets.UTF_8);
            if(value.endsWith("\n")) {
                value=value.substring(0,value.length()-1);
                if(value.endsWith("\r")) value=value.substring(0,value.length()-1);
            }
            if(value.isEmpty() || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            return value;
        } catch(Exception failure) { throw new IllegalArgumentException("INVALID_SECRET_FILE"); }
    }
}
