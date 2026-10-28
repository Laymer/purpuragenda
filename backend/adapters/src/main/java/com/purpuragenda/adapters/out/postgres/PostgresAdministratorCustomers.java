package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.application.port.out.AdministratorCustomerQuery;
import com.purpuragenda.application.port.out.AdministratorHistoryQuery;
import com.purpuragenda.application.port.out.AccountDataStore;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import com.purpuragenda.domain.booking.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** One private read snapshot establishes administrator authority before searching identities or their bookings. */
public final class PostgresAdministratorCustomers implements AdministratorCustomerQuery {
    private static final String CUSTOMER="SELECT c.id,c.email_key,EXISTS(SELECT 1 FROM customer_account a WHERE a.customer_id=c.id) AS enrolled FROM customer_identity c ";
    private final JdbcTemplate jdbc;private final TransactionTemplate reader;
    public PostgresAdministratorCustomers(DataSource source) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(source));var manager=new DataSourceTransactionManager(source);manager.setEnforceReadOnly(true);
        reader=new TransactionTemplate(manager);reader.setReadOnly(true);reader.setTimeout(15);reader.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);reader.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    private <T>T read(SessionBinding session,Function<Instant,T> work) {
        Objects.requireNonNull(session);
        try {return reader.execute(status->{jdbc.execute("SET LOCAL lock_timeout='2s'");jdbc.execute("SET LOCAL statement_timeout='5s'");
            Instant now=jdbc.queryForObject("SELECT clock_timestamp()",(r,n)->r.getTimestamp(1).toInstant());PersistedSession.administrator(jdbc,session,now);return work.apply(now);
        });}catch(Failure denial){throw denial;}catch(RuntimeException unavailable){throw new Failure(Failure.Kind.STORAGE_FAILURE);}
    }
    @Override public Page search(SessionBinding session,Search request) {
        Objects.requireNonNull(request);
        return read(session,now->{
            var args=new ArrayList<Object>(List.of(request.query(),request.query(),request.query(),request.query(),request.query()));
            String after="";if(request.after()!=null){after=" AND c.id>?";args.add(request.after());}args.add(request.limit()+1);
            // POSITION is literal: search terms cannot introduce LIKE wildcards or SQL syntax.
            var rows=jdbc.query(CUSTOMER+"""
                WHERE (position(lower(?) in lower(c.email_key))>0
                  OR EXISTS(SELECT 1 FROM customer_profile p WHERE p.customer_id=c.id AND position(lower(?) in lower(concat_ws(' ',p.document->>'firstName',p.document->>'lastName')))>0)
                  OR EXISTS(SELECT 1 FROM booking b JOIN booking_snapshot s ON s.booking_id=b.id WHERE b.customer_id=c.id AND
                    (position(lower(?) in lower(concat_ws(' ',s.document->'intake'->>'firstName',s.document->'intake'->>'lastName')))>0
                      OR position(lower(?) in lower(b.delivery_email))>0 OR position(lower(?) in lower(b.reference))>0)))
                """+after+" ORDER BY c.id LIMIT ?",(r,n)->new Customer(r.getObject(1,UUID.class),r.getString(2),r.getBoolean(3)),args.toArray());
            boolean more=rows.size()>request.limit();var visible=more?rows.subList(0,request.limit()):rows;return new Page(visible,more?visible.getLast().id():null,now);
        });
    }
    @Override public Bookings bookings(SessionBinding session,UUID customerId,AdministratorHistoryQuery.Request request) {
        Objects.requireNonNull(customerId);Objects.requireNonNull(request);
        return read(session,now->{
            Customer customer=jdbc.query(CUSTOMER+" WHERE c.id=?",(r,n)->new Customer(r.getObject(1,UUID.class),r.getString(2),r.getBoolean(3)),customerId).stream().findFirst().orElseThrow(()->new Failure(Failure.Kind.NOT_FOUND));
            var args=new ArrayList<Object>(List.of(Timestamp.from(now),customerId));String cursor="";
            if(request.before()!=null){cursor=" AND (b.start_at,b.id)<(?,?)";args.add(Timestamp.from(request.before().time()));args.add(request.before().id());}args.add(request.limit()+1);
            var rows=jdbc.query("""
                SELECT b.id,b.reference,b.state,b.start_at,b.session_end,(b.state='PENDING_CONFIRMATION' AND b.expires_at<=?),
                  s.document->'service'->>'name',(s.document->'service'->'price'->>'minor')::bigint,s.document->'service'->'price'->>'currency'
                FROM booking b JOIN booking_snapshot s ON s.booking_id=b.id WHERE b.customer_id=?
                """+cursor+" ORDER BY b.start_at DESC,b.id DESC LIMIT ?",(r,n)->new AccountDataStore.BookingSummary(r.getObject(1,UUID.class),r.getString(2),Booking.State.valueOf(r.getString(3)),
                    r.getTimestamp(4).toInstant(),r.getTimestamp(5).toInstant(),r.getBoolean(6),r.getString(7),new Money(r.getLong(8),r.getString(9))),args.toArray());
            boolean more=rows.size()>request.limit();var visible=more?rows.subList(0,request.limit()):rows;
            var last=visible.isEmpty()?null:visible.getLast();return new Bookings(customer,new AccountDataStore.History(visible,more?new AccountDataStore.Cursor(last.start(),last.id()):null,now));
        });
    }
}
