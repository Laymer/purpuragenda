package com.purpuragenda.application.notification;

import com.purpuragenda.application.port.out.OutboxStore.*;
import java.util.*;
import java.time.Instant;

/**
 * Explicit handler per outbox type. Total: any other type is Unknown (visible failure, never an exception) so one bad event
 * cannot block the consumer. CONFIGURATION_CONFLICT is an administrator follow-up with no notification at all.
 */
public final class NotificationPlanner {
    private NotificationPlanner() {}
    private static Spec customer(String template) { return new Spec("CUSTOMER",template); }
    private static Spec owner(String template) { return new Spec("OWNER",template); }
    private static Jobs withReminders(Spec customer,Spec owner) {
        return new Jobs(List.of(customer,owner,new Spec("CUSTOMER","REMINDER_48H",Reminder.FORTY_EIGHT_HOURS),new Spec("CUSTOMER","REMINDER_2H",Reminder.TWO_HOURS)));
    }
    public record Scheduled(Instant dueAt,Integer graceSeconds) { }
    public static Optional<Scheduled> schedule(Spec spec,String state,Instant start,Instant now) {
        return schedule(spec,state,start,now,now);
    }
    /** Event creation decides eligibility; an expansion delay never extends or erases the original grace window. */
    public static Optional<Scheduled> schedule(Spec spec,String state,Instant start,Instant createdAt,Instant now) {
        if(spec.reminder()==null)return Optional.of(new Scheduled(now,null));
        if(!"CONFIRMED".equals(state))return Optional.empty();
        Instant due=start.minus(spec.reminder().lead());
        return due.isBefore(createdAt)?Optional.empty():Optional.of(new Scheduled(due,spec.reminder().graceSeconds()));
    }
    public static Plan plan(String type) {
        if(type==null)return new Unknown();
        return switch(type) {
            case "REQUESTED" -> new Jobs(List.of(customer("BOOKING_REQUESTED"),owner("OWNER_REQUEST_RECEIVED")));
            case "ADMIN_CREATED" -> withReminders(customer("BOOKING_CREATED"),owner("OWNER_BOOKING_CREATED"));
            case "APPROVED","CONFIRMED_EXTERNAL_PAYMENT" -> withReminders(customer("BOOKING_CONFIRMED"),owner("OWNER_BOOKING_CONFIRMED"));
            case "REJECTED" -> new Jobs(List.of(customer("BOOKING_REJECTED"),owner("OWNER_BOOKING_REJECTED")));
            case "EXPIRED" -> new Jobs(List.of(customer("BOOKING_EXPIRED"),owner("OWNER_BOOKING_EXPIRED")));
            case "CANCELLED" -> new Jobs(List.of(customer("BOOKING_CANCELLED"),owner("OWNER_BOOKING_CANCELLED")));
            case "RESCHEDULED" -> withReminders(customer("BOOKING_RESCHEDULED"),owner("OWNER_BOOKING_RESCHEDULED"));
            // Reminders already render the current link; a meeting change only needs its own notice.
            case "MEETING_CHANGED" -> new Jobs(List.of(customer("BOOKING_MEETING_CHANGED"),owner("OWNER_BOOKING_MEETING_CHANGED")));
            case "PAYMENT_RECORDED" -> new Jobs(List.of(customer("PAYMENT_RECORDED"),owner("OWNER_PAYMENT_RECORDED")));
            case "REFUND_RECORDED" -> new Jobs(List.of(customer("REFUND_RECORDED"),owner("OWNER_REFUND_RECORDED")));
            case "PAYMENT_RECONCILIATION_REQUIRED" -> new Jobs(List.of(owner("OWNER_ACTION_RECONCILIATION")));
            case "REFUND_REQUIRED" -> new Jobs(List.of(owner("OWNER_ACTION_REFUND")));
            // Administrator follow-up only: no customer notification and no job.
            case "CONFIGURATION_CONFLICT" -> new Jobs(List.of());
            default -> new Unknown();
        };
    }
}
