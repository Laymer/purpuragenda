package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Persisted customer session rows shared by authorized customer units. A reference alone is never authority. */
record PersistedSession(UUID id,String browser,String kind,UUID grant,UUID customer,UUID account,
                        Instant issued,Instant lastSeen,Instant expires,Instant revoked) {
    static PersistedSession find(JdbcTemplate jdbc,SessionBinding binding,boolean lock) {
        return jdbc.query("SELECT * FROM app_session WHERE id=? AND browser_hash=?"+(lock?" FOR UPDATE":""),(r,n)->new PersistedSession(
                r.getObject("id",UUID.class),r.getString("browser_hash"),r.getString("kind"),r.getObject("grant_id",UUID.class),
                r.getObject("customer_id",UUID.class),r.getObject("account_id",UUID.class),time(r,"issued_at"),time(r,"last_seen_at"),
                time(r,"absolute_expires_at"),time(r,"revoked_at")),binding.sessionId(),binding.browserHash()).stream().findFirst().orElse(null);
    }
    /** Recheck after the row lock: expiry, idle timeout, revocation and unchanged identity all use the captured time. */
    boolean validAfterLock(PersistedSession initial,Instant now) {
        return revoked==null && !issued.isAfter(now) && now.isBefore(expires) && now.isBefore(lastSeen.plusSeconds(1800))
                && kind.equals(initial.kind()) && java.util.Objects.equals(grant,initial.grant())
                && java.util.Objects.equals(account,initial.account()) && java.util.Objects.equals(customer,initial.customer());
    }
    static Instant time(ResultSet row,String column) throws SQLException { Timestamp value=row.getTimestamp(column);return value==null?null:value.toInstant(); }
    static Timestamp stamp(Instant value) { return Timestamp.from(value); }
    static Failure denied() { return new Failure(Failure.Kind.FORBIDDEN); }
    static Failure unauthenticated() { return new Failure(Failure.Kind.AUTHENTICATION_REQUIRED); }
}
