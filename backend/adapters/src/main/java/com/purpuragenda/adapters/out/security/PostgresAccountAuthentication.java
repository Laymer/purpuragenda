package com.purpuragenda.adapters.out.security;

import com.purpuragenda.application.port.out.AccountAuthenticationUnitOfWork;
import com.purpuragenda.application.port.out.SessionStore.ProofBinding;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;

/** The credential row remains locked from password verification through single-use proof issuance. */
public final class PostgresAccountAuthentication implements AccountAuthenticationUnitOfWork {
    private final DataSource source;
    public PostgresAccountAuthentication(DataSource source) { this.source=Objects.requireNonNull(source); }
    @Override public <T>T locked(String email,Function<Account,T> work) {
        Objects.requireNonNull(work);
        try(var connection=source.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);connection.setAutoCommit(false);
            try(var statement=connection.createStatement()) { statement.execute("SET LOCAL lock_timeout='2s'");statement.execute("SET LOCAL statement_timeout='5s'"); }
            try(var account=load(connection,email)) {
                try { T result=work.apply(account);connection.commit();return result; }
                catch(SQLException | RuntimeException failure) { try { connection.rollback(); } catch(SQLException ignored) { }throw failure; }
            }
        } catch(SQLException | RuntimeException failure) { throw unavailable(); }
    }
    private static Context load(Connection connection,String email) throws SQLException {
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
                    enrolled=row.getTimestamp(5).toInstant();var stamp=row.getTimestamp(6);changed=stamp==null?null:stamp.toInstant();
                }
            }
        }
        Instant now;
        try(var query=connection.createStatement();var row=query.executeQuery("SELECT clock_timestamp()")) { row.next();now=row.getTimestamp(1).toInstant(); }
        if(credential!=null && (enrolled.isAfter(now) || changed==null || changed.isAfter(now)))credential=null;
        if(credential!=null && !Argon2PasswordHash.validEncoding(credential.passwordHash()))throw unavailable();
        return new Context(connection,credential,now);
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("ACCOUNT_AUTHENTICATION_UNAVAILABLE"); }
    private static final class Context implements Account,AutoCloseable {
        private final Connection connection;
        private final Credential credential;
        private final Instant captured;
        private final Thread owner=Thread.currentThread();
        private boolean active=true,issued;
        Context(Connection connection,Credential credential,Instant captured) { this.connection=connection;this.credential=credential;this.captured=captured; }
        private void check() { if(!active || owner!=Thread.currentThread())throw new IllegalStateException("ACCOUNT_AUTHENTICATION_CONTEXT_CLOSED"); }
        @Override public Instant now() { check();return captured; }
        @Override public Optional<Credential> credential() { check();return Optional.ofNullable(credential); }
        @Override public Instant issueProof(String hash,ProofBinding binding) {
            check();if(credential==null || issued || hash==null || !hash.matches("[a-f0-9]{64}"))throw unavailable();Objects.requireNonNull(binding);
            Instant expiry=captured.plusSeconds(60);
            try(var insert=connection.prepareStatement("""
                INSERT INTO account_authentication_proof(id,account_id,token_hash,purpose,browser_hash,request_hash,credential_version,issued_at,expires_at)
                VALUES(?,?,?,'ACCOUNT_AUTHENTICATION',?,?,?,?,?)
                """)) {
                insert.setObject(1,UUID.randomUUID());insert.setObject(2,credential.accountId());insert.setString(3,hash);
                insert.setString(4,binding.browserHash());insert.setString(5,binding.requestHash());insert.setLong(6,credential.version());
                insert.setTimestamp(7,Timestamp.from(captured));insert.setTimestamp(8,Timestamp.from(expiry));insert.executeUpdate();
                try(var audit=connection.prepareStatement("INSERT INTO audit_event(id,actor_id,actor_kind,type,occurred_at) VALUES(?,?,'ACCOUNT_FACTOR','ACCOUNT_PASSWORD_VERIFIED',?)")) {
                    audit.setObject(1,UUID.randomUUID());audit.setObject(2,credential.accountId());audit.setTimestamp(3,Timestamp.from(captured));audit.executeUpdate();
                }
                issued=true;return expiry;
            } catch(SQLException failure) { throw unavailable(); }
        }
        @Override public void close() { active=false; }
    }
    @Override public String toString() { return "AccountAuthenticationStore[REDACTED]"; }
}
