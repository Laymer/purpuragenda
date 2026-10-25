package com.purpuragenda.bootstrap;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.Set;
import static com.purpuragenda.domain.DomainFailure.require;

@Component
public class StartupValidation {
    public StartupValidation(Environment environment){
        String mode=environment.getProperty("app.mode","local"),delivery=environment.getProperty("app.delivery-mode","CAPTURE"),smtp=environment.getProperty("spring.mail.host","");
        require(Set.of("local","test","operational","restore").contains(mode),"INVALID_APP_MODE");
        require(Set.of("CAPTURE","NORMAL","SUPPRESSED").contains(delivery),"INVALID_DELIVERY_MODE");
        if(mode.equals("restore"))require(delivery.equals("SUPPRESSED"),"RESTORE_MUST_SUPPRESS_DELIVERY");
        if(delivery.equals("CAPTURE"))require(Set.of("localhost","127.0.0.1","mailpit").contains(smtp),"CAPTURE_REQUIRES_LOCAL_SMTP");
        if(mode.equals("operational")){
            require(environment.getProperty("server.servlet.session.cookie.secure",Boolean.class,false),"SECURE_COOKIE_REQUIRED");
            require(environment.getProperty("server.servlet.session.cookie.name","").equals("__Host-purpuragenda"),"HOST_COOKIE_REQUIRED");
            require("https".equals(URI.create(environment.getRequiredProperty("app.public-base-url")).getScheme()),"HTTPS_REQUIRED");
            require(!environment.getProperty("app.privacy-notice-version","local-draft").equals("local-draft"),"REVIEWED_NOTICE_REQUIRED");
            require(delivery.equals("NORMAL")&&!Set.of("mailpit","localhost","127.0.0.1").contains(smtp),"OPERATIONAL_SMTP_REQUIRED");
            require(!environment.getProperty("app.owner-email","").endsWith(".test"),"OWNER_EMAIL_REQUIRED");
        }
    }
}
