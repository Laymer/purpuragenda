package com.purpuragenda.adapters.out.security;

import com.purpuragenda.application.port.out.AccountEnrollmentStore;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

/** One transaction: ACCOUNT proof, verified identity, credential, profile and audit commit or roll back together. */
public final class PostgresAccountEnrollment implements AccountEnrollmentStore {
    private final DataSource source;
    public PostgresAccountEnrollment(DataSource source) { this.source=Objects.requireNonNull(source); }
    @Override public Optional<Enrolled> enroll(Enrollment request) {
        Objects.requireNonNull(request);
        if(!Argon2PasswordHash.validEncoding(request.passwordHash()))throw new IllegalArgumentException("INVALID_ACCOUNT_ENROLLMENT");
        try(var c=source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
            try(var s=c.createStatement()) { s.execute("SET LOCAL lock_timeout='2s'");s.execute("SET LOCAL statement_timeout='5s'"); }
            try {
                var result=enroll(c,request);
                if(result.isPresent())c.commit();else c.rollback();
                return result;
            } catch(SQLException | RuntimeException failure) { try { c.rollback(); } catch(SQLException ignored) { }throw failure; }
        } catch(SQLException | RuntimeException failure) { throw new IllegalStateException("ACCOUNT_ENROLLMENT_UNAVAILABLE"); }
    }
    private static Optional<Enrolled> enroll(Connection c,Enrollment request) throws SQLException {
        UUID challenge,booking;String email,purpose,browser;Instant expiry,consumed;
        try(var q=c.prepareStatement("SELECT id,email_key,purpose,booking_id,browser_hash,expires_at,consumed_at FROM email_challenge WHERE token_hash=? FOR UPDATE")) {
            q.setString(1,Secrets.hash(request.token()));
            try(var r=q.executeQuery()) {
                if(!r.next())return Optional.empty();
                challenge=r.getObject(1,UUID.class);email=r.getString(2);purpose=r.getString(3);booking=r.getObject(4,UUID.class);browser=r.getString(5);
                expiry=r.getTimestamp(6).toInstant();var value=r.getTimestamp(7);consumed=value==null?null:value.toInstant();
            }
        }
        Instant now;
        try(var q=c.createStatement();var r=q.executeQuery("SELECT clock_timestamp()")) { r.next();now=r.getTimestamp(1).toInstant(); }
        if(consumed!=null || !now.isBefore(expiry) || !purpose.equals("ACCOUNT") || booking!=null || !request.browserHash().equals(browser))return Optional.empty();
        update(c,"INSERT INTO customer_identity(id,email_key,verified_at) VALUES(?,?,?) ON CONFLICT(email_key) DO NOTHING",UUID.randomUUID(),email,now);
        UUID customer;
        try(var q=c.prepareStatement("SELECT id FROM customer_identity WHERE email_key=? FOR UPDATE")) {
            q.setString(1,email);try(var r=q.executeQuery()) { if(!r.next())throw new SQLException();customer=r.getObject(1,UUID.class); }
        }
        // The identity lock serializes concurrent proofs for one mailbox; an existing account is never replaced.
        try(var q=c.prepareStatement("SELECT 1 FROM customer_account WHERE customer_id=?")) {
            q.setObject(1,customer);try(var r=q.executeQuery()) { if(r.next())return Optional.empty(); }
        }
        UUID account=UUID.randomUUID();
        update(c,"UPDATE email_challenge SET consumed_at=? WHERE id=?",now,challenge);
        update(c,"INSERT INTO customer_account(id,customer_id,enrolled_at,login_email,password_hash,password_changed_at) VALUES(?,?,?,?,?,?)",
                account,customer,now,email,request.passwordHash(),now);
        update(c,"INSERT INTO customer_profile(customer_id,version,document) VALUES(?,1,'{}'::jsonb) ON CONFLICT(customer_id) DO NOTHING",customer);
        update(c,"INSERT INTO audit_event(id,actor_id,actor_kind,type,occurred_at) VALUES(?,?,'ACCOUNT','ACCOUNT_ENROLLED',?)",UUID.randomUUID(),account,now);
        return Optional.of(new Enrolled(account,customer,now));
    }
    private static void update(Connection c,String sql,Object... values) throws SQLException {
        try(var s=c.prepareStatement(sql)) { for(int i=0;i<values.length;i++)s.setObject(i+1,values[i] instanceof Instant t?Timestamp.from(t):values[i]);s.executeUpdate(); }
    }
    @Override public String toString() { return "AccountEnrollmentStore[REDACTED]"; }
}
