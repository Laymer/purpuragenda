package com.purpuragenda.adapters.out.email;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Short independent notification transactions; no scheduling lock and no transport work. */
final class NotificationJdbc {
    final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    NotificationJdbc(DataSource source) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(source));
        transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(15);
    }
    <T>T transaction(Supplier<T> work) {
        try {
            return transaction.execute(status->{
                jdbc.execute("SET LOCAL lock_timeout='2s'");
                jdbc.execute("SET LOCAL statement_timeout='5s'");
                return work.get();
            });
        } catch(RuntimeException failure) { throw new IllegalStateException("NOTIFICATION_STORAGE_FAILED"); }
    }
    Instant now() { return jdbc.queryForObject("SELECT clock_timestamp()",(row,n)->row.getTimestamp(1).toInstant()); }
}
