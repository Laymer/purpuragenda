package com.purpuragenda.adapters.out.email;

import com.purpuragenda.application.port.out.OutboxStore;
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
    private record Event(UUID id,UUID booking,String type,long version) { }

    @Override public Outcome expandNext(Function<String,Plan> planner) {
        Objects.requireNonNull(planner);
        return db.transaction(()->{
            var events=db.jdbc.query("SELECT id,booking_id,type,version FROM outbox_event WHERE expanded_at IS NULL ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1",
                    (row,n)->new Event(row.getObject(1,UUID.class),row.getObject(2,UUID.class),row.getString(3),row.getLong(4)));
            if(events.isEmpty())return Outcome.IDLE;
            var event=events.getFirst();Instant now=db.now();
            Plan plan=Objects.requireNonNull(planner.apply(event.type()));
            Outcome outcome;
            if(plan instanceof Jobs jobs) {
                for(Spec spec:jobs.specs())insert(event,spec.audience(),spec.template(),now,"QUEUED",null);
                outcome=jobs.specs().isEmpty()?Outcome.NO_NOTIFICATION:Outcome.EXPANDED;
            } else {
                // Visible terminal failure: the unknown event never blocks later work.
                insert(event,"OWNER","UNKNOWN_OUTBOX_TYPE",now,"FAILED","UNKNOWN_OUTBOX_TYPE");
                outcome=Outcome.UNKNOWN_TYPE;
            }
            db.jdbc.update("UPDATE outbox_event SET expanded_at=? WHERE id=?",Timestamp.from(now),event.id());
            return outcome;
        });
    }
    private void insert(Event event,String audience,String template,Instant now,String state,String error) {
        db.jdbc.update("""
            INSERT INTO notification_job(id,event_id,booking_id,audience,template,version,due_at,state,safe_error)
            VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(event_id,audience,template,version) DO NOTHING
            """,UUID.randomUUID(),event.id(),event.booking(),audience,template,event.version(),Timestamp.from(now),state,error);
    }
}
