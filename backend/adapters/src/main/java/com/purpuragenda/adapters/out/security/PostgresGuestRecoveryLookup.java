package com.purpuragenda.adapters.out.security;

import com.purpuragenda.application.port.out.GuestRecoveryLookup;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** Advisory recovery lookup; redemption locks and rechecks the current contact before granting access. */
public final class PostgresGuestRecoveryLookup implements GuestRecoveryLookup {
    private final DataSource source;
    public PostgresGuestRecoveryLookup(DataSource source) { this.source=Objects.requireNonNull(source); }
    @Override public Optional<UUID> find(Request request) {
        Objects.requireNonNull(request);
        try(var c=source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setReadOnly(true);c.setAutoCommit(false);
            try(var limits=c.createStatement()) {
                limits.execute("SET LOCAL lock_timeout='2s'");limits.execute("SET LOCAL statement_timeout='5s'");
            }
            Optional<UUID> result;
            try(var query=c.prepareStatement("SELECT id FROM booking WHERE reference=? AND delivery_email=?")) {
                query.setString(1,request.reference());query.setString(2,request.email());
                try(var rows=query.executeQuery()) { result=rows.next()?Optional.of(rows.getObject(1,UUID.class)):Optional.empty(); }
            }
            c.commit();return result;
        } catch(SQLException | RuntimeException failure) { throw new IllegalStateException("GUEST_RECOVERY_UNAVAILABLE"); }
    }
    @Override public String toString() { return "GuestRecoveryLookup[REDACTED]"; }
}
