package com.purpuragenda.adapters.out.security;

import com.purpuragenda.application.port.out.AccountPasswordUnitOfWork;
import com.purpuragenda.application.port.out.AccountAuthenticationUnitOfWork.Credential;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;

/** Lock order matches authentication exchange and customer mutations: account, then its requesting session. */
public final class PostgresAccountPassword implements AccountPasswordUnitOfWork {
    private final DataSource source;
    public PostgresAccountPassword(DataSource source) { this.source=Objects.requireNonNull(source); }
    @Override public <T>T locked(String email,SessionBinding session,Function<Account,T> work) {
        Objects.requireNonNull(email);Objects.requireNonNull(session);Objects.requireNonNull(work);
        try(var connection=source.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);connection.setAutoCommit(false);
            try(var statement=connection.createStatement()) { statement.execute("SET LOCAL lock_timeout='2s'");statement.execute("SET LOCAL statement_timeout='5s'"); }
            try(var account=load(connection,email,session)) {
                try { T result=work.apply(account);connection.commit();return result; }
                catch(SQLException | RuntimeException failure) { try { connection.rollback(); } catch(SQLException ignored) { }throw failure; }
            }
        } catch(SQLException | RuntimeException failure) { throw unavailable(); }
    }
    private static Context load(Connection connection,String email,SessionBinding session) throws SQLException {
        Credential credential=null;Instant enrolled=null,changed=null;
        try(var query=connection.prepareStatement("""
            SELECT a.id,a.customer_id,a.password_hash,a.credential_version,a.enrolled_at,a.password_changed_at
            FROM customer_account a JOIN customer_identity i ON i.id=a.customer_id AND i.email_key=a.login_email
            WHERE a.login_email=? FOR UPDATE OF a
            """)) {
            query.setString(1,email);
            try(var row=query.executeQuery()) {
                if(row.next()) {
                    credential=new Credential(row.getObject(1,UUID.class),row.getObject(2,UUID.class),row.getString(3),row.getLong(4));
                    enrolled=time(row,5);changed=time(row,6);
                }
            }
        }
        String kind=null;UUID owner=null,customer=null;Long epoch=null;Instant issued=null,seen=null,expires=null,authenticated=null,revoked=null;
        try(var query=connection.prepareStatement("""
            SELECT kind,account_id,customer_id,account_credential_version,issued_at,last_seen_at,absolute_expires_at,authenticated_at,revoked_at
            FROM app_session WHERE id=? AND browser_hash=? FOR UPDATE
            """)) {
            query.setObject(1,session.sessionId());query.setString(2,session.browserHash());
            try(var row=query.executeQuery()) {
                if(row.next()) { kind=row.getString(1);owner=row.getObject(2,UUID.class);customer=row.getObject(3,UUID.class);epoch=row.getObject(4,Long.class);
                    issued=time(row,5);seen=time(row,6);expires=time(row,7);authenticated=time(row,8);revoked=time(row,9); }
            }
        }
        Instant now;
        try(var query=connection.createStatement();var row=query.executeQuery("SELECT clock_timestamp()")) { row.next();now=row.getTimestamp(1).toInstant(); }
        if(credential!=null && (!"ACCOUNT".equals(kind) || !credential.accountId().equals(owner) || !credential.customerId().equals(customer)
                || epoch==null || epoch!=credential.version() || revoked!=null || issued.isAfter(now) || !now.isBefore(expires)
                || seen.isAfter(now) || !now.isBefore(seen.plusSeconds(1800)) || authenticated==null || authenticated.isAfter(now)
                || enrolled==null || enrolled.isAfter(now) || changed==null || changed.isAfter(now)))credential=null;
        if(credential!=null && !Argon2PasswordHash.validEncoding(credential.passwordHash()))throw unavailable();
        return new Context(connection,credential,now);
    }
    private static Instant time(ResultSet row,int column) throws SQLException { var stamp=row.getTimestamp(column);return stamp==null?null:stamp.toInstant(); }
    private static IllegalStateException unavailable() { return new IllegalStateException("ACCOUNT_PASSWORD_CHANGE_UNAVAILABLE"); }
    private static final class Context implements Account,AutoCloseable {
        private final Connection connection;private final Credential credential;private final Instant now;
        private final Thread owner=Thread.currentThread();private boolean active=true,replaced;
        Context(Connection connection,Credential credential,Instant now) { this.connection=connection;this.credential=credential;this.now=now; }
        private void check() { if(!active || owner!=Thread.currentThread())throw new IllegalStateException("ACCOUNT_PASSWORD_CONTEXT_CLOSED"); }
        @Override public Optional<Credential> credential() { check();return Optional.ofNullable(credential); }
        @Override public void replacePassword(String encoded) {
            check();if(credential==null || replaced || !Argon2PasswordHash.validEncoding(encoded))throw unavailable();
            try {
                if(update("UPDATE customer_account SET password_hash=?,password_changed_at=?,credential_version=credential_version+1 WHERE id=? AND credential_version=?",encoded,Timestamp.from(now),credential.accountId(),credential.version())!=1)throw unavailable();
                update("UPDATE app_session SET revoked_at=GREATEST(issued_at,?) WHERE account_id=? AND revoked_at IS NULL",Timestamp.from(now),credential.accountId());
                update("UPDATE account_authentication_proof SET revoked_at=GREATEST(issued_at,?) WHERE account_id=? AND consumed_at IS NULL AND revoked_at IS NULL",Timestamp.from(now),credential.accountId());
                update("INSERT INTO audit_event(id,actor_id,actor_kind,type,occurred_at) VALUES(?,?,'ACCOUNT','ACCOUNT_PASSWORD_CHANGED',?)",UUID.randomUUID(),credential.accountId(),Timestamp.from(now));
                replaced=true;
            } catch(SQLException failure) { throw unavailable(); }
        }
        private int update(String sql,Object...arguments) throws SQLException {
            try(var statement=connection.prepareStatement(sql)) { for(int i=0;i<arguments.length;i++)statement.setObject(i+1,arguments[i]);return statement.executeUpdate(); }
        }
        @Override public void close() { active=false; }
    }
    @Override public String toString() { return "AccountPasswordStore[REDACTED]"; }
}
