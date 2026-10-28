package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.application.port.out.AdministratorOperationsQuery;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Administrator reconciliation and delivery follow-up use the same authorized, read-only snapshot. */
public final class PostgresAdministratorOperations implements AdministratorOperationsQuery {
    private static final Set<String> SAFE_CODES=Set.of("SMTP_DELIVERY_FAILED","INVALID_CONTACT_AUDIENCE","RECIPIENT_UNAVAILABLE","UNKNOWN_TEMPLATE","SUPERSEDED",
            "BOOKING_NOT_PENDING","BOOKING_NOT_ACTIVE","BOOKING_NOT_CONFIRMED","BOOKING_NOT_CANCELLED","BOOKING_NOT_EXPIRED","BOOKING_NOT_REJECTED",
            "INVALID_REMINDER_AUDIENCE","INVALID_REMINDER_WINDOW","SESSION_STARTED","REMINDER_NOT_DUE","REMINDER_GRACE_EXPIRED","MEETING_UNAVAILABLE");
    private final JdbcTemplate jdbc;private final TransactionTemplate reader;
    public PostgresAdministratorOperations(DataSource source) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(source));var manager=new DataSourceTransactionManager(source);manager.setEnforceReadOnly(true);
        reader=new TransactionTemplate(manager);reader.setReadOnly(true);reader.setTimeout(15);reader.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);reader.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    private <T>T read(SessionBinding session,UUID bookingId,Function<Instant,T> work) {
        Objects.requireNonNull(session);
        try {return reader.execute(status->{jdbc.execute("SET LOCAL lock_timeout='2s'");jdbc.execute("SET LOCAL statement_timeout='5s'");
            Instant now=jdbc.queryForObject("SELECT clock_timestamp()",(r,n)->r.getTimestamp(1).toInstant());PersistedSession.administrator(jdbc,session,now);
            if(bookingId!=null && jdbc.queryForObject("SELECT count(*) FROM booking WHERE id=?",Integer.class,bookingId)!=1)throw new Failure(Failure.Kind.NOT_FOUND);
            return work.apply(now);
        });}catch(Failure denial){throw denial;}catch(RuntimeException unavailable){throw new Failure(Failure.Kind.STORAGE_FAILURE);}
    }
    @Override public Page<Refund> reconciliation(SessionBinding session,UUID bookingId,Request request) {
        Objects.requireNonNull(request);
        return read(session,bookingId,now->{
            var args=new ArrayList<Object>();String filter=filters(bookingId,request,"p",args);args.add(request.limit()+1);
            var rows=jdbc.query("""
                SELECT p.id,p.booking_id,b.reference,b.state,p.currency,p.amount_minor,COALESCE(r.refunded,0),o.due_minor,
                  o.due_minor-COALESCE(r.refunded,0),o.reason,p.verified_at
                FROM refund_obligation o JOIN payment_entry p ON p.id=o.receipt_id AND p.kind='RECEIPT' JOIN booking b ON b.id=p.booking_id
                LEFT JOIN LATERAL (SELECT sum(amount_minor) AS refunded FROM payment_entry WHERE kind='REFUND' AND receipt_id=p.id) r ON true
                WHERE o.due_minor>COALESCE(r.refunded,0)
                """+filter+" ORDER BY p.id LIMIT ?",(r,n)->new Refund(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getString(5),
                    r.getLong(6),r.getLong(7),r.getLong(8),r.getLong(9),r.getString(10),r.getTimestamp(11).toInstant()),args.toArray());
            return page(rows,request,now,Refund::receiptId);
        });
    }
    @Override public Page<Notification> notifications(SessionBinding session,UUID bookingId,NotificationState state,Request request) {
        Objects.requireNonNull(request);
        return read(session,bookingId,now->{
            var args=new ArrayList<Object>();String filter=filters(bookingId,request,"j",args);if(state!=null){filter+=" AND j.state=?";args.add(state.name());}args.add(request.limit()+1);
            var rows=jdbc.query("""
                SELECT j.id,j.booking_id,b.reference,j.audience,j.template,j.version,j.state,j.attempts,j.due_at,j.safe_error
                FROM notification_job j JOIN booking b ON b.id=j.booking_id WHERE j.template<>'AUTH'
                """+filter+" ORDER BY j.id LIMIT ?",(r,n)->new Notification(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getString(5),r.getLong(6),
                    NotificationState.valueOf(r.getString(7)),r.getInt(8),r.getTimestamp(9).toInstant(),safe(r.getString(10))),args.toArray());
            return page(rows,request,now,Notification::id);
        });
    }
    private static String filters(UUID bookingId,Request request,String alias,List<Object> args) {
        String filter="";if(bookingId!=null){filter+=" AND "+alias+".booking_id=?";args.add(bookingId);}if(request.after()!=null){filter+=" AND "+alias+".id>?";args.add(request.after());}return filter;
    }
    private static <T>Page<T> page(List<T> rows,Request request,Instant now,Function<T,UUID> id) {
        boolean more=rows.size()>request.limit();var visible=more?rows.subList(0,request.limit()):rows;return new Page<>(visible,more?id.apply(visible.getLast()):null,now);
    }
    private static String safe(String code) {return code==null?null:SAFE_CODES.contains(code)?code:"DELIVERY_STATUS_UNAVAILABLE";}
}
