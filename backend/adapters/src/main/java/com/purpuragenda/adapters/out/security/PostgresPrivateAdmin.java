package com.purpuragenda.adapters.out.security;

import com.purpuragenda.application.port.out.PrivateAdminStore;
import com.purpuragenda.domain.customer.Input;
import java.sql.*;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import static com.purpuragenda.domain.DomainFailure.require;

/** Not composed into HTTP/startup. Composition must supply a dedicated TOTP key. */
public final class PostgresPrivateAdmin implements PrivateAdminStore {
    private final DataSource source;
    private final Secrets totpSecrets;
    public PostgresPrivateAdmin(DataSource source,Secrets totpSecrets) {
        this.source=Objects.requireNonNull(source);this.totpSecrets=Objects.requireNonNull(totpSecrets);
    }
    @Override public Result createUnenrolled(String emailKey,String passwordHash) {
        require(Input.email(emailKey).equals(emailKey) && Argon2PasswordHash.validEncoding(passwordHash), "INVALID_ADMIN_BOOTSTRAP");
        // Direct connection ownership keeps provider exceptions out of transaction framework logging.
        try(Connection connection=source.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);connection.setAutoCommit(false);
            try { Result result=create(connection,emailKey,passwordHash);connection.commit();return result; }
            catch(SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch(SQLException ignored) { }
                throw new IllegalStateException("ADMIN_BOOTSTRAP_UNAVAILABLE");
            }
        } catch(SQLException | RuntimeException unavailable) { throw new IllegalStateException("ADMIN_BOOTSTRAP_UNAVAILABLE"); }
    }
    private Result create(Connection connection,String emailKey,String passwordHash) throws SQLException {
        try(var statement=connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout='2s'");statement.execute("SET LOCAL statement_timeout='5s'");
            // Serializes absent-row races; disabled/history rows must also block replacement.
            statement.execute("LOCK TABLE admin_user IN SHARE ROW EXCLUSIVE MODE");
            try(var rows=statement.executeQuery("SELECT EXISTS(SELECT 1 FROM admin_user)")) {
                rows.next();if(rows.getBoolean(1))return Result.ALREADY_EXISTS;
            }
        }
        byte[] secret=Totp.newSecret();
        try {
            UUID id=UUID.randomUUID();String encrypted=totpSecrets.encrypt(Base64.getEncoder().encodeToString(secret));
            try(var insert=connection.prepareStatement("INSERT INTO admin_user(id,email_key,password_hash,totp_secret,last_totp_step,enrolled,enabled) VALUES (?,?,?,?, -1,false,true)")) {
                insert.setObject(1,id);insert.setString(2,emailKey);insert.setString(3,passwordHash);insert.setString(4,encrypted);insert.executeUpdate();
            }
            try(var audit=connection.prepareStatement("INSERT INTO audit_event(id,actor_id,actor_kind,type,occurred_at) VALUES (?,?,'PRIVATE_BOOTSTRAP','ADMIN_BOOTSTRAPPED',clock_timestamp())")) {
                audit.setObject(1,UUID.randomUUID());audit.setObject(2,id);audit.executeUpdate();
            }
            return Result.CREATED;
        } finally { Arrays.fill(secret,(byte)0); }
    }
}
