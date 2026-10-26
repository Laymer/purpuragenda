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
                SELECT b.expiry_ceiling,o.released_at,n.lease_token,q.customer_id,
                  g.issued_at,g.browser_hash,g.parent_grant_id,r.security_version,r.request_hash,u.security_version,c.revoked_at,
                  s.id,s.token_hash,s.browser_hash,s.csrf_hash,s.kind,s.grant_id,s.customer_id,s.account_id,s.admin_id,
                  s.admin_security_version,s.issued_at,s.last_seen_at,s.absolute_expires_at,s.authenticated_at,s.revoked_at,
                  a.id,a.admin_id,a.token_hash,a.purpose,a.browser_hash,a.request_hash,a.security_version,
                  a.issued_at,a.expires_at,a.consumed_at,a.revoked_at,
                  t.key,t.kind,t.key_id,t.count,t.window_started_at,t.reset_at,
                  ca.login_email,ca.password_hash,ca.credential_version,ca.password_changed_at
                FROM booking b,booking_occupancy o,notification_job n,request_charge q,
                  access_grant g,admin_reenrollment_grant r,admin_user u,admin_recovery_code c,
                  app_session s,admin_authentication_proof a,rate_limit_bucket t,customer_account ca LIMIT 0
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
