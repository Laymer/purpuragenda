package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.application.port.out.AdministratorHistoryQuery;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Separate read-only projection so private history can never execute scheduling or note commands. */
public final class PostgresAdministratorHistory implements AdministratorHistoryQuery {
    private final JdbcTemplate jdbc;private final TransactionTemplate reader;
    public PostgresAdministratorHistory(DataSource source) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(source));var manager=new DataSourceTransactionManager(source);manager.setEnforceReadOnly(true);
        reader=new TransactionTemplate(manager);reader.setReadOnly(true);reader.setTimeout(15);
        reader.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);reader.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    private <T>T read(SessionBinding session,UUID booking,Function<Instant,T> work) {
        Objects.requireNonNull(session);
        try { return reader.execute(status->{
            jdbc.execute("SET LOCAL lock_timeout='2s'");jdbc.execute("SET LOCAL statement_timeout='5s'");
            Instant now=jdbc.queryForObject("SELECT clock_timestamp()",(r,n)->r.getTimestamp(1).toInstant());
            PersistedSession.administrator(jdbc,session,now);
            if(booking!=null && jdbc.query("SELECT id FROM booking WHERE id=?",(r,n)->r.getObject(1,UUID.class),booking).isEmpty())throw new Failure(Failure.Kind.NOT_FOUND);
            return work.apply(now);
        }); } catch(Failure denial) { throw denial; }
        catch(RuntimeException unavailable) { throw new Failure(Failure.Kind.STORAGE_FAILURE); }
    }
    @Override public NotePage notes(SessionBinding session,UUID bookingId,Request request) {
        Objects.requireNonNull(bookingId);Objects.requireNonNull(request);
        return read(session,bookingId,now->{
            var parameters=new ArrayList<Object>(List.of(bookingId));String cursor=cursor("created_at",request,parameters);parameters.add(request.limit()+1);
            var rows=jdbc.query("SELECT id,note,created_at FROM booking_note WHERE booking_id=?"+cursor+" ORDER BY created_at DESC,id DESC LIMIT ?",
                    (r,n)->new Note(r.getObject(1,UUID.class),r.getString(2),r.getTimestamp(3).toInstant()),parameters.toArray());
            boolean more=rows.size()>request.limit();var visible=more?rows.subList(0,request.limit()):rows;
            Note last=visible.isEmpty()?null:visible.getLast();return new NotePage(visible,more?new Cursor(last.createdAt(),last.id()):null,now);
        });
    }
    @Override public AuditPage audit(SessionBinding session,UUID bookingId,Request request) {
        Objects.requireNonNull(request);
        return read(session,bookingId,now->{
            var parameters=new ArrayList<Object>();String filter="";
            if(bookingId!=null){filter=" AND booking_id=?";parameters.add(bookingId);}
            String cursor=cursor("occurred_at",request,parameters);parameters.add(request.limit()+1);
            var rows=jdbc.query("SELECT id,booking_id,actor_kind,type,reason,occurred_at,version FROM audit_event WHERE true"+filter+cursor+" ORDER BY occurred_at DESC,id DESC LIMIT ?",
                    (r,n)->new Audit(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getString(5),r.getTimestamp(6).toInstant(),r.getObject(7,Long.class)),parameters.toArray());
            boolean more=rows.size()>request.limit();var visible=more?rows.subList(0,request.limit()):rows;
            Audit last=visible.isEmpty()?null:visible.getLast();return new AuditPage(visible,more?new Cursor(last.occurredAt(),last.id()):null,now);
        });
    }
    private static String cursor(String column,Request request,List<Object> parameters) {
        if(request.before()==null)return "";
        parameters.add(Timestamp.from(request.before().time()));parameters.add(request.before().id());return " AND ("+column+",id)<(?,?)";
    }
}
