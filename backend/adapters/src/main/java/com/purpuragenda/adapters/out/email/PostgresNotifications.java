package com.purpuragenda.adapters.out.email;

import com.purpuragenda.application.port.out.*;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.adapters.codec.Json;
import com.purpuragenda.adapters.out.security.Secrets;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class PostgresNotifications implements NotificationQueue {
    private final JdbcTemplate jdbc;private final TransactionTemplate transaction;private final SchedulingUnitOfWork scheduling;
    private final Secrets secrets;private final CalendarWriter calendars;private final String baseUrl,owner;private final boolean suppressed;
    private static final int[] RETRIES={60,300,1800,7200,21600,43200};
    public PostgresNotifications(JdbcTemplate jdbc,PlatformTransactionManager manager,SchedulingUnitOfWork scheduling,Secrets secrets,CalendarWriter calendars,String baseUrl,String owner,String mode){this.jdbc=jdbc;transaction=new TransactionTemplate(manager);this.scheduling=scheduling;this.secrets=secrets;this.calendars=calendars;this.baseUrl=baseUrl.replaceAll("/+$","");this.owner=owner;suppressed="SUPPRESSED".equals(mode);}
    public void expand(){transaction.executeWithoutResult(status->{
        var events=jdbc.queryForList("SELECT * FROM outbox_event WHERE expanded_at IS NULL ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 50");
        for(var event:events){UUID booking=(UUID)event.get("booking_id");String type=(String)event.get("type");long version=((Number)event.get("version")).longValue();Instant now=now();
            for(String audience:"CONTACT_CHANGED".equals(type)?List.of("CUSTOMER","OWNER","OLD_CONTACT"):List.of("CUSTOMER","OWNER"))job((UUID)event.get("id"),booking,audience,type,version,now,null);
            if(Set.of("CONFIRMED","RESCHEDULED","MEETING_CHANGED","CONTACT_CHANGED").contains(type)){
                Booking b=booking(booking);
                if(b!=null&&b.state()==Booking.State.CONFIRMED&&b.version()==version){
                    Instant early=b.start().minus(Duration.ofHours(48)),late=b.start().minus(Duration.ofHours(2));
                    if(early.isAfter(now))job((UUID)event.get("id"),booking,"CUSTOMER","REMINDER_48",version,early,3600);
                    if(late.isAfter(now))job((UUID)event.get("id"),booking,"CUSTOMER","REMINDER_2",version,late,900);
                }
            }
            jdbc.update("UPDATE outbox_event SET expanded_at=? WHERE id=?",Timestamp.from(now),event.get("id"));
        }
    });}
    public Optional<Job> claim(){
        if(suppressed||Boolean.TRUE.equals(jdbc.queryForObject("SELECT delivery_suppressed FROM operation_control WHERE id=1",Boolean.class)))return Optional.empty();
        try{return transaction.execute(status->{
            jdbc.update("UPDATE notification_job SET state='QUEUED',lease_until=NULL,lease_token=NULL WHERE state='LEASED' AND lease_until<clock_timestamp()");
            var ids=jdbc.queryForList("SELECT j.id FROM notification_job j WHERE j.state='QUEUED' AND j.due_at<=clock_timestamp() AND NOT EXISTS(SELECT 1 FROM notification_job l WHERE l.booking_id=j.booking_id AND l.audience=j.audience AND l.state='LEASED') ORDER BY j.due_at,j.id FOR UPDATE SKIP LOCKED LIMIT 1",UUID.class);
            if(ids.isEmpty())return Optional.empty();UUID id=ids.getFirst(),token=UUID.randomUUID();jdbc.update("UPDATE notification_job SET state='LEASED',lease_until=clock_timestamp()+interval '60 seconds',lease_token=?,attempts=attempts+1 WHERE id=?",token,id);return Optional.of(new Job(id,token));
        });}catch(org.springframework.dao.DataIntegrityViolationException contention){return Optional.empty();}
    }
    public boolean renew(Job job){return jdbc.update("UPDATE notification_job SET lease_until=clock_timestamp()+interval '60 seconds' WHERE id=? AND state='LEASED' AND lease_token=?",job.id(),job.leaseToken())==1;}
    public Optional<Message> prepare(Job job){return transaction.execute(status->{
        var rows=jdbc.queryForList("SELECT * FROM notification_job WHERE id=? AND state='LEASED' AND lease_token=? FOR UPDATE",job.id(),job.leaseToken());if(rows.isEmpty())return Optional.empty();var row=rows.getFirst();
        Instant now=now();String template=(String)row.get("template"),audience=(String)row.get("audience");
        if(suppressed){suppress(job);return Optional.empty();}
        if("AUTH".equals(template)){
            var live=jdbc.queryForList("SELECT id FROM email_challenge WHERE id=? AND consumed_at IS NULL AND expires_at>?",UUID.class,row.get("challenge_id"),Timestamp.from(now));
            if(live.isEmpty()||row.get("encrypted_envelope")==null){suppress(job);return Optional.empty();}
            Map<?,?> envelope=Json.read(secrets.decrypt((String)row.get("encrypted_envelope")),Map.class);
            return Optional.of(new Message((String)envelope.get("email"),"Verify your Purpuragenda email","You requested email verification for "+envelope.get("purpose")+".\n\nOpen this link and choose Verify email:\n"+envelope.get("url")+"\n\nThis single-use link expires after 15 minutes. Booking and account verification must open in the browser that requested it.\nIf you did not request this email, ignore it.",messageId(job),null));
        }
        UUID id=(UUID)row.get("booking_id");Booking b=booking(id);if(b==null){suppress(job);return Optional.empty();}
        long version=((Number)row.get("version")).longValue();boolean financial=Set.of("PAYMENT_RECORDED","REFUND_RECORDED").contains(template);
        if("CONTACT_CHANGED".equals(template)&&"OLD_CONTACT".equals(audience)){Map<?,?> payload=Json.read(jdbc.queryForObject("SELECT payload::text FROM outbox_event WHERE id=?",String.class,row.get("event_id")),Map.class);String previous=jdbc.queryForObject("SELECT old_email FROM booking_contact_change WHERE id=?::uuid",String.class,payload.get("contactChangeId").toString());return Optional.of(new Message(previous,"Appointment contact email changed","The verified delivery email for appointment "+b.reference()+" has changed. Booking ownership and account login have not changed. If you did not authorize this, contact Medina through your usual channel.",messageId(job),null));}
        if(!financial&&version!=b.version()){suppress(job);return Optional.empty();}
        if(template.startsWith("REMINDER")){
            Instant due=((Timestamp)row.get("due_at")).toInstant();int grace=((Number)row.get("grace_seconds")).intValue();
            if(b.state()!=Booking.State.CONFIRMED||!b.start().isAfter(now)||now.isAfter(due.plusSeconds(grace))){suppress(job);return Optional.empty();}
        }
        if("CONFIRMED".equals(template)&&b.state()!=Booking.State.CONFIRMED||"REQUESTED".equals(template)&&!b.livePending(now)){suppress(job);return Optional.empty();}
        var ledger=scheduling.read(v->v.ledger(id));var summary=ledger.summary(b.snapshot().service().price());
        String zone="OWNER".equals(audience)?"Europe/Brussels":b.snapshot().displayZone();
        String when=DateTimeFormatter.ofPattern("EEEE d MMMM uuuu, HH:mm XXX '['VV']'",Locale.ENGLISH).format(b.start().atZone(ZoneId.of(zone)));
        String title=switch(template){case "REQUESTED"->"Appointment request received — awaiting confirmation";case "CONFIRMED"->"Appointment confirmed";case "CANCELLED"->"Appointment cancelled";case "REJECTED"->"Appointment request declined";case "EXPIRED"->"Appointment request expired";case "RESCHEDULED"->"Appointment rescheduled";case "MEETING_CHANGED"->"Zoom meeting details updated";case "PAYMENT_RECORDED"->"External payment recorded";case "REFUND_RECORDED"->"External refund recorded";default->"Appointment reminder";};
        StringBuilder body=new StringBuilder(title).append("\n\nReference: ").append(b.reference()).append("\nService: ").append(b.snapshot().service().name()).append("\nSession: ").append(b.snapshot().service().durationMinutes()).append(" minutes\nPrice: EUR ").append(String.format(Locale.ROOT,"%d.%02d",b.snapshot().service().price().minor()/100,b.snapshot().service().price().minor()%100)).append("\nWhen: ").append(when).append("\nBooking status: ").append(b.state()).append("\nPayment status: ").append(summary.status()).append("\nEUR received: ").append(summary.receivedMinor()).append(" cents; remaining: ").append(summary.remainingMinor()).append(" cents\nCancellation deadline: ").append(b.start().minus(b.snapshot().policy().customerCutoff()).atZone(ZoneId.of(zone))).append("\n");
        if(b.livePending(now))body.append("This is a request, not a confirmed appointment. Hold expires: ").append(b.expiresAt()).append("\n");
        if(financial)for(var entry:ledger.entries())body.append("Verified ").append(entry.kind()).append(": ").append(entry.amount().minor()).append(" ").append(entry.amount().currency()).append(" minor units; reference ").append(entry.reference()).append("\n");
        if(b.state()==Booking.State.CONFIRMED&&b.meeting()!=null)body.append("Join Zoom: ").append(b.meeting().participantUrl()).append("\n");
        if("OWNER".equals(audience))body.append("\nContact: ").append(b.deliveryEmail()).append(" / ").append(b.snapshot().intake().telephone()).append("\nOwner panel: ").append(baseUrl).append("/admin\n");
        else body.append("\nManage this appointment: ").append(baseUrl).append("/account\nChoose recover an appointment and enter the reference above and this email address.\n");
        if(summary.status()==com.purpuragenda.domain.payment.PaymentLedger.Status.REFUND_DUE)body.append("A refund follow-up is outstanding. No automatic refund has been claimed.\n");
        boolean attachment=b.previouslyConfirmed()&&Set.of("CONFIRMED","CANCELLED","RESCHEDULED","MEETING_CHANGED").contains(template);
        if(attachment)body.append("\nThe attached calendar file covers the consultation only. Re-imports and cancellations may need a manual update in your calendar.\n");
        return Optional.of(new Message("OWNER".equals(audience)?owner:b.deliveryEmail(),title+" · "+b.reference(),body.toString(),messageId(job),attachment?calendars.write(b,audience,now):null));
    });}
    public void delivered(Job job){transaction.executeWithoutResult(status->{jdbc.update("UPDATE notification_job SET state='SENT',lease_until=NULL,lease_token=NULL,encrypted_envelope=NULL,safe_error=NULL WHERE id=? AND state='LEASED' AND lease_token=?",job.id(),job.leaseToken());attempt(job,true,null);});}
    public void failed(Job job){transaction.executeWithoutResult(status->{var rows=jdbc.queryForList("SELECT attempts FROM notification_job WHERE id=? AND state='LEASED' AND lease_token=? FOR UPDATE",Integer.class,job.id(),job.leaseToken());if(rows.isEmpty())return;int count=rows.getFirst();jdbc.update("UPDATE notification_job SET state=?,due_at=clock_timestamp()+?*interval '1 second',lease_until=NULL,lease_token=NULL,safe_error='SMTP_DELIVERY_FAILED' WHERE id=?",count>RETRIES.length?"FAILED":"QUEUED",count>RETRIES.length?0:RETRIES[count-1],job.id());attempt(job,false,"SMTP_DELIVERY_FAILED");});}
    private void suppress(Job job){jdbc.update("UPDATE notification_job SET state='SUPPRESSED',lease_until=NULL,lease_token=NULL,encrypted_envelope=NULL WHERE id=? AND lease_token=?",job.id(),job.leaseToken());}
    private void attempt(Job job,boolean success,String error){jdbc.update("INSERT INTO delivery_attempt(id,job_id,attempted_at,success,safe_code,lease_token) VALUES(?,?,clock_timestamp(),?,?,?)",UUID.randomUUID(),job.id(),success,error,job.leaseToken());}
    private Booking booking(UUID id){return scheduling.read(v->v.bookings().stream().filter(b->b.id().equals(id)).findFirst().orElse(null));}
    private Instant now(){return jdbc.queryForObject("SELECT clock_timestamp()",(r,n)->r.getTimestamp(1).toInstant());}
    private void job(UUID event,UUID booking,String audience,String template,long version,Instant due,Integer grace){jdbc.update("INSERT INTO notification_job(id,event_id,booking_id,audience,template,version,due_at,grace_seconds) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",UUID.randomUUID(),event,booking,audience,template,version,Timestamp.from(due),grace);}
    private static String messageId(Job job){return "<"+job.id()+"@purpuragenda>";}
}
