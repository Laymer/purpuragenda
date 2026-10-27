package com.purpuragenda.application.notification;

import com.purpuragenda.application.port.out.BookingNotificationStore.Facts;
import com.purpuragenda.application.port.out.MailDelivery;
import com.purpuragenda.application.port.out.OutboxStore.Reminder;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Renders an email from CURRENT facts or decides the job is no longer worth sending. Bodies carry only the booking
 * reference, service name, time, state and (for confirmations) the participant meeting link: never names, telephone,
 * birth data, intake message, private notes or tokens.
 */
public final class NotificationContent {
    private NotificationContent() {}
    public sealed interface Prepared permits Send,Suppress {}
    public record Send(MailDelivery.Message message) implements Prepared {}
    public record Suppress(String code) implements Prepared {}
    private static final ZoneId BRUSSELS=ZoneId.of("Europe/Brussels");
    private static final DateTimeFormatter LOCAL=DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'at' HH:mm",Locale.ENGLISH);
    private static final Set<String> ALWAYS=Set.of("PAYMENT_RECORDED","OWNER_PAYMENT_RECORDED","REFUND_RECORDED","OWNER_REFUND_RECORDED","OWNER_ACTION_RECONCILIATION","OWNER_ACTION_REFUND");

    public static Prepared prepare(Facts f) {
        Objects.requireNonNull(f);
        String bare=f.template().startsWith("OWNER_")?f.template().substring(6):f.template();
        boolean known=ALWAYS.contains(f.template()) || Set.of("BOOKING_REQUESTED","REQUEST_RECEIVED","BOOKING_CREATED","BOOKING_CONFIRMED","BOOKING_REJECTED",
                "BOOKING_EXPIRED","BOOKING_CANCELLED","BOOKING_RESCHEDULED","REMINDER_48H","REMINDER_2H").contains(bare);
        if(!known)return new Suppress("UNKNOWN_TEMPLATE");
        if(f.recipient()==null)return new Suppress("RECIPIENT_UNAVAILABLE");
        boolean pendingLive=f.state().equals("PENDING_CONFIRMATION") && f.expiresAt()!=null && f.expiresAt().isAfter(f.now());
        boolean confirmed=f.state().equals("CONFIRMED");
        Set<String> later=f.laterEventTypes();
        String stale=null;
        if(!ALWAYS.contains(f.template())) stale=switch(bare) {
            case "BOOKING_REQUESTED","REQUEST_RECEIVED" -> !disjoint(later,"RESCHEDULED")?"SUPERSEDED":pendingLive?null:"BOOKING_NOT_PENDING";
            case "BOOKING_CREATED" -> disjoint(later,"CANCELLED","EXPIRED","REJECTED","RESCHEDULED")?(pendingLive||confirmed?null:"BOOKING_NOT_ACTIVE"):"SUPERSEDED";
            case "BOOKING_CONFIRMED" -> !disjoint(later,"CANCELLED","RESCHEDULED")?"SUPERSEDED":confirmed?null:"BOOKING_NOT_CONFIRMED";
            case "BOOKING_RESCHEDULED" -> !disjoint(later,"CANCELLED","RESCHEDULED")?"SUPERSEDED":(pendingLive||confirmed)?null:"BOOKING_NOT_ACTIVE";
            case "BOOKING_CANCELLED" -> f.state().equals("CANCELLED")?null:"BOOKING_NOT_CANCELLED";
            case "BOOKING_EXPIRED" -> f.state().equals("EXPIRED")?null:"BOOKING_NOT_EXPIRED";
            case "BOOKING_REJECTED" -> f.state().equals("REJECTED")?null:"BOOKING_NOT_REJECTED";
            case "REMINDER_48H","REMINDER_2H" -> reminderRelevance(f,bare);
            default -> "UNKNOWN_TEMPLATE";
        };
        if(stale!=null)return new Suppress(stale);
        String when=f.start().atZone(BRUSSELS).format(LOCAL)+" (Brussels time; "+f.start()+" UTC)";
        String subject=switch(bare) {
            case "BOOKING_REQUESTED","REQUEST_RECEIVED" -> "Booking request received";
            case "BOOKING_CREATED" -> f.audience().equals("OWNER")?"Booking created":confirmed?"Your booking is confirmed":"A booking request was created for you";
            case "BOOKING_CONFIRMED" -> "Booking confirmed";
            case "BOOKING_RESCHEDULED" -> "Booking time changed";
            case "BOOKING_CANCELLED" -> "Booking cancelled";
            case "BOOKING_EXPIRED" -> "Booking request expired";
            case "BOOKING_REJECTED" -> "Booking request declined";
            case "PAYMENT_RECORDED" -> "Payment recorded";
            case "REFUND_RECORDED" -> "Refund recorded";
            case "REMINDER_48H","REMINDER_2H" -> "Consultation reminder";
            default -> f.template().equals("OWNER_ACTION_REFUND")?"Action needed: refund":"Action needed: payment reconciliation";
        };
        StringBuilder text=new StringBuilder();
        text.append("Booking reference: ").append(f.reference()).append("\nService: ").append(f.serviceName()).append("\nTime: ").append(when).append("\n");
        if(f.audience().equals("CUSTOMER")) {
            text.append(switch(bare) {
                case "BOOKING_REQUESTED" -> "\nYour request is pending. It is not confirmed until the practitioner approves it, and the hold expires automatically.\n";
                case "BOOKING_CREATED" -> confirmed?"\nThe practitioner created and confirmed this booking.\n":"\nThe practitioner created this request. It is pending until confirmed.\n";
                case "BOOKING_CONFIRMED" -> "\nYour booking is confirmed.\n";
                case "BOOKING_RESCHEDULED" -> "\nThe session time changed to the time above.\n";
                case "BOOKING_CANCELLED" -> "\nThis booking is cancelled.\n";
                case "BOOKING_EXPIRED" -> "\nThe hold expired and the time is released.\n";
                case "BOOKING_REJECTED" -> "\nThis request was declined.\n";
                case "PAYMENT_RECORDED" -> "\nA payment was recorded against this booking.\n";
                case "REMINDER_48H","REMINDER_2H" -> "\nYour confirmed consultation is approaching. Please check the session time and use the meeting link below.\n";
                default -> "\nA refund was recorded for this booking.\n";
            });
            if(confirmed && f.meetingUrl()!=null && Set.of("BOOKING_CONFIRMED","BOOKING_CREATED","BOOKING_RESCHEDULED","REMINDER_48H","REMINDER_2H").contains(bare))
                text.append("Meeting link: ").append(f.meetingUrl()).append("\n");
        } else {
            text.append("\nStatus: ").append(f.state()).append("\nOpen the administrator area for details and any action.\n");
        }
        text.append("\nThis message deliberately contains no personal intake details.\n");
        return new Send(new MailDelivery.Message(f.jobId(),f.recipient(),subject,text.toString()));
    }
    private static String reminderRelevance(Facts f,String template) {
        if(!f.audience().equals("CUSTOMER"))return "INVALID_REMINDER_AUDIENCE";
        if(!f.state().equals("CONFIRMED"))return "BOOKING_NOT_CONFIRMED";
        if(!disjoint(f.laterEventTypes(),"RESCHEDULED","CANCELLED","EXPIRED","REJECTED","APPROVED","CONFIRMED_EXTERNAL_PAYMENT"))return "SUPERSEDED";
        Reminder timing=template.equals("REMINDER_48H")?Reminder.FORTY_EIGHT_HOURS:Reminder.TWO_HOURS;
        if(f.scheduledAt()==null || f.graceSeconds()==null || f.graceSeconds()!=timing.graceSeconds())return "INVALID_REMINDER_WINDOW";
        if(!f.start().minus(timing.lead()).equals(f.scheduledAt()))return "SUPERSEDED";
        if(!f.now().isBefore(f.start()))return "SESSION_STARTED";
        if(f.now().isBefore(f.scheduledAt()))return "REMINDER_NOT_DUE";
        // Like leases/holds, the grace deadline itself is expired: [scheduled, scheduled + grace).
        if(!f.now().isBefore(f.scheduledAt().plusSeconds(f.graceSeconds())))return "REMINDER_GRACE_EXPIRED";
        return f.meetingUrl()==null?"MEETING_UNAVAILABLE":null;
    }
    private static boolean disjoint(Set<String> values,String... excluded) {
        for(String value:excluded)if(values.contains(value))return false;
        return true;
    }
}
