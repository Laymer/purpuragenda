package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.adapters.out.email.PostgresAuthDeliveryStore;
import com.purpuragenda.adapters.out.security.Secrets;
import com.purpuragenda.application.port.out.AuthDeliveryStore;
import com.purpuragenda.application.port.out.ContactChangesUnitOfWork;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.Booking;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;

/** Contact proof consumption and notification history share the locked booking transaction. */
public final class PostgresContactChanges implements ContactChangesUnitOfWork {
    private final DataSource source;private final JdbcTemplate jdbc;private final PostgresScheduling scheduling;
    private final PostgresBookingChanges authority;private final PostgresAuthDeliveryStore delivery;
    public PostgresContactChanges(DataSource source,PostgresAuthDeliveryStore delivery) { this(source,delivery,Set.of("zoom.us")); }
    public PostgresContactChanges(DataSource source,PostgresAuthDeliveryStore delivery,Set<String> meetingHosts) {
        this.source=Objects.requireNonNull(source);this.delivery=Objects.requireNonNull(delivery);
        jdbc=new JdbcTemplate(source);scheduling=new PostgresScheduling(source,meetingHosts);authority=new PostgresBookingChanges(source,meetingHosts);
    }
    @Override public void request(ProofRequest request) {
        Objects.requireNonNull(request);
        scheduling.maintain(tx->{
            authority.authorize(request.session(),request.bookingId(),tx.now());
            Connection connection=DataSourceUtils.getConnection(source);
            try { delivery.issueWithin(connection,new AuthDeliveryStore.Issue(request.email(),AuthDeliveryStore.Purpose.CONTACT,request.bookingId(),null),tx.now()); }
            catch(SQLException | IllegalStateException failure) { throw storage(); }
            finally { DataSourceUtils.releaseConnection(connection,source); }
            return 0;
        });
    }
    @Override public Receipt change(Command command,Function<Change,Receipt> work) {
        Objects.requireNonNull(command);Objects.requireNonNull(work);
        return scheduling.resolved(now->{
            var actor=authority.authorize(command.session(),command.bookingId(),now);
            var key=new CommandKey(new Scope(actor.actor(),"CHANGE_CONTACT",command.bookingId()),command.key(),command.fingerprint());
            return new PostgresScheduling.Resolved(key,receipt->{
                long version=jdbc.queryForObject("SELECT version FROM booking WHERE id=?",Long.class,command.bookingId());
                if(receipt.success() && (!command.bookingId().equals(receipt.entityId()) || receipt.version()<2 || receipt.version()>version))throw storage();
                if(!receipt.success() && receipt.code()!=Code.STALE_VERSION && receipt.code()!=Code.IDEMPOTENCY_KEY_REUSED)throw storage();
                return receipt;
            },tx->{var state=new State(tx,command,actor);Receipt receipt=Objects.requireNonNull(work.apply(state));state.verify(receipt);return receipt;});
        });
    }
    private final class State extends TransactionDelegate implements Change {
        private final Command command;private final PostgresBookingChanges.Authority actor;
        private Booking baseline,saved;private UUID challenge;private String email;private int saves,effects,attempts;private boolean attempting;
        State(Transaction tx,Command command,PostgresBookingChanges.Authority actor) { super(tx);this.command=command;this.actor=actor; }
        public Receipt attempt(Supplier<Receipt> mutation) {
            if(attempts++!=0)throw storage();
            return tx.attempt(()->{attempting=true;try{return mutation.get();}finally{attempting=false;}});
        }
        public Booking authorizedBooking() {
            if(!attempting || baseline!=null)throw storage();
            baseline=tx.booking(command.bookingId()).orElseThrow(PostgresContactChanges::storage);return baseline;
        }
        public String verifiedEmail() {
            if(!attempting || baseline==null)throw storage();
            var rows=jdbc.query("SELECT id,email_key,expires_at,consumed_at FROM email_challenge WHERE token_hash=? AND purpose='CONTACT' AND booking_id=? FOR UPDATE",
                    (r,n)->new Proof(r.getObject(1,UUID.class),r.getString(2),r.getTimestamp(3).toInstant(),r.getTimestamp(4)!=null),Secrets.hash(command.token()),command.bookingId());
            if(rows.size()!=1 || rows.getFirst().consumed() || !now().isBefore(rows.getFirst().expires()))throw new DomainFailure("INVALID_CONTACT_PROOF");
            Proof proof=rows.getFirst();challenge=proof.id();email=proof.email();return email;
        }
        public void save(Booking value) {
            if(!attempting) {
                Booking original=tx.booking(value.id()).orElseThrow(PostgresContactChanges::storage);
                if(attempts!=0 || original.state()!=Booking.State.PENDING_CONFIRMATION || now().isBefore(original.expiresAt()) || !original.expire(now()).equals(value))throw storage();
            } else {
                if(baseline==null || email==null || saves++!=0 || !baseline.deliveryContact(email).equals(value))throw storage();saved=value;
            }
            tx.save(value);
        }
        public boolean chargeCreated(Booking value) { throw storage(); }
        public void contactChangedEffects(Booking original,Booking changed) {
            if(!attempting || saves!=1 || effects++!=0 || !Objects.equals(original,baseline) || !Objects.equals(changed,saved)
                    || !tx.booking(command.bookingId()).filter(changed::equals).isPresent())throw storage();
            if(jdbc.update("UPDATE email_challenge SET consumed_at=? WHERE id=? AND consumed_at IS NULL",stamp(now()),challenge)!=1)throw storage();
            jdbc.update("UPDATE email_challenge SET consumed_at=? WHERE purpose='CONTACT' AND booking_id=? AND consumed_at IS NULL",stamp(now()),changed.id());
            UUID event=UUID.nameUUIDFromBytes(("contact-changed/"+changed.id()+"/"+changed.version()).getBytes(StandardCharsets.UTF_8));
            jdbc.update("INSERT INTO booking_contact_change(id,booking_id,old_email,new_email,changed_at) VALUES(?,?,?,?,?)",event,changed.id(),original.deliveryEmail(),changed.deliveryEmail(),stamp(now()));
            jdbc.update("INSERT INTO audit_event(id,booking_id,actor_id,actor_kind,type,occurred_at,version) VALUES(?,?,?,?,'CONTACT_CHANGED',?,?)",
                    event,changed.id(),actor.actor(),actor.guest()?"GUEST":"ACCOUNT",stamp(now()),changed.version());
            jdbc.update("INSERT INTO outbox_event(id,booking_id,type,version,created_at) VALUES(?,?,'CONTACT_CHANGED',?,?)",event,changed.id(),changed.version(),stamp(now()));
        }
        void verify(Receipt receipt) {
            if(attempts!=1)throw storage();
            if(receipt.success()) { if(saves!=1 || effects!=1 || saved==null || !saved.id().equals(receipt.entityId()) || saved.version()!=receipt.version())throw storage(); }
            else if(saves!=0 || effects!=0 || receipt.code()!=Code.STALE_VERSION)throw storage();
        }
    }
    private record Proof(UUID id,String email,Instant expires,boolean consumed) { @Override public String toString(){return "ContactProof[REDACTED]";} }
    private static Timestamp stamp(Instant time) { return Timestamp.from(time); }
    private static Failure storage() { return new Failure(Failure.Kind.STORAGE_FAILURE); }
}
