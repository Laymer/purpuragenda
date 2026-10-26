package com.purpuragenda.application.notification;

import com.purpuragenda.application.port.out.OutboxStore.*;
import java.util.*;

/**
 * Explicit handler per outbox type. Total: any other type is Unknown (visible failure, never an exception) so one bad event
 * cannot block the consumer. CONFIGURATION_CONFLICT is an administrator follow-up with no notification at all.
 */
public final class NotificationPlanner {
    private NotificationPlanner() {}
    private static Spec customer(String template) { return new Spec("CUSTOMER",template); }
    private static Spec owner(String template) { return new Spec("OWNER",template); }
    public static Plan plan(String type) {
        if(type==null)return new Unknown();
        return switch(type) {
            case "REQUESTED" -> new Jobs(List.of(customer("BOOKING_REQUESTED"),owner("OWNER_REQUEST_RECEIVED")));
            case "ADMIN_CREATED" -> new Jobs(List.of(customer("BOOKING_CREATED")));
            case "APPROVED","CONFIRMED_EXTERNAL_PAYMENT" -> new Jobs(List.of(customer("BOOKING_CONFIRMED"),owner("OWNER_BOOKING_CONFIRMED")));
            case "REJECTED" -> new Jobs(List.of(customer("BOOKING_REJECTED"),owner("OWNER_BOOKING_REJECTED")));
            case "EXPIRED" -> new Jobs(List.of(customer("BOOKING_EXPIRED"),owner("OWNER_BOOKING_EXPIRED")));
            case "CANCELLED" -> new Jobs(List.of(customer("BOOKING_CANCELLED"),owner("OWNER_BOOKING_CANCELLED")));
            case "RESCHEDULED" -> new Jobs(List.of(customer("BOOKING_RESCHEDULED"),owner("OWNER_BOOKING_RESCHEDULED")));
            case "PAYMENT_RECORDED" -> new Jobs(List.of(customer("PAYMENT_RECORDED"),owner("OWNER_PAYMENT_RECORDED")));
            case "REFUND_RECORDED" -> new Jobs(List.of(customer("REFUND_RECORDED")));
            case "PAYMENT_RECONCILIATION_REQUIRED" -> new Jobs(List.of(owner("OWNER_ACTION_RECONCILIATION")));
            case "REFUND_REQUIRED" -> new Jobs(List.of(owner("OWNER_ACTION_REFUND")));
            // Administrator follow-up only: no customer notification and no job.
            case "CONFIGURATION_CONFLICT" -> new Jobs(List.of());
            default -> new Unknown();
        };
    }
}
