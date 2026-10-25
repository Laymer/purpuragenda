package com.purpuragenda.bootstrap;

import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.usecase.*;
import com.purpuragenda.adapters.out.postgres.PostgresScheduling;
import com.purpuragenda.adapters.out.security.*;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;

@org.springframework.context.annotation.Configuration
@EnableJdbcHttpSession(maxInactiveIntervalInSeconds=1800,cleanupCron="0 0 * * * *")
public class Configuration {
    @Bean SchedulingUnitOfWork schedulingUnitOfWork(JdbcTemplate jdbc,PlatformTransactionManager manager){return new PostgresScheduling(jdbc,manager);}
    @Bean BookingService bookingService(SchedulingUnitOfWork work){return new BookingService(work);}
    @Bean Secrets secrets(@Value("${app.key-file}")String file,@Value("${app.key-id}")String id){return new Secrets(Path.of(file),id);}
    @Bean IdentityGateway identityGateway(JdbcTemplate jdbc,PlatformTransactionManager manager,Secrets secrets,@Value("${app.public-base-url}")String url){return new PostgresIdentity(jdbc,manager,secrets,url);}
    @Bean IdentityService identityService(IdentityGateway gateway){return new IdentityService(gateway);}
    @Bean CalendarWriter calendarWriter(){return new com.purpuragenda.adapters.out.calendar.IcalendarWriter();}
    @Bean CalendarService calendarService(BookingService service,CalendarWriter writer){return new CalendarService(service,writer);}
    @Bean MailDelivery mailDelivery(org.springframework.mail.javamail.JavaMailSender sender,@Value("${app.mail-from}")String from){return new com.purpuragenda.adapters.out.email.SmtpDelivery(sender,from);}
    @Bean NotificationQueue notificationQueue(JdbcTemplate jdbc,PlatformTransactionManager manager,SchedulingUnitOfWork work,Secrets secrets,CalendarWriter calendar,@Value("${app.public-base-url}")String url,@Value("${app.owner-email}")String owner,@Value("${app.delivery-mode}")String mode){return new com.purpuragenda.adapters.out.email.PostgresNotifications(jdbc,manager,work,secrets,calendar,url,owner,mode);}
    @Bean NotificationService notificationService(NotificationQueue queue,MailDelivery delivery){return new NotificationService(queue,delivery);}
    @Bean OperationsGateway operationsGateway(JdbcTemplate jdbc,PlatformTransactionManager manager,Secrets secrets){return new com.purpuragenda.adapters.out.postgres.PostgresOperations(jdbc,manager,secrets);}
    @Bean OperationsService operationsService(OperationsGateway gateway){return new OperationsService(gateway);}
    @Bean org.springframework.session.web.http.DefaultCookieSerializer cookieSerializer(@Value("${server.servlet.session.cookie.name}")String name,@Value("${server.servlet.session.cookie.secure}")boolean secure){var cookie=new org.springframework.session.web.http.DefaultCookieSerializer();cookie.setCookieName(name);cookie.setCookiePath("/");cookie.setUseSecureCookie(secure);cookie.setUseHttpOnlyCookie(true);cookie.setSameSite("Lax");cookie.setUseBase64Encoding(false);return cookie;}
}
