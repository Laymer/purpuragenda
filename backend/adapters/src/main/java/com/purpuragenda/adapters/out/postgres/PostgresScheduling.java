package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.application.port.out.SchedulingUnitOfWork;
import com.purpuragenda.application.port.out.SchedulingMaintenanceUnitOfWork;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.scheduling.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.*;
import javax.sql.DataSource;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Only PostgreSQL time is authoritative; there is no operational clock override. */
public final class PostgresScheduling implements SchedulingUnitOfWork, SchedulingMaintenanceUnitOfWork {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final TransactionTemplate reader;
    private final PostgresBookingStore store;
    private final PostgresQuotaStore quotas;
    public PostgresScheduling(DataSource source) { this(source,Set.of("zoom.us")); }
    public PostgresScheduling(DataSource source,Set<String> meetingHosts) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(source));
        if(meetingHosts==null || meetingHosts.isEmpty()) throw new IllegalArgumentException("MEETING_HOSTS_REQUIRED");
        store=new PostgresBookingStore(jdbc,Set.copyOf(meetingHosts));
        quotas=new PostgresQuotaStore(jdbc,store);
        DataSourceTransactionManager manager=new DataSourceTransactionManager(source); manager.setEnforceReadOnly(true);
        transaction=new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(15);
        reader=new TransactionTemplate(manager);
        reader.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reader.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reader.setReadOnly(true); reader.setTimeout(15);
    }
    @Override public <T> T read(Function<View,T> work) {
        Objects.requireNonNull(work);
        try { return reader.execute(status->work.apply(new ViewState(capture(),status))); }
        catch(DataAccessException | TransactionException failure) { throw new Failure(Failure.Kind.STORAGE_FAILURE); }
    }
    private Instant capture() { return jdbc.queryForObject("SELECT clock_timestamp()",(row,n)->row.getTimestamp(1).toInstant()); }
    @Override public Receipt execute(CommandKey key, Function<Transaction,Receipt> work) {
        Objects.requireNonNull(key); Objects.requireNonNull(work);
        return resolved(now -> new Resolved(key,Function.identity(),work));
    }
    /** Adapter-internal authority resolution shares this exact resource lock, time and transaction. */
    record Resolved(CommandKey key,Function<Receipt,Receipt> replay,Function<Transaction,Receipt> work) {
        Resolved { Objects.requireNonNull(key);Objects.requireNonNull(replay);Objects.requireNonNull(work); }
    }
    Receipt resolved(Function<Instant,Resolved> authorize) {
        Objects.requireNonNull(authorize);
        return locked(tx->{
            Instant now=tx.now();Resolved resolved=Objects.requireNonNull(authorize.apply(now));CommandKey key=resolved.key();
            List<Receipt> existing=jdbc.query("""
                SELECT fingerprint,receipt->>'code' AS code,receipt->>'entityId' AS entity,
                  receipt->>'version' AS version,jsonb_exists_all(receipt,ARRAY['code','entityId','version']) AS complete
                FROM idempotency_record WHERE scope=? AND key=?
                """,(row,n)->decode(row,key),key.scope().storageKey(),key.key());
            if(!existing.isEmpty())return Objects.requireNonNull(resolved.replay().apply(existing.getFirst()));
            Receipt receipt=Objects.requireNonNull(resolved.work().apply(tx));
            jdbc.update("""
                INSERT INTO idempotency_record(scope,key,fingerprint,receipt,created_at)
                VALUES(?,?,?,jsonb_build_object('code',?::text,'entityId',?::text,'version',?::bigint),?)
                """,key.scope().storageKey(),key.key(),key.fingerprint(),receipt.code().name(),
                    receipt.entityId()==null?null:receipt.entityId().toString(),receipt.version(),Timestamp.from(now));
            return receipt;
        });
    }
    @Override public int maintain(ToIntFunction<Transaction> work) {
        Objects.requireNonNull(work);
        return locked(tx->work.applyAsInt(tx));
    }
    /** Commands and maintenance cannot diverge in lock order, authoritative time, retry or rollback behavior. */
    // Package-private for privacy operations sharing the same lock/time/maintenance boundary.
    <T>T locked(Function<Transaction,T> work) {
        for(int attempt=0;;attempt++) {
            try {
                return transaction.execute(status->{
                    jdbc.execute("SET LOCAL lock_timeout='5s'");
                    jdbc.queryForObject("SELECT id FROM scheduling_resource WHERE id=1 FOR UPDATE",Integer.class);
                    Instant now=capture();
                    if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT maintenance FROM operation_control WHERE id=1",Boolean.class)))
                        throw new Failure(Failure.Kind.MAINTENANCE);
                    return work.apply(new Tx(now,status));
                });
            } catch(RuntimeException failure) {
                SQLException sql=sql(failure);
                if(sql!=null) {
                    if("40P01".equals(sql.getSQLState()) && attempt<2) continue;
                    if("40P01".equals(sql.getSQLState()) || "55P03".equals(sql.getSQLState()) || "57014".equals(sql.getSQLState())) throw new Failure(Failure.Kind.RETRY_LATER);
                    throw new Failure(Failure.Kind.STORAGE_FAILURE);
                }
                if(failure instanceof DataAccessException || failure instanceof TransactionException) throw new Failure(Failure.Kind.STORAGE_FAILURE);
                throw failure;
            }
        }
    }
    private Receipt decode(ResultSet row,CommandKey key) throws SQLException {
        try {
            String fingerprint=row.getString("fingerprint");
            if(!fingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
            if(!key.fingerprint().equals(fingerprint)) return Receipt.failure(Code.IDEMPOTENCY_KEY_REUSED);
            if(!row.getBoolean("complete")) throw new IllegalArgumentException();
            String entity=row.getString("entity");
            return new Receipt(Code.valueOf(row.getString("code")),entity==null?null:UUID.fromString(entity),Long.parseLong(row.getString("version")));
        } catch(IllegalArgumentException | NullPointerException | com.purpuragenda.domain.DomainFailure invalid) {
            throw new Failure(Failure.Kind.STORAGE_FAILURE);
        }
    }
    private class ViewState implements View {
        private final Instant now;
        protected final TransactionStatus status;
        private final Thread owner=Thread.currentThread();
        ViewState(Instant now,TransactionStatus status) { this.now=now;this.status=status; }
        protected void active() { if(Thread.currentThread()!=owner || status.isCompleted()) throw new Failure(Failure.Kind.STORAGE_FAILURE); }
        public Instant now() { active();return now; }
        public List<ServiceDefinition> services() { active();return store.services(); }
        public SchedulingPolicy policy() { active();return store.policy(); }
        public AvailabilityCalendar calendar() { active();return store.calendar(); }
        public Optional<Booking> booking(UUID id) { active();return store.booking(id); }
        public List<Booking> bookings(ReservationInterval interval) { active();return store.bookings(interval); }
    }
    private final class Tx extends ViewState implements Transaction {
        Tx(Instant now,TransactionStatus status) { super(now,status); }
        public void save(Booking booking) { active();store.save(booking,now()); }
        public com.purpuragenda.application.scheduling.CustomerQuota quota(UUID customer) { active();return quotas.quota(customer,now()); }
        public boolean chargeCreated(Booking booking) { active();return quotas.chargeCreated(booking,now()); }
        public List<Booking> duePending() { active();return store.duePending(now()); }
        public void expiryEffects(UUID bookingId,long expiredVersion) {
            active();
            Integer count=jdbc.queryForObject("SELECT count(*) FROM booking WHERE id=? AND state='EXPIRED' AND version=?",
                    Integer.class,bookingId,expiredVersion);
            if(count==null || count!=1) throw new Failure(Failure.Kind.STORAGE_FAILURE);
            jdbc.update("""
                INSERT INTO refund_obligation(receipt_id,due_minor,reason)
                SELECT id,amount_minor,'EXPIRED' FROM payment_entry WHERE booking_id=? AND kind='RECEIPT'
                ON CONFLICT(receipt_id) DO NOTHING
                """,bookingId);
            UUID event=UUID.nameUUIDFromBytes(("booking-expired/"+bookingId+"/"+expiredVersion).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jdbc.update("""
                INSERT INTO audit_event(id,booking_id,actor_kind,type,occurred_at,version)
                VALUES(?,?,'SYSTEM','EXPIRED',?,?) ON CONFLICT(id) DO NOTHING
                """,event,bookingId,Timestamp.from(now()),expiredVersion);
            jdbc.update("""
                INSERT INTO outbox_event(id,booking_id,type,version,created_at)
                VALUES(?,?,'EXPIRED',?,?) ON CONFLICT(id) DO NOTHING
                """,event,bookingId,expiredVersion,Timestamp.from(now()));
        }
        public Receipt attempt(Supplier<Receipt> mutation) {
            active();
            Object savepoint=status.createSavepoint();
            try {
                Receipt receipt=Objects.requireNonNull(mutation.get());
                if(receipt.success()) {
                    jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
                    jdbc.execute("SET CONSTRAINTS ALL DEFERRED");
                } else status.rollbackToSavepoint(savepoint);
                return receipt;
            } catch(RuntimeException failure) {
                status.rollbackToSavepoint(savepoint);
                SQLException sql=sql(failure);
                if(sql instanceof PSQLException pg && "23P01".equals(sql.getSQLState())
                        && pg.getServerErrorMessage()!=null && "practitioner_no_overlap".equals(pg.getServerErrorMessage().getConstraint())) {
                    return Receipt.failure(Code.SLOT_UNAVAILABLE);
                }
                if(sql instanceof PSQLException pg && "23505".equals(sql.getSQLState())
                        && pg.getServerErrorMessage()!=null && "booking_meeting_id_key".equals(pg.getServerErrorMessage().getConstraint())) {
                    return Receipt.failure(Code.MEETING_IN_USE);
                }
                throw failure;
            } finally { status.releaseSavepoint(savepoint); }
        }
    }
    private static SQLException sql(Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) if(cause instanceof SQLException sql) return sql;
        return null;
    }
}
