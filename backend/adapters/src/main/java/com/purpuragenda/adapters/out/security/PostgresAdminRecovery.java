package com.purpuragenda.adapters.out.security;

import com.purpuragenda.application.ReenrollmentGrantPolicy.*;
import com.purpuragenda.application.ReenrollmentGrantPolicy;
import com.purpuragenda.application.port.out.AdminRecoveryUnitOfWork;
import com.purpuragenda.application.port.out.AdminMfaUnitOfWork.Secret;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;

/** The administrator row lock fences MFA, recovery and later session proof consumption. */
public final class PostgresAdminRecovery implements AdminRecoveryUnitOfWork {
    private final DataSource source;private final Secrets secrets;
    public PostgresAdminRecovery(DataSource source,Secrets secrets) { this.source=Objects.requireNonNull(source);this.secrets=Objects.requireNonNull(secrets); }
    @Override public <T>T locked(String emailKey,Function<Admin,T> work) {
        try(Connection connection=source.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);connection.setAutoCommit(false);
            try(Context context=new Context(connection,emailKey)) {
                try { T result=work.apply(context);connection.commit();return result; }
                catch(SQLException | RuntimeException failure) { try { connection.rollback(); } catch(SQLException ignored) { }throw unavailable(); }
            }
        } catch(SQLException | RuntimeException failure) { throw unavailable(); }
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("ADMIN_RECOVERY_UNAVAILABLE"); }
    private final class Context implements Admin,AutoCloseable {
        private final Connection connection;private final Thread owner=Thread.currentThread();
        private final Instant captured;private final Identity identity;
        private boolean active=true,enrolled;private long version;private String encrypted;private UUID proven;
        Context(Connection connection,String email) throws SQLException {
            this.connection=connection;Identity loaded=null;
            try(var statement=connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout='2s'");statement.execute("SET LOCAL statement_timeout='5s'");
            }
            try(var query=connection.prepareStatement("SELECT id,password_hash,enrolled,security_version,totp_secret FROM admin_user WHERE email_key=? AND enabled FOR UPDATE")) {
                query.setString(1,email);
                try(var row=query.executeQuery()) { if(row.next()) { loaded=new Identity(row.getObject(1,UUID.class),row.getString(2));enrolled=row.getBoolean(3);version=row.getLong(4);encrypted=row.getString(5); } }
            }
            identity=loaded;
            try(var query=connection.createStatement();var row=query.executeQuery("SELECT clock_timestamp()")) { row.next();captured=row.getTimestamp(1).toInstant(); }
        }
        private void check() { if(!active || owner!=Thread.currentThread())throw new IllegalStateException("ADMIN_RECOVERY_CONTEXT_CLOSED"); }
        @Override public Instant now() { check();return captured; }
        @Override public Optional<Identity> identity() { check();return Optional.ofNullable(identity); }
        @Override public Optional<Secret> begin(String recoveryHash,Binding binding,String tokenHash) {
            check();if(identity==null)return Optional.empty();
            try(var query=connection.prepareStatement("SELECT id,consumed_at,revoked_at FROM admin_recovery_code WHERE admin_id=? AND code_hash=? FOR UPDATE")) {
                query.setObject(1,identity.id());query.setString(2,recoveryHash);
                try(var row=query.executeQuery()) {
                    if(!row.next() || row.getTimestamp(3)!=null)return Optional.empty();
                    UUID codeId=row.getObject(1,UUID.class);
                    if(row.getTimestamp(2)!=null) {
                        var grant=grant("recovery_code_id",codeId,binding);if(grant.isEmpty())return Optional.empty();
                        update("UPDATE admin_reenrollment_grant SET token_hash=? WHERE id=?",tokenHash,grant.get());
                        audit("ADMIN_RECOVERY_RESUMED");return Optional.of(secret());
                    }
                    byte[] raw=Totp.newSecret();
                    try { encrypted=secrets.encrypt(Base64.getEncoder().encodeToString(raw)); }
                    finally { Arrays.fill(raw,(byte)0); }
                    update("UPDATE admin_recovery_code SET consumed_at=? WHERE id=?",captured,codeId);
                    revokeGrants();
                    update("UPDATE admin_user SET totp_secret=?,last_totp_step=-1,enrolled=false,security_version=security_version+1 WHERE id=?",encrypted,identity.id());
                    version++;enrolled=false;revokeSessions();
                    update("INSERT INTO admin_reenrollment_grant(id,admin_id,recovery_code_id,purpose,token_hash,browser_hash,request_hash,security_version,issued_at,expires_at) VALUES (?,?,?,'ADMIN_REENROLLMENT',?,?,?,?,?,?)",
                            UUID.randomUUID(),identity.id(),codeId,tokenHash,hex(binding.browserHash()),hex(binding.requestHash()),version,captured,captured.plus(ReenrollmentGrantPolicy.LIFETIME));
                    audit("ADMIN_RECOVERY_STARTED");return Optional.of(secret());
                }
            } catch(SQLException failure) { throw unavailable(); }
        }
        @Override public Optional<Secret> prove(String tokenHash,Binding binding) {
            check();if(identity==null)return Optional.empty();
            var grant=grant("token_hash",tokenHash,binding);if(grant.isEmpty())return Optional.empty();
            proven=grant.get();return Optional.of(secret());
        }
        private Optional<UUID> grant(String column,Object value,Binding binding) {
            if(enrolled)return Optional.empty();
            try(var query=connection.prepareStatement("SELECT id,purpose,browser_hash,request_hash,token_hash,issued_at,expires_at,consumed_at,revoked_at,security_version FROM admin_reenrollment_grant WHERE admin_id=? AND "+column+"=?")) {
                query.setObject(1,identity.id());query.setObject(2,value);
                try(var row=query.executeQuery()) {
                    if(!row.next() || row.getLong(10)!=version)return Optional.empty();
                    var state=new State(row.getObject(1,UUID.class),identity.id(),Purpose.valueOf(row.getString(2)),
                            new Binding(unhex(row.getString(3)),unhex(row.getString(4))),unhex(row.getString(5)),time(row,6),time(row,7),time(row,8),time(row,9));
                    return state.eligible(identity.id(),binding,Purpose.ADMIN_REENROLLMENT,captured,!enrolled)?Optional.of(state.id()):Optional.empty();
                }
            } catch(SQLException failure) { throw unavailable(); }
        }
        @Override public void reenroll(long step,List<String> hashes) {
            check();if(identity==null || enrolled || proven==null || step<0 || hashes.size()!=10 || new HashSet<>(hashes).size()!=10)throw unavailable();
            for(String hash:hashes)if(!hash.matches("[a-f0-9]{64}"))throw unavailable();
            update("UPDATE admin_user SET enrolled=true,last_totp_step=?,security_version=security_version+1 WHERE id=?",step,identity.id());
            update("UPDATE admin_reenrollment_grant SET consumed_at=? WHERE id=?",captured,proven);
            revokeGrants();update("UPDATE admin_recovery_code SET revoked_at=? WHERE admin_id=? AND revoked_at IS NULL",captured,identity.id());
            for(String hash:hashes)update("INSERT INTO admin_recovery_code(id,admin_id,code_hash) VALUES (?,?,?)",UUID.randomUUID(),identity.id(),hash);
            revokeSessions();audit("ADMIN_MFA_REENROLLED");enrolled=true;version++;
        }
        private Secret secret() {
            byte[] raw=Base64.getDecoder().decode(secrets.decrypt(encrypted));
            try { if(raw.length!=20)throw unavailable();return new Secret(raw); }
            finally { Arrays.fill(raw,(byte)0); }
        }
        private void revokeGrants() { update("UPDATE admin_reenrollment_grant SET revoked_at=? WHERE admin_id=? AND consumed_at IS NULL AND revoked_at IS NULL",captured,identity.id()); }
        private void revokeSessions() {
            update("DELETE FROM spring_session WHERE principal_name=?",identity.id().toString());
            update("DELETE FROM app_session WHERE admin_id=?",identity.id());
            update("UPDATE admin_authentication_proof SET revoked_at=? WHERE admin_id=? AND consumed_at IS NULL AND revoked_at IS NULL",captured,identity.id());
        }
        private void audit(String type) { update("INSERT INTO audit_event(id,actor_id,actor_kind,type,occurred_at) VALUES (?,?,'ADMIN_RECOVERY',?,?)",UUID.randomUUID(),identity.id(),type,captured); }
        private void update(String sql,Object... values) {
            try(var statement=connection.prepareStatement(sql)) {
                for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i] instanceof Instant time?Timestamp.from(time):values[i]);
                statement.executeUpdate();
            } catch(SQLException failure) { throw unavailable(); }
        }
        @Override public void close() { active=false; }
    }
    private static Instant time(ResultSet row,int column) throws SQLException { var time=row.getTimestamp(column);return time==null?null:time.toInstant(); }
    private static String hex(byte[] value) { return HexFormat.of().formatHex(value); }
    private static byte[] unhex(String value) { return HexFormat.of().parseHex(value); }
}
