package com.purpuragenda.bootstrap;

import com.purpuragenda.application.scheduling.AdministratorBookingView;
import com.purpuragenda.application.scheduling.AdministratorFinance;
import com.purpuragenda.adapters.out.postgres.PostgresFinance;
import com.purpuragenda.adapters.out.postgres.PostgresAdministratorBookingQuery;

import com.purpuragenda.adapters.in.rest.*;
import com.purpuragenda.adapters.out.email.*;
import com.purpuragenda.adapters.out.postgres.PostgresScheduling;
import com.purpuragenda.adapters.out.postgres.DatabaseReadiness;
import com.purpuragenda.adapters.out.postgres.PostgresCustomerBooking;
import com.purpuragenda.adapters.out.postgres.PostgresBookingChanges;
import com.purpuragenda.adapters.out.postgres.PostgresCustomerBookingQuery;
import com.purpuragenda.adapters.out.security.*;
import com.purpuragenda.application.*;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.scheduling.PublicScheduling;
import com.purpuragenda.application.scheduling.CustomerBooking;
import com.purpuragenda.application.scheduling.BookingNotice;
import com.purpuragenda.application.scheduling.PublicBookingContext;
import com.purpuragenda.application.scheduling.CustomerBookingChanges;
import com.purpuragenda.application.scheduling.CustomerBookingView;
import javax.sql.DataSource;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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
    @Bean SecurityMaintenance securityMaintenance(AuthDeliveryStore delivery,SecurityCleanupStore store) {
        return new SecurityMaintenance(new SecurityCleanup(delivery,store));
    }
    @Bean RuntimeWorkers runtimeWorkers(AuthDeliveryWorker delivery,SecurityMaintenance maintenance,
                                       DatabaseReadiness readiness,RuntimeSettings settings) {
        return new RuntimeWorkers(delivery::tick,maintenance::tick,
                () -> org.springframework.boot.health.contributor.Status.UP.equals(readiness.health().getStatus()),settings.suppressed());
    }
    @Bean MailDelivery mail(RuntimeSettings settings) { return settings.mail(); }
    @Bean AuthDeliveryWorker authWorker(NotificationQueue queue,AuthDeliveryStore store,MailDelivery mail,RuntimeSettings settings) {
        return new AuthDeliveryWorker(queue,store,mail,settings.suppressed());
    }
    @Bean SessionStore sessions(DataSource source) { return new PostgresSessions(source); }
    @Bean SessionAccess sessionAccess(SessionStore sessions) { return new SessionAccess(sessions); }
    @Bean SessionCookies sessionCookies(RuntimeSettings settings) { return settings.localCookies()?SessionCookies.local():SessionCookies.operational(); }
    @Bean SessionController sessionController(SessionAccess access,SessionCookies cookies) { return new SessionController(access,cookies); }
    @Bean GuestRecoveryLookup guestRecoveryLookup(DataSource source) { return new PostgresGuestRecoveryLookup(source); }
    @Bean GuestRecovery guestRecovery(GuestRecoveryLookup lookup,AuthDeliveryStore delivery) { return new GuestRecovery(lookup,delivery); }
    @Bean GuestProofs guestProofs(RequestThrottle throttle,AuthDeliveryStore delivery,GuestRecovery recovery) { return new GuestProofs(throttle,delivery,recovery); }
    @Bean GuestAuthController guestAuthController(GuestProofs proofs,SessionAccess access,SessionCookies cookies) {
        return new GuestAuthController(proofs,access,cookies,java.time.Duration.ofMillis(300));
    }
    @Bean AdministratorFinance administratorFinance(DataSource source,RuntimeSettings settings) {
        return new AdministratorFinance(new PostgresFinance(source,settings.meetingHosts()),settings.meetingHosts());
    }
    @Bean AdministratorFinanceController administratorFinanceController(AdministratorFinance finance) { return new AdministratorFinanceController(finance); }
    @Bean AdministratorBookingView administratorBookingView(DataSource source,RuntimeSettings settings) {
        return new AdministratorBookingView(new PostgresAdministratorBookingQuery(source,settings.meetingHosts()));
    }
    @Bean AdministratorBookingController administratorBookingController(AdministratorBookingView view,SessionAccess access,SessionCookies cookies) {
        return new AdministratorBookingController(view,access,cookies);
    }
    @Bean AdministratorRecovery administratorRecovery(PrivateAdminRecovery recovery,RequestThrottle throttle) {
        return new AdministratorRecovery(recovery,throttle,new SessionRecoveryBinding());
    }
    @Bean AdministratorRecoveryController administratorRecoveryController(AdministratorRecovery recovery) {
        return new AdministratorRecoveryController(recovery,java.time.Duration.ofMillis(300));
    }
    @Bean AdministratorLogin administratorLogin(RequestThrottle throttle,PrivateAdminMfa factors,SessionStore sessions) {
        return new AdministratorLogin(throttle,factors,sessions,new AdminCrypto());
    }
    @Bean AdministratorLoginController administratorLoginController(AdministratorLogin login,SessionCookies cookies) {
        return new AdministratorLoginController(login,cookies,java.time.Duration.ofMillis(300));
    }
    @Bean PublicController publicController(PublicScheduling scheduling) { return new PublicController(scheduling); }
    @Bean BookingNotice bookingNotice(Environment environment) { return RuntimeNotice.read(environment); }
    @Bean BookingContextController bookingContext(SchedulingUnitOfWork work,BookingNotice notice) {
        return new BookingContextController(new PublicBookingContext(work,notice));
    }
    @Bean CustomerBookingController customerBookings(DataSource source,RuntimeSettings settings,BookingNotice notice,SessionAccess access,SessionCookies cookies) {
        // Review and submission share the same immutable configured notice.
        var create=new CustomerBooking(new PostgresCustomerBooking(source,settings.meetingHosts()),notice.version());
        var changes=new CustomerBookingChanges(new PostgresBookingChanges(source,settings.meetingHosts()));
        var views=new CustomerBookingView(new PostgresCustomerBookingQuery(source,settings.meetingHosts()));
        return new CustomerBookingController(create,changes,views,access,cookies);
    }
    @Bean FilterRegistrationBean<AvailabilityThrottleFilter> availabilityThrottle(RequestThrottle throttle) {
        var registration=new FilterRegistrationBean<>(new AvailabilityThrottleFilter(throttle));
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE+20);
        registration.addUrlPatterns("/*"); // the filter itself decides on the normalized path
        return registration;
    }
    @Bean FilterRegistrationBean<ApiGuardFilter> apiGuard(RuntimeSettings settings,SessionAccess access,SessionCookies cookies) {
        var registration=new FilterRegistrationBean<>(new ApiGuardFilter(settings.canonicalOrigin(),access,cookies,new ClientAddress(settings.trustedProxyPeers())));
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE+10);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
