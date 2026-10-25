package com.purpuragenda.bootstrap;

import com.purpuragenda.adapters.out.email.*;
import com.purpuragenda.adapters.out.postgres.PostgresScheduling;
import com.purpuragenda.adapters.out.postgres.DatabaseReadiness;
import com.purpuragenda.adapters.out.security.*;
import com.purpuragenda.application.*;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.scheduling.PublicScheduling;
import javax.sql.DataSource;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import(DatabaseConfiguration.class)
public class RuntimeApplication {
    @Bean String databaseRole() { return "purpuragenda_app"; }
    @Bean DatabaseReadiness applicationSchema(DataSource source,SchedulingUnitOfWork scheduling) { return new DatabaseReadiness(source,scheduling); }
    @Bean RuntimeSettings settings(Environment environment) { return RuntimeSettings.read(environment); }
    @Bean SchedulingUnitOfWork scheduling(DataSource source,RuntimeSettings settings) {
        return new PostgresScheduling(source,settings.meetingHosts());
    }
    @Bean PublicScheduling publicScheduling(SchedulingUnitOfWork work) { return new PublicScheduling(work); }
    @Bean PrivateAdminBootstrap adminBootstrap(DataSource source,RuntimeSettings settings) {
        return new PrivateAdminBootstrap(new Argon2PasswordHash(),new PostgresPrivateAdmin(source,settings.totp()));
    }
    @Bean PrivateAdminMfa adminMfa(DataSource source,RuntimeSettings settings) {
        return new PrivateAdminMfa(new PostgresAdminMfa(source,settings.totp()),new Argon2PasswordHash(),new AdminCrypto());
    }
    @Bean PrivateAdminRecovery adminRecovery(DataSource source,RuntimeSettings settings) {
        return new PrivateAdminRecovery(new PostgresAdminRecovery(source,settings.totp()),new Argon2PasswordHash(),new AdminCrypto());
    }
    @Bean GuestAccessStore guestAccess(DataSource source) { return new PostgresGuestAccess(source); }
    @Bean NotificationQueue notificationQueue(DataSource source,RuntimeSettings settings) { return new PostgresNotificationQueue(source,settings.suppressed()); }
    @Bean AuthDeliveryStore authDeliveryStore(DataSource source,RuntimeSettings settings) {
        return new PostgresAuthDeliveryStore(source,settings.delivery(),settings.origin(),settings.suppressed());
    }
    @Bean RateLimitStore rateLimits(DataSource source,RuntimeSettings settings) { return new PostgresRateLimits(source,settings.rate()); }
    @Bean RequestThrottle requestThrottle(RateLimitStore limits) { return new RequestThrottle(limits); }
    @Bean SecurityCleanupStore securityCleanupStore(DataSource source) { return new PostgresSecurityCleanup(source); }
    /** Scheduling is deliberately inert until the HTTP surface is composed; the hook is invoked by a later scheduler. */
    @Bean SecurityMaintenance securityMaintenance(AuthDeliveryStore delivery,SecurityCleanupStore store) {
        return new SecurityMaintenance(new SecurityCleanup(delivery,store));
    }
    @Bean MailDelivery mail(RuntimeSettings settings) { return settings.mail(); }
    @Bean AuthDeliveryWorker authWorker(NotificationQueue queue,AuthDeliveryStore store,MailDelivery mail,RuntimeSettings settings) {
        return new AuthDeliveryWorker(queue,store,mail,settings.suppressed());
    }
}
