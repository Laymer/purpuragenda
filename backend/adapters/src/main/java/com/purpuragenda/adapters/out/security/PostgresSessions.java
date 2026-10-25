package com.purpuragenda.adapters.out.security;

import com.purpuragenda.application.port.out.SessionStore;
import com.purpuragenda.application.port.out.GuestAccessStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

/** Direct JDBC boundaries keep provider causes and bearer material out of framework diagnostics. */
public final class PostgresSessions implements SessionStore {
    private final DataSource source;private final AdminCrypto crypto=new AdminCrypto();
    public PostgresSessions(DataSource source) { this.source=Objects.requireNonNull(source); }
    @FunctionalInterface private interface Work<T> { T run(Connection connection) throws SQLException; }
    private <T>T transaction(Work<T> work) {
        try(var c=source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
            try(var s=c.createStatement()) { s.execute("SET LOCAL lock_timeout='2s'");s.execute("SET LOCAL statement_timeout='5s'"); }
            try { T result=work.run(c);c.commit();return result; }
            catch(SQLException | RuntimeException failure) { try { c.rollback(); } catch(SQLException ignored) { }throw failure; }
        } catch(SQLException | RuntimeException failure) { throw new IllegalStateException("SESSION_UNAVAILABLE"); }
    }
    private Optional<Issued> issuing(Work<Optional<Issued>> work) {
        List<Issued> prepared=new ArrayList<>();
        try { return transaction(c->{var value=work.run(c);value.ifPresent(prepared::add);return value;}); }
        catch(RuntimeException failure) { prepared.forEach(Issued::close);throw failure; }
    }
    @Override public Issued anonymous() {
        return issuing(c->{try(var browser=crypto.recoveryCode()) { return Optional.of(issue(c,Kind.ANONYMOUS,browser.hash(),null,null,null,null,null,now(c))); }}).orElseThrow();
    }
    @Override public Optional<Snapshot> resolve(char[] token) {
        try {
            var hash=crypto.digest(token);if(hash.isEmpty())return Optional.empty();
            return transaction(c->{
                var row=load(c,"token_hash",hash.get(),null);Instant now=now(c);
                if(row==null || !live(c,row,now))return Optional.empty();
                update(c,"UPDATE app_session SET last_seen_at=? WHERE id=?",now,row.session.reference().id());
                return Optional.of(touched(row.session,now));
            });
        } finally { clear(token); }
    }
    @Override public Optional<CsrfRotation> rotateCsrf(char[] token) {
        try {
            var hash=crypto.digest(token);if(hash.isEmpty())return Optional.empty();
            List<CsrfRotation> prepared=new ArrayList<>();
            try {
                return transaction(c->{
                    var row=load(c,"token_hash",hash.get(),null);Instant now=now(c);
                    if(row==null || !live(c,row,now))return Optional.empty();
                    try(var csrf=crypto.recoveryCode()) {
                        update(c,"UPDATE app_session SET csrf_hash=?,last_seen_at=? WHERE id=?",csrf.hash(),now,row.session.reference().id());
                        char[] nonce=csrf.value();
                        try { var result=new CsrfRotation(touched(row.session,now),nonce);prepared.add(result);return Optional.of(result); }
                        finally { clear(nonce); }
                    }
                });
            } catch(RuntimeException failure) { prepared.forEach(CsrfRotation::close);throw failure; }
        } finally { clear(token); }
    }
    @Override public boolean csrf(SessionReference reference,char[] token) {
        try {
            var hash=crypto.digest(token);if(hash.isEmpty())return false;
            return transaction(c->{
                var row=load(c,"id",reference.id(),reference.browserHash());Instant now=now(c);
                if(row==null || !live(c,row,now) || !same(hash.get(),row.csrfHash))return false;
                update(c,"UPDATE app_session SET last_seen_at=? WHERE id=?",now,reference.id());return true;
            });
        } finally { clear(token); }
    }
    @Override public boolean revoke(SessionReference reference) {
        return transaction(c->{
            var row=load(c,"id",reference.id(),reference.browserHash());if(row==null)return false;
            Instant now=now(c);if(row.revokedAt==null)update(c,"UPDATE app_session SET revoked_at=? WHERE id=?",now,reference.id());return true;
        });
    }
    @Override public Optional<Issued> exchangeAdmin(SessionReference previous,String requestHash,char[] proofToken) {
        try {
            var binding=new ProofBinding(previous.browserHash(),requestHash);var hash=crypto.digest(proofToken);if(hash.isEmpty())return Optional.empty();
            return issuing(c->{
                UUID adminId;
                try(var q=c.prepareStatement("SELECT admin_id FROM admin_authentication_proof WHERE token_hash=?")) {
                    q.setString(1,hash.get());try(var r=q.executeQuery()) { if(!r.next())return Optional.empty();adminId=r.getObject(1,UUID.class); }
                }
                long version;
                try(var q=c.prepareStatement("SELECT enabled,enrolled,security_version FROM admin_user WHERE id=? FOR UPDATE")) {
                    q.setObject(1,adminId);try(var r=q.executeQuery()) { if(!r.next() || !r.getBoolean(1) || !r.getBoolean(2))return Optional.empty();version=r.getLong(3); }
                }
                try(var q=c.prepareStatement("SELECT purpose,browser_hash,request_hash,security_version,issued_at,expires_at,consumed_at,revoked_at FROM admin_authentication_proof WHERE token_hash=? FOR UPDATE")) {
                    q.setString(1,hash.get());try(var r=q.executeQuery()) {
                        if(!r.next())return Optional.empty();var old=load(c,"id",previous.id(),previous.browserHash());Instant now=now(c);
                        if(old==null || !live(c,old,now) || !r.getString(1).equals("ADMIN_AUTHENTICATION") || !same(r.getString(2),binding.browserHash())
                                || !same(r.getString(3),binding.requestHash()) || r.getLong(4)!=version || now.isBefore(time(r,5)) || !now.isBefore(time(r,6))
                                || time(r,7)!=null || time(r,8)!=null)return Optional.empty();
                        update(c,"UPDATE admin_authentication_proof SET consumed_at=? WHERE token_hash=?",now,hash.get());
                        update(c,"UPDATE app_session SET revoked_at=? WHERE id=?",now,previous.id());
                        update(c,"INSERT INTO audit_event(id,actor_id,actor_kind,type,occurred_at) VALUES (?,?,'ADMIN_SESSION','ADMIN_SESSION_CREATED',?)",UUID.randomUUID(),adminId,now);
                        return Optional.of(issue(c,Kind.ADMIN,binding.browserHash(),null,null,null,adminId,version,now));
                    }
                }
            });
        } finally { clear(proofToken); }
    }
    @Override public Optional<Issued> exchangeGuest(SessionReference previous,GuestAccessStore.Redemption proof) {
        if(!same(previous.browserHash(),proof.browserHash()))return Optional.empty();
        return issuing(c->{
            try(var lock=c.createStatement();var resource=lock.executeQuery("SELECT id FROM scheduling_resource WHERE id=1 FOR UPDATE")) { if(!resource.next())throw new SQLException(); }
            Row[] old=new Row[1];
            var grant=PostgresGuestAccess.redeem(c,proof,()->old[0]=load(c,"id",previous.id(),previous.browserHash()));
            if(grant.isEmpty())return Optional.empty();var value=grant.get();Instant now=value.issuedAt();
            if(old[0]==null || !live(c,old[0],now) || (old[0].session.kind()!=Kind.ANONYMOUS && old[0].session.kind()!=Kind.GUEST)) {
                c.rollback();return Optional.empty();
            }
            update(c,"UPDATE app_session SET revoked_at=? WHERE id=?",now,previous.id());
            return Optional.of(issue(c,Kind.GUEST,previous.browserHash(),value.id(),value.customerId(),null,null,null,now));
        });
    }
    private Issued issue(Connection c,Kind kind,String browser,UUID grant,UUID customer,UUID account,UUID admin,Long version,Instant now) throws SQLException {
        long seconds=kind==Kind.ADMIN?28800:kind==Kind.ACCOUNT?43200:7200;
        var session=new Snapshot(new SessionReference(UUID.randomUUID(),browser),kind,grant,customer,account,admin,version,now,now,now.plusSeconds(seconds),kind==Kind.ANONYMOUS?null:now);
        try(var token=crypto.recoveryCode();var csrf=crypto.recoveryCode()) {
            update(c,"INSERT INTO app_session(id,token_hash,browser_hash,csrf_hash,kind,grant_id,customer_id,account_id,admin_id,admin_security_version,issued_at,last_seen_at,absolute_expires_at,authenticated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    session.reference().id(),token.hash(),browser,csrf.hash(),kind.name(),grant,customer,account,admin,version,now,now,session.absoluteExpiresAt(),session.authenticatedAt());
            char[] bearer=token.value(),nonce=csrf.value();
            try { return new Issued(session,bearer,nonce); }
            finally { clear(bearer);clear(nonce); }
        }
    }
    private record Row(Snapshot session,String csrfHash,Instant revokedAt) {
        @Override public String toString() { return "StoredSession[REDACTED]"; }
    }
    private static Row load(Connection c,String column,Object value,String browser) throws SQLException {
        try(var q=c.prepareStatement("SELECT id,browser_hash,kind,grant_id,customer_id,account_id,admin_id,admin_security_version,issued_at,last_seen_at,absolute_expires_at,authenticated_at,csrf_hash,revoked_at FROM app_session WHERE "+column+"=?"+(browser==null?"":" AND browser_hash=?")+" FOR UPDATE")) {
            q.setObject(1,value);if(browser!=null)q.setString(2,browser);
            try(var r=q.executeQuery()) {
                if(!r.next())return null;
                return new Row(new Snapshot(new SessionReference(r.getObject(1,UUID.class),r.getString(2)),Kind.valueOf(r.getString(3)),r.getObject(4,UUID.class),r.getObject(5,UUID.class),r.getObject(6,UUID.class),
                        r.getObject(7,UUID.class),r.getObject(8,Long.class),time(r,9),time(r,10),time(r,11),time(r,12)),r.getString(13),time(r,14));
            }
        }
    }
    private static boolean live(Connection c,Row row,Instant now) throws SQLException {
        var s=row.session;
        if(row.revokedAt!=null || now.isBefore(s.lastSeenAt()) || !now.isBefore(s.absoluteExpiresAt()) || !now.isBefore(s.lastSeenAt().plusSeconds(s.kind()==Kind.ADMIN?900:1800)))return false;
        return switch(s.kind()) {
            case ANONYMOUS -> true;
            case ADMIN -> exists(c,"SELECT 1 FROM admin_user WHERE id=? AND enabled AND enrolled AND security_version=?",s.adminId(),s.adminSecurityVersion());
            case ACCOUNT -> exists(c,"SELECT 1 FROM customer_account WHERE id=? AND customer_id=?",s.accountId(),s.customerId());
            case GUEST -> exists(c,"SELECT 1 FROM access_grant g WHERE g.id=? AND g.customer_id=? AND g.browser_hash=? AND g.revoked_at IS NULL AND (g.parent_grant_id IS NULL OR EXISTS(SELECT 1 FROM access_grant parent WHERE parent.id=g.parent_grant_id AND parent.kind='CREATE' AND parent.revoked_at IS NULL AND parent.consumed_at=g.issued_at AND parent.customer_id=g.customer_id AND parent.browser_hash=g.browser_hash AND parent.booking_id=g.booking_id)) AND g.issued_at<=? AND ((g.consumed_at IS NULL AND ?<g.expires_at) OR (g.kind='CREATE' AND g.consumed_at IS NOT NULL AND EXISTS(SELECT 1 FROM access_grant child WHERE child.parent_grant_id=g.id AND child.kind='MANAGE' AND child.revoked_at IS NULL AND child.issued_at<=? AND ?<child.expires_at)))",
                    s.grantId(),s.customerId(),s.reference().browserHash(),now,now,now,now);
        };
    }
    private static Snapshot touched(Snapshot s,Instant now) { return new Snapshot(s.reference(),s.kind(),s.grantId(),s.customerId(),s.accountId(),s.adminId(),s.adminSecurityVersion(),s.issuedAt(),now,s.absoluteExpiresAt(),s.authenticatedAt()); }
    private static Instant now(Connection c) throws SQLException { try(var q=c.createStatement();var r=q.executeQuery("SELECT clock_timestamp()")) { r.next();return r.getTimestamp(1).toInstant(); } }
    private static Instant time(ResultSet r,int index) throws SQLException { var value=r.getTimestamp(index);return value==null?null:value.toInstant(); }
    private static void bind(PreparedStatement s,Object... values) throws SQLException { for(int i=0;i<values.length;i++)s.setObject(i+1,values[i] instanceof Instant time?Timestamp.from(time):values[i]); }
    private static void update(Connection c,String sql,Object... values) throws SQLException { try(var s=c.prepareStatement(sql)) { bind(s,values);s.executeUpdate(); } }
    private static boolean exists(Connection c,String sql,Object... values) throws SQLException { try(var s=c.prepareStatement(sql)) { bind(s,values);try(var r=s.executeQuery()) { return r.next(); } } }
    private static boolean same(String a,String b) { return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),b.getBytes(StandardCharsets.US_ASCII)); }
    private static void clear(char[] value) { if(value!=null)Arrays.fill(value,'\0'); }
    @Override public String toString() { return "SessionStore[REDACTED]"; }
}
