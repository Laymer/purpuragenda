package com.purpuragenda.adapters.out.security;

import com.purpuragenda.application.port.out.GuestAccessStore;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

/** Persisted internal grants only; callers cannot authorize access from returned metadata alone. */
public final class PostgresGuestAccess implements GuestAccessStore {
    private final DataSource source;
    public PostgresGuestAccess(DataSource source) { this.source=Objects.requireNonNull(source); }
    @Override public Optional<Grant> redeem(Redemption request) {
        Objects.requireNonNull(request);
        try(var c=source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); c.setAutoCommit(false);
            try(var s=c.createStatement()) { s.execute("SET LOCAL lock_timeout='2s'"); s.execute("SET LOCAL statement_timeout='5s'"); }
            try { var result=redeem(c,request); c.commit(); return result; }
            catch(SQLException | RuntimeException error) { try { c.rollback(); } catch(SQLException ignored) { } throw error; }
        } catch(SQLException | RuntimeException error) { throw new IllegalStateException("GUEST_ACCESS_UNAVAILABLE"); }
    }
    private static Optional<Grant> redeem(Connection c,Redemption request) throws SQLException {
        String hash=Secrets.hash(request.token()); UUID lockedBooking=null,customer=null; String deliveryEmail=null;
        // Discover only the lock target first. Booking precedes challenge to match contact-change lock order.
        if(request.purpose()==Kind.MANAGE) {
            try(var q=c.prepareStatement("SELECT booking_id FROM email_challenge WHERE token_hash=?")) {
                q.setString(1,hash); try(var r=q.executeQuery()) { if(r.next()) lockedBooking=r.getObject(1,UUID.class); }
            }
            if(lockedBooking==null) return Optional.empty();
            try(var q=c.prepareStatement("SELECT customer_id,delivery_email FROM booking WHERE id=? FOR SHARE")) {
                q.setObject(1,lockedBooking); try(var r=q.executeQuery()) {
                    if(!r.next()) return Optional.empty(); customer=r.getObject(1,UUID.class); deliveryEmail=r.getString(2);
                }
            }
        }
        UUID challenge,booking; String email,purpose,browser; Instant expiry,consumed;
        try(var q=c.prepareStatement("SELECT id,email_key,purpose,booking_id,browser_hash,expires_at,consumed_at FROM email_challenge WHERE token_hash=? FOR UPDATE")) {
            q.setString(1,hash); try(var r=q.executeQuery()) {
                if(!r.next()) return Optional.empty(); challenge=r.getObject(1,UUID.class); email=r.getString(2); purpose=r.getString(3);
                booking=r.getObject(4,UUID.class); browser=r.getString(5); expiry=r.getTimestamp(6).toInstant();
                var value=r.getTimestamp(7); consumed=value==null?null:value.toInstant();
            }
        }
        Instant now;
        try(var q=c.createStatement();var r=q.executeQuery("SELECT clock_timestamp()")) { r.next(); now=r.getTimestamp(1).toInstant(); }
        if(consumed!=null || !now.isBefore(expiry) || !request.purpose().name().equals(purpose)) return Optional.empty();
        if(request.purpose()==Kind.CREATE) {
            if(booking!=null || !request.browserHash().equals(browser)) return Optional.empty();
            update(c,"INSERT INTO customer_identity(id,email_key,verified_at) VALUES(?,?,?) ON CONFLICT(email_key) DO NOTHING",UUID.randomUUID(),email,now);
            try(var q=c.prepareStatement("SELECT id FROM customer_identity WHERE email_key=? FOR KEY SHARE")) {
                q.setString(1,email); try(var r=q.executeQuery()) { if(!r.next()) throw new SQLException(); customer=r.getObject(1,UUID.class); }
            }
        } else if(browser!=null || !Objects.equals(booking,lockedBooking) || !email.equals(deliveryEmail)) return Optional.empty();
        var grant=new Grant(UUID.randomUUID(),customer,request.purpose(),booking,now,now.plusSeconds(request.purpose()==Kind.CREATE?1800:7200));
        update(c,"UPDATE email_challenge SET consumed_at=? WHERE id=?",now,challenge);
        update(c,"INSERT INTO access_grant(id,customer_id,kind,booking_id,issued_at,expires_at,browser_hash,challenge_id) VALUES(?,?,?,?,?,?,?,?)",
                grant.id(),customer,grant.kind().name(),booking,now,grant.expiresAt(),request.browserHash(),challenge);
        return Optional.of(grant);
    }
    private static void update(Connection c,String sql,Object... values) throws SQLException {
        try(var s=c.prepareStatement(sql)) { for(int i=0;i<values.length;i++) s.setObject(i+1,values[i] instanceof Instant t?Timestamp.from(t):values[i]); s.executeUpdate(); }
    }
    @Override public String toString() { return "GuestAccessStore[REDACTED]"; }
}
