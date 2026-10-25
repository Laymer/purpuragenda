package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.application.port.out.SchedulingUnitOfWork;
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
public final class PostgresScheduling implements SchedulingUnitOfWork {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public PostgresScheduling(DataSource source) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(source));
        transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(15);
    }
    @Override public Receipt execute(CommandKey key, Function<Transaction,Receipt> work) {
        Objects.requireNonNull(key); Objects.requireNonNull(work);
        for(int attempt=0;;attempt++) {
            try {
                return transaction.execute(status->{
                    jdbc.execute("SET LOCAL lock_timeout='5s'");
                    jdbc.queryForObject("SELECT id FROM scheduling_resource WHERE id=1 FOR UPDATE",Integer.class);
                    Instant now=jdbc.queryForObject("SELECT clock_timestamp()",(row,n)->row.getTimestamp(1).toInstant());
                    List<Receipt> existing=jdbc.query("""
                        SELECT fingerprint,receipt->>'code' AS code,receipt->>'entityId' AS entity,
                          receipt->>'version' AS version,receipt ?& ARRAY['code','entityId','version'] AS complete
                        FROM idempotency_record WHERE scope=? AND key=?
                        """,(row,n)->decode(row,key),key.scope().storageKey(),key.key());
                    if(!existing.isEmpty()) return existing.getFirst();
                    Receipt receipt=Objects.requireNonNull(work.apply(new Tx(now,status)));
                    jdbc.update("""
                        INSERT INTO idempotency_record(scope,key,fingerprint,receipt,created_at)
                        VALUES(?,?,?,jsonb_build_object('code',?::text,'entityId',?::text,'version',?::bigint),?)
                        """,key.scope().storageKey(),key.key(),key.fingerprint(),receipt.code().name(),
                            receipt.entityId()==null?null:receipt.entityId().toString(),receipt.version(),Timestamp.from(now));
                    return receipt;
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
    private final class Tx implements Transaction {
        private final Instant now;
        private final TransactionStatus status;
        Tx(Instant now,TransactionStatus status) { this.now=now; this.status=status; }
        public Instant now() { return now; }
        public Receipt attempt(Supplier<Receipt> mutation) {
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
                throw failure;
            } finally { status.releaseSavepoint(savepoint); }
        }
    }
    private static SQLException sql(Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) if(cause instanceof SQLException sql) return sql;
        return null;
    }
}
