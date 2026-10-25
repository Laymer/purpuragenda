package com.purpuragenda.adapters.out.security;

import com.purpuragenda.application.port.out.AdminMfaUnitOfWork;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;

/** Direct transaction ownership prevents arbitrary provider causes crossing framework logging. */
public final class PostgresAdminMfa implements AdminMfaUnitOfWork {
    private final DataSource source;
    private final Secrets secrets;
    public PostgresAdminMfa(DataSource source,Secrets secrets) {
        this.source=Objects.requireNonNull(source);this.secrets=Objects.requireNonNull(secrets);
    }
    @Override public <T> T locked(String emailKey,Function<Admin,T> work) {
        try(Connection connection=source.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);connection.setAutoCommit(false);
            try(Context admin=load(connection,emailKey)) {
                try { T result=work.apply(admin);connection.commit();return result; }
                catch(SQLException | RuntimeException failure) {
                    try { connection.rollback(); } catch(SQLException ignored) { }
                    throw unavailable();
                }
            }
        } catch(SQLException | RuntimeException failure) { throw unavailable(); }
    }
    private Context load(Connection connection,String emailKey) throws SQLException {
        UUID id=null;String hash=null,encrypted=null;boolean enrolled=false;long previous=-1;
        try(var statement=connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout='2s'");statement.execute("SET LOCAL statement_timeout='5s'");
        }
        try(var query=connection.prepareStatement("SELECT id,password_hash,totp_secret,enrolled,last_totp_step FROM admin_user WHERE email_key=? AND enabled FOR UPDATE")) {
            query.setString(1,emailKey);
            try(var row=query.executeQuery()) {
                if(row.next()) { id=row.getObject(1,UUID.class);hash=row.getString(2);encrypted=row.getString(3);enrolled=row.getBoolean(4);previous=row.getLong(5); }
            }
        }
        Instant now;
        try(var query=connection.createStatement();var row=query.executeQuery("SELECT clock_timestamp()")) { row.next();now=row.getTimestamp(1).toInstant(); }
        Credential credential=null;boolean initial=false;
        if(id!=null) {
            byte[] raw=Base64.getDecoder().decode(secrets.decrypt(encrypted));
            try {
                if(raw.length!=20 || previous < -1 || !Argon2PasswordHash.validEncoding(hash))throw unavailable();
                credential=new Credential(id,hash,enrolled,previous,new Secret(raw));
            } finally { Arrays.fill(raw,(byte)0); }
            try(var query=connection.prepareStatement("SELECT NOT EXISTS(SELECT 1 FROM admin_recovery_code WHERE admin_id=?) AND NOT EXISTS(SELECT 1 FROM audit_event WHERE actor_id=? AND type='ADMIN_MFA_ENROLLED')")) {
                query.setObject(1,id);query.setObject(2,id);try(var row=query.executeQuery()) { row.next();initial=row.getBoolean(1); }
            }
        }
        return new Context(connection,now,credential,initial);
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("ADMIN_MFA_UNAVAILABLE"); }
    private static final class Context implements Admin,AutoCloseable {
        private final Connection connection;
        private final Instant captured;
        private final Credential credential;
        private final boolean initial;
        private final Thread owner=Thread.currentThread();
        private boolean active=true;
        Context(Connection connection,Instant captured,Credential credential,boolean initial) { this.connection=connection;this.captured=captured;this.credential=credential;this.initial=initial; }
        private void check() { if(!active || owner!=Thread.currentThread())throw new IllegalStateException("ADMIN_MFA_CONTEXT_CLOSED"); }
        @Override public Instant now() { check();return captured; }
        @Override public Optional<Credential> credential() { check();return Optional.ofNullable(credential); }
        @Override public boolean initialEnrollment() { check();return initial; }
        @Override public void enroll(long step,List<String> hashes) {
            check();if(credential==null || credential.enrolled() || !initial || hashes.size()!=10 || new HashSet<>(hashes).size()!=10)throw unavailable();
            for(String hash:hashes)if(!hash.matches("[a-f0-9]{64}"))throw unavailable();
            update(step,"UPDATE admin_user SET enrolled=true,last_totp_step=? WHERE id=? AND NOT enrolled AND last_totp_step<?");
            try(var insert=connection.prepareStatement("INSERT INTO admin_recovery_code(id,admin_id,code_hash) VALUES (?,?,?)")) {
                for(String hash:hashes) { insert.setObject(1,UUID.randomUUID());insert.setObject(2,credential.id());insert.setString(3,hash);insert.executeUpdate(); }
            } catch(SQLException failure) { throw unavailable(); }
            audit("ADMIN_MFA_ENROLLED");
        }
        @Override public void acceptStep(long step) {
            check();if(credential==null || !credential.enrolled())throw unavailable();
            update(step,"UPDATE admin_user SET last_totp_step=? WHERE id=? AND enrolled AND last_totp_step<?");audit("ADMIN_MFA_VERIFIED");
        }
        private void update(long step,String sql) {
            if(step<0 || step<=credential.acceptedStep())throw unavailable();
            try(var update=connection.prepareStatement(sql)) {
                update.setLong(1,step);update.setObject(2,credential.id());update.setLong(3,step);
                if(update.executeUpdate()!=1)throw unavailable();
            } catch(SQLException failure) { throw unavailable(); }
        }
        private void audit(String type) {
            try(var insert=connection.prepareStatement("INSERT INTO audit_event(id,actor_id,actor_kind,type,occurred_at) VALUES (?,?,'ADMIN_FACTOR',?,?)")) {
                insert.setObject(1,UUID.randomUUID());insert.setObject(2,credential.id());insert.setString(3,type);insert.setTimestamp(4,Timestamp.from(captured));insert.executeUpdate();
            } catch(SQLException failure) { throw unavailable(); }
        }
        @Override public void close() { active=false;if(credential!=null)credential.secret().close(); }
    }
}
