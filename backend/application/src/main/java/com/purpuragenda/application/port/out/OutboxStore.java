package com.purpuragenda.application.port.out;

import java.util.*;
import java.time.Duration;
import java.util.function.Function;

/** Expands one unexpanded outbox event per transaction into durable notification jobs; never sends anything. */
public interface OutboxStore {
    enum Outcome { IDLE, EXPANDED, NO_NOTIFICATION, UNKNOWN_TYPE }
    enum Reminder {
        FORTY_EIGHT_HOURS("REMINDER_48H",Duration.ofHours(48),3600), TWO_HOURS("REMINDER_2H",Duration.ofHours(2),900);
        private final String template;private final Duration lead;private final int grace;
        Reminder(String template,Duration lead,int grace){this.template=template;this.lead=lead;this.grace=grace;}
        public String template(){return template;}public Duration lead(){return lead;}public int graceSeconds(){return grace;}
    }
    record Spec(String audience,String template,Reminder reminder) {
        public Spec(String audience,String template){this(audience,template,null);}
        public Spec {
            if(!Set.of("CUSTOMER","OWNER").contains(audience) || template==null || !template.matches("[A-Z][A-Z0-9_]{0,79}"))
                throw new IllegalArgumentException("INVALID_NOTIFICATION_SPEC");
            if(reminder!=null && (!audience.equals("CUSTOMER") || !template.equals(reminder.template()))
                    || reminder==null && Set.of("REMINDER_48H","REMINDER_2H").contains(template))
                throw new IllegalArgumentException("INVALID_NOTIFICATION_SPEC");
        }
    }
    /** Total planner result: a known type yields jobs (possibly none); anything else is Unknown and fails visibly. */
    sealed interface Plan permits Jobs,Unknown {}
    record Jobs(List<Spec> specs) implements Plan { public Jobs { specs=List.copyOf(specs); } }
    record Unknown() implements Plan {}
    /**
     * Locks the oldest unexpanded event (skipping events locked by other workers), inserts the planned jobs with unique
     * event/audience/template/revision keys, records unknown types as a FAILED owner job and marks the event expanded, all
     * in one transaction. A storage failure leaves the event unexpanded for a later attempt.
     */
    Outcome expandNext(Function<String,Plan> planner);
}
