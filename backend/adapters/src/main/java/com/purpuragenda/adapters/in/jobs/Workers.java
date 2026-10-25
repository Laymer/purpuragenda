package com.purpuragenda.adapters.in.jobs;
import com.purpuragenda.application.usecase.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @ConditionalOnProperty(name="app.jobs.enabled",havingValue="true",matchIfMissing=true)
public class Workers {
    private final BookingService bookings;private final NotificationService notifications;
    public Workers(BookingService bookings,NotificationService notifications){this.bookings=bookings;this.notifications=notifications;}
    @Scheduled(fixedDelay=30000,initialDelay=30000) public void expiry(){bookings.reclaim();}
    @Scheduled(fixedDelay=1000,initialDelay=5000) public void notifications(){notifications.tick();}
}
