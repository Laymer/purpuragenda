package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.application.port.out.SchedulingUnitOfWork;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.boot.health.contributor.*;

/** No private details or restricted Liquibase metadata enter health responses. */
public final class DatabaseReadiness implements HealthIndicator {
    private final DataSource source;
    private final SchedulingUnitOfWork scheduling;
    public DatabaseReadiness(DataSource source,SchedulingUnitOfWork scheduling) {
        this.source=Objects.requireNonNull(source);this.scheduling=Objects.requireNonNull(scheduling);
    }
    @Override public Health health() {
        try {
            if(!schemaReady()) return Health.down().build();
            scheduling.read(view->{view.policy();view.calendar();return null;});
            return Health.up().build();
        } catch(Exception failure) { return Health.down().build(); }
    }
    private boolean schemaReady() throws java.sql.SQLException {
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            statement.setQueryTimeout(2);
            // Parse the currently required application columns without reading any customer rows.
            try(var shape=statement.executeQuery("""
                SELECT b.expiry_ceiling,o.released_at,n.lease_token,q.customer_id
                FROM booking b,booking_occupancy o,notification_job n,request_charge q LIMIT 0
                """)) { if(shape.next()) throw new IllegalStateException(); }
            try(var rows=statement.executeQuery("""
                SELECT NOT c.maintenance FROM operation_control c
                JOIN scheduling_resource r ON r.id=c.id JOIN business_settings s ON s.id=r.id
                JOIN policy_version p ON p.version=s.policy_version WHERE c.id=1
                """)) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }
}
