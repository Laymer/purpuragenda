package com.purpuragenda.adapters.out.email;

import com.purpuragenda.application.port.out.OutboxStore;
import com.purpuragenda.application.notification.NotificationPlanner;
import com.purpuragenda.application.notification.NotificationPlanner.Scheduled;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;

/** Expansion only: each event commits independently; no scheduling resource lock or SMTP. */
public final class PostgresOutboxStore implements OutboxStore {
    private final NotificationJdbc db;
    public PostgresOutboxStore(DataSource source) { db=new NotificationJdbc(source); }
    private record Event(UUID id,UUID booking,String type,long version,Instant createdAt) { }

    private record Booking(String state,Instant start) { }

    @Override public Outcome expandNext(Function<String,Plan> planner) {
        Objects.requireNonNull(planner);
        return db.transaction(()->{
            var events=db.jdbc.query("SELECT id,booking_id,type,version,created_at FROM outbox_event WHERE expanded_at IS NULL ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1",
                    (row,n)->new Event(row.getObject(1,UUID.class),row.getObject(2,UUID.class),row.getString(3),row.getLong(4),row.getTimestamp(5).toInstant()));
            if(events.isEmpty())return Outcome.IDLE;
            var event=events.getFirst();Instant now=db.now();
            Plan plan=Objects.requireNonNull(planner.apply(event.type()));
            Outcome outcome;
            if(plan instanceof Jobs jobs) {
                Booking booking=jobs.specs().stream().anyMatch(spec->spec.reminder()!=null)?db.jdbc.queryForObject(
                        "SELECT state,start_at FROM booking WHERE id=?",(row,n)->new Booking(row.getString(1),row.getTimestamp(2).toInstant()),event.booking()):null;
                for(Spec spec:jobs.specs()) {
                    var scheduled=NotificationPlanner.schedule(spec,booking==null?null:booking.state(),booking==null?null:booking.start(),event.createdAt(),now);
                    scheduled.ifPresent(value->insert(event,spec.audience(),spec.template(),value,spec.reminder()!=null,"QUEUED",null));
                }
                outcome=jobs.specs().isEmpty()?Outcome.NO_NOTIFICATION:Outcome.EXPANDED;
            } else {
                // Visible terminal failure: the unknown event never blocks later work.
                insert(event,"OWNER","UNKNOWN_OUTBOX_TYPE",new Scheduled(now,null),false,"FAILED","UNKNOWN_OUTBOX_TYPE");
                outcome=Outcome.UNKNOWN_TYPE;
            }
            db.jdbc.update("UPDATE outbox_event SET expanded_at=? WHERE id=?",Timestamp.from(now),event.id());
            return outcome;
        });
    }
    private void insert(Event event,String audience,String template,Scheduled schedule,boolean reminder,String state,String error) {
        db.jdbc.update("""
            INSERT INTO notification_job(id,event_id,booking_id,audience,template,version,due_at,scheduled_at,grace_seconds,state,safe_error)
            VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(event_id,audience,template,version) DO NOTHING
            """,UUID.randomUUID(),event.id(),event.booking(),audience,template,event.version(),Timestamp.from(schedule.dueAt()),reminder?Timestamp.from(schedule.dueAt()):null,schedule.graceSeconds(),state,error);
    }
}
