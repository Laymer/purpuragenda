package com.purpuragenda.adapters.out.email;

import com.purpuragenda.adapters.out.security.Secrets;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.NotificationQueue.Lease;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

/** Delivery-key instance must be distinct from TOTP encryption; composition is a P45 obligation. */
public final class PostgresAuthDeliveryStore implements AuthDeliveryStore {
    private final DataSource source;
    private final Secrets delivery;
    private final String origin;
    private final boolean suppressed;
    public PostgresAuthDeliveryStore(DataSource source, Secrets delivery, URI origin, boolean suppressed) {
        this.source=Objects.requireNonNull(source); this.delivery=Objects.requireNonNull(delivery);
        if (origin==null || origin.getHost()==null || origin.getUserInfo()!=null || origin.getQuery()!=null
                || origin.getFragment()!=null || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))
                || !("https".equals(origin.getScheme()) || "http".equals(origin.getScheme())
                    && Set.of("localhost","127.0.0.1","[::1]").contains(origin.getHost())))
            throw new IllegalArgumentException("INVALID_VERIFICATION_ORIGIN");
        this.origin=origin.getScheme()+"://"+origin.getRawAuthority(); this.suppressed=suppressed;
    }
    @Override public Receipt issue(Issue request) {
        Objects.requireNonNull(request);
        return transaction(c->{
            if (suppressed || suppression(c)) throw new IllegalStateException();
            Instant now=now(c), expires=now.plusSeconds(900);
            UUID challenge=UUID.randomUUID(), job=UUID.randomUUID(); String token=Secrets.token();
            var envelope=new Envelope(challenge,job,request.email(),request.purpose(),request.bookingId(),request.browserHash(),expires,token);
            String encrypted=delivery.encrypt(envelope.encode());
            update(c,"INSERT INTO email_challenge(id,token_hash,email_key,purpose,booking_id,browser_hash,expires_at) VALUES(?,?,?,?,?,?,?)",
                    challenge,Secrets.hash(token),request.email(),request.purpose().name(),request.bookingId(),request.browserHash(),expires);
            update(c,"INSERT INTO notification_job(id,booking_id,audience,template,due_at,encrypted_envelope,challenge_id) VALUES(?,?,'CUSTOMER','AUTH',?,?,?)",
                    job,request.bookingId(),now,encrypted,challenge);
            return new Receipt(challenge,job,expires);
        });
    }
    @Override public int eraseExpired() {
        return transaction(c->{
            boolean blocked=suppressed || suppression(c);
            var jobs=new ArrayList<UUID>();
            try(var query=c.prepareStatement("""
                SELECT j.id FROM notification_job j LEFT JOIN email_challenge e ON e.id=j.challenge_id
                WHERE j.template='AUTH' AND j.encrypted_envelope IS NOT NULL
                  AND (j.state<>'LEASED' OR j.lease_until<=clock_timestamp() OR j.lease_until IS NULL OR j.lease_token IS NULL)
                  AND (? OR e.id IS NULL OR e.consumed_at IS NOT NULL OR e.expires_at<=clock_timestamp()
                    OR j.state IN ('SENT','SUPPRESSED','FAILED') OR j.audience<>'CUSTOMER')
                ORDER BY j.id FOR UPDATE OF j SKIP LOCKED LIMIT 100
                """)) {
                query.setBoolean(1,blocked);
                try(var r=query.executeQuery()) { while(r.next()) jobs.add(r.getObject(1,UUID.class)); }
            }
            // Locks precede authoritative expiry decisions; never steal a still-live fenced lease.
            Instant now=now(c); int erased=0;
            for(UUID id:jobs) try(var query=c.prepareStatement("""
                UPDATE notification_job j SET state=CASE WHEN j.state IN ('SENT','FAILED','SUPPRESSED') THEN j.state ELSE 'SUPPRESSED' END,encrypted_envelope=NULL,
                  lease_token=NULL,lease_until=NULL,safe_error=CASE WHEN j.state IN ('SENT','FAILED','SUPPRESSED') THEN j.safe_error ELSE ? END
                WHERE j.id=? AND (j.state<>'LEASED' OR j.lease_until<=? OR j.lease_until IS NULL OR j.lease_token IS NULL)
                  AND (? OR j.state IN ('SENT','SUPPRESSED','FAILED') OR j.audience<>'CUSTOMER' OR NOT EXISTS
                    (SELECT 1 FROM email_challenge e WHERE e.id=j.challenge_id AND e.consumed_at IS NULL AND e.expires_at>?))
                """)) {
                query.setString(1,blocked?"DELIVERY_SUPPRESSED":"AUTH_DELIVERY_EXPIRED"); query.setObject(2,id);
                query.setTimestamp(3,Timestamp.from(now)); query.setBoolean(4,blocked); query.setTimestamp(5,Timestamp.from(now));
                erased+=query.executeUpdate();
            }
            return erased;
        });
    }
    @Override public Optional<MailDelivery.Message> prepare(Lease lease) {
        Objects.requireNonNull(lease);
        return transaction(c->{
            UUID token, challenge, booking; Instant until; String encrypted;
            try(var query=c.prepareStatement("SELECT lease_token,lease_until,challenge_id,booking_id,encrypted_envelope FROM notification_job WHERE id=? AND state='LEASED' AND template='AUTH' AND audience='CUSTOMER' FOR UPDATE")) {
                query.setObject(1,lease.id()); try(var r=query.executeQuery()) {
                    if(!r.next()) return Optional.empty(); token=r.getObject(1,UUID.class); until=instant(r,2);
                    challenge=r.getObject(3,UUID.class); booking=r.getObject(4,UUID.class); encrypted=r.getString(5);
                }
            }
            String hash=null,email=null,purpose=null,browser=null; UUID challengeBooking=null; Instant expires=null,consumed=null;
            try(var query=c.prepareStatement("SELECT token_hash,email_key,purpose,booking_id,browser_hash,expires_at,consumed_at FROM email_challenge WHERE id=? FOR UPDATE")) {
                query.setObject(1,challenge); try(var r=query.executeQuery()) {
                    if(r.next()) { hash=r.getString(1); email=r.getString(2); purpose=r.getString(3); challengeBooking=r.getObject(4,UUID.class);
                        browser=r.getString(5); expires=instant(r,6); consumed=instant(r,7); }
                }
            }
            // Capture after both potentially waiting row locks; equality is expired.
            boolean blocked=suppressed || suppression(c);
            Instant now=now(c);
            if(token==null || until==null || !token.equals(lease.token()) || !until.isAfter(now)) return Optional.empty();
            if(blocked || expires==null || !expires.isAfter(now) || consumed!=null || encrypted==null) {
                erase(c,lease.id(),blocked?"DELIVERY_SUPPRESSED":"AUTH_DELIVERY_EXPIRED"); return Optional.empty();
            }
            try {
                var e=Envelope.decode(delivery.decrypt(encrypted));
                new Issue(e.email,e.purpose,e.booking,e.browser);
                if(!e.challenge.equals(challenge) || !e.job.equals(lease.id()) || !Objects.equals(e.booking,booking)
                        || !Objects.equals(e.booking,challengeBooking) || !e.expires.equals(expires)
                        || !same(e.email,email) || !same(e.purpose.name(),purpose) || !Objects.equals(e.browser,browser)
                        || !e.token.matches("[A-Za-z0-9_-]{43}") || !same(Secrets.hash(e.token),hash)) throw new IllegalArgumentException();
                return Optional.of(new MailDelivery.Message(lease.id(),e.email,"Verify your Purpuragenda email",
                    "Open this link and choose Verify email:\n"+origin+"/verify#token="+e.token+"&purpose="+e.purpose.name()
                    +"\n\nThis single-use link expires after 15 minutes. Open it in the browser that requested it. If you did not request this email, ignore it."));
            } catch(RuntimeException invalid) { erase(c,lease.id(),"AUTH_DELIVERY_INVALID"); return Optional.empty(); }
        });
    }
    private static boolean same(String a,String b) {
        return a!=null && b!=null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));
    }
    private static void erase(Connection c,UUID id,String code) throws SQLException {
        update(c,"UPDATE notification_job SET state='SUPPRESSED',encrypted_envelope=NULL,lease_token=NULL,lease_until=NULL,safe_error=? WHERE id=?",code,id);
    }
    private static boolean suppression(Connection c) throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT delivery_suppressed FROM operation_control WHERE id=1 FOR SHARE")) {
            if(!r.next()) throw new SQLException(); return r.getBoolean(1);
        }
    }
    private record Envelope(UUID challenge,UUID job,String email,Purpose purpose,UUID booking,String browser,Instant expires,String token) {
        String encode() {
            try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)) {
                out.writeInt(1); for(String field:new String[]{challenge.toString(),job.toString(),email,purpose.name(),
                    booking==null?"":booking.toString(),browser==null?"":browser,expires.toString(),token}) out.writeUTF(field);
                return Base64.getEncoder().encodeToString(bytes.toByteArray());
            } catch(IOException impossible) { throw new IllegalStateException("AUTH_ENVELOPE_INVALID"); }
        }
        static Envelope decode(String encoded) {
            try(var in=new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))) {
                if(in.readInt()!=1) throw new IOException();
                UUID challenge=UUID.fromString(in.readUTF()), job=UUID.fromString(in.readUTF());
                String email=in.readUTF(); Purpose purpose=Purpose.valueOf(in.readUTF()); String booking=in.readUTF(),browser=in.readUTF();
                var result=new Envelope(challenge,job,email,purpose,booking.isEmpty()?null:UUID.fromString(booking),browser.isEmpty()?null:browser,
                        Instant.parse(in.readUTF()),in.readUTF());
                if(in.read()!=-1) throw new IOException(); return result;
            } catch(IOException | RuntimeException invalid) { throw new IllegalStateException("AUTH_ENVELOPE_INVALID"); }
        }
        @Override public String toString() { return "AuthEnvelope[private=REDACTED]"; }
    }
    private static Instant instant(ResultSet r,int column) throws SQLException { var value=r.getTimestamp(column); return value==null?null:value.toInstant(); }
    private static Instant now(Connection c) throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT clock_timestamp()")) { r.next(); return r.getTimestamp(1).toInstant(); }
    }
    private static void update(Connection c,String sql,Object... values) throws SQLException {
        try(var s=c.prepareStatement(sql)) { for(int i=0;i<values.length;i++) s.setObject(i+1,values[i] instanceof Instant t?Timestamp.from(t):values[i]); s.executeUpdate(); }
    }
    @FunctionalInterface private interface Work<T> { T run(Connection c) throws SQLException; }
    private <T> T transaction(Work<T> work) {
        try(var c=source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); c.setAutoCommit(false);
            try(var s=c.createStatement()) { s.execute("SET LOCAL lock_timeout='2s'"); s.execute("SET LOCAL statement_timeout='5s'"); }
            try { T result=work.run(c); c.commit(); return result; }
            catch(SQLException | RuntimeException error) { try { c.rollback(); } catch(SQLException ignored) { } throw error; }
        } catch(SQLException | RuntimeException failure) { throw new IllegalStateException("AUTH_DELIVERY_STORAGE_FAILED"); }
    }
    @Override public String toString() { return "AuthDeliveryStore[configuration=REDACTED]"; }
}
