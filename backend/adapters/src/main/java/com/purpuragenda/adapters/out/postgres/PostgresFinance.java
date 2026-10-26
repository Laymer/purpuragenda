package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.FinanceUnitOfWork;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.CustomerQuota;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.payment.PaymentLedger;
import com.purpuragenda.domain.scheduling.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Administrator money commands. Authority is the locked persisted ADMIN session row and its enabled, enrolled
 * administrator at the current security epoch; fresh MFA is required. No caller flag can substitute for these rows.
 */
public final class PostgresFinance implements FinanceUnitOfWork {
    static final long ADMIN_IDLE_SECONDS=900,FRESH_MFA_SECONDS=600;
    private final PostgresScheduling scheduling;
    private final JdbcTemplate jdbc;
    public PostgresFinance(DataSource source,Set<String> meetingHosts) {
        scheduling=new PostgresScheduling(source,meetingHosts);jdbc=new JdbcTemplate(source);
    }
    @Override public Receipt execute(Command command,Function<Finance,Receipt> work) {
        Objects.requireNonNull(command);Objects.requireNonNull(work);
        return scheduling.resolved(now -> {
            // Resource lock is already held. Authority is established, then locked, before any receipt can be observed.
            UUID admin=authorize(command.session(),now);
            var key=new CommandKey(new Scope(admin,command.operation().name(),command.bookingId()),command.key(),command.fingerprint());
            // A stored success for a booking command must name the booking it was issued for.
            return new PostgresScheduling.Resolved(key,stored -> {
                if(stored.success() && confirming(command.operation()) && !command.bookingId().equals(stored.entityId()))
                    throw new Failure(Failure.Kind.STORAGE_FAILURE);
                return stored;
            },tx -> {
                FinanceState state=new FinanceState(tx,admin,command);
                Receipt receipt=Objects.requireNonNull(work.apply(state));
                state.verifyEffects(receipt);
                return receipt;
            });
        });
    }
    private record Row(UUID admin,Long epoch,Instant issued,Instant lastSeen,Instant expires,Instant authenticated,Instant revoked,String kind) { }
    private Row session(SessionBinding binding,boolean lock) {
        return jdbc.query("SELECT admin_id,admin_security_version,issued_at,last_seen_at,absolute_expires_at,authenticated_at,revoked_at,kind FROM app_session WHERE id=? AND browser_hash=?"+(lock?" FOR UPDATE":""),
                (r,n)->new Row(r.getObject(1,UUID.class),(Long)r.getObject(2),PersistedSession.time(r,"issued_at"),PersistedSession.time(r,"last_seen_at"),
                        PersistedSession.time(r,"absolute_expires_at"),PersistedSession.time(r,"authenticated_at"),PersistedSession.time(r,"revoked_at"),r.getString("kind")),
                binding.sessionId(),binding.browserHash()).stream().findFirst().orElse(null);
    }
    /** Lock order: resource -> administrator -> session -> booking. Everything is rechecked after the locks. */
    private UUID authorize(SessionBinding binding,Instant now) {
        Row initial=session(binding,false);
        if(initial==null)throw unauthenticated();
        if(!"ADMIN".equals(initial.kind()))throw PersistedSession.denied();
        List<long[]> admin=jdbc.query("SELECT security_version FROM admin_user WHERE id=? AND enabled AND enrolled FOR UPDATE",(r,n)->new long[]{r.getLong(1)},initial.admin());
        Row row=session(binding,true);
        if(admin.size()!=1 || row==null || !"ADMIN".equals(row.kind()) || !initial.admin().equals(row.admin()))throw unauthenticated();
        if(row.revoked()!=null || row.issued().isAfter(now) || !now.isBefore(row.expires()) || now.isBefore(row.lastSeen())
                || !now.isBefore(row.lastSeen().plusSeconds(ADMIN_IDLE_SECONDS)) || row.epoch()==null || row.epoch()!=admin.getFirst()[0])throw unauthenticated();
        // Idle activity never extends the absolute lifetime; stale MFA is distinguishable so the UI can re-authenticate.
        if(row.authenticated()==null || !now.isBefore(row.authenticated().plusSeconds(FRESH_MFA_SECONDS)))
            throw new Failure(Failure.Kind.FRESH_AUTHENTICATION_REQUIRED);
        jdbc.update("UPDATE app_session SET last_seen_at=GREATEST(last_seen_at,?) WHERE id=?",Timestamp.from(now),binding.sessionId());
        return row.admin();
    }
    private final class FinanceState extends TransactionDelegate implements Finance {
        private final UUID admin,bookingId;private final Operation operation;
        private Booking baseline;private int effects;
        FinanceState(Transaction tx,UUID admin,Command command) { super(tx);this.admin=admin;bookingId=command.bookingId();operation=command.operation(); }
        public Booking authorizedBooking() {
            if(jdbc.query("SELECT id FROM booking WHERE id=? FOR UPDATE",(r,n)->r.getObject(1,UUID.class),bookingId).isEmpty())throw new Failure(Failure.Kind.NOT_FOUND);
            baseline=tx.booking(bookingId).orElseThrow(() -> new Failure(Failure.Kind.STORAGE_FAILURE));return baseline;
        }
        public PaymentLedger ledger() {
            try {
                var entries=jdbc.query("SELECT id,kind,amount_minor,currency,method,reference,verified_at,receipt_id FROM payment_entry WHERE booking_id=? ORDER BY verified_at,id",
                        (r,n)->new PaymentLedger.Entry(r.getObject(1,UUID.class),bookingId,PaymentLedger.Kind.valueOf(r.getString(2)),new Money(r.getLong(3),r.getString(4)),
                                r.getString(5),r.getString(6),PersistedSession.time(r,"verified_at"),r.getObject(8,UUID.class)),bookingId);
                var obligations=jdbc.query("SELECT o.receipt_id,o.due_minor,o.reason FROM refund_obligation o JOIN payment_entry p ON p.id=o.receipt_id WHERE p.booking_id=? ORDER BY o.receipt_id",
                        (r,n)->new PaymentLedger.Obligation(r.getObject(1,UUID.class),r.getLong(2),r.getString(3)),bookingId);
                return new PaymentLedger(entries,obligations);
            } catch(RuntimeException invalid) { throw new Failure(Failure.Kind.STORAGE_FAILURE); }
        }
        public void refundRecorded(PaymentLedger.Entry refund) {
            if(baseline==null || operation!=Operation.RECORD_REFUND || effects++>0 || refund.kind()!=PaymentLedger.Kind.REFUND
                    || !refund.bookingId().equals(bookingId))throw new Failure(Failure.Kind.STORAGE_FAILURE);
            insert(refund);
            event("refund-recorded","REFUND_RECORDED",refund.id(),baseline.version(),"",
                    "{\"currency\":\""+refund.amount().currency()+"\",\"amountMinor\":"+refund.amount().minor()+"}");
        }
        public void refundRequired(UUID receiptId,String reason) {
            if(baseline==null || operation!=Operation.REQUIRE_REFUND || effects++>0)throw new Failure(Failure.Kind.STORAGE_FAILURE);
            // The obligation always covers the whole receipt; the ledger projection subtracts what was refunded.
            int rows=jdbc.update("INSERT INTO refund_obligation(receipt_id,due_minor,reason) SELECT id,amount_minor,'ADMIN_REQUIRED' FROM payment_entry WHERE id=? AND booking_id=? AND kind='RECEIPT'",receiptId,bookingId);
            if(rows!=1)throw new Failure(Failure.Kind.STORAGE_FAILURE);
            event("refund-required","REFUND_REQUIRED",receiptId,baseline.version(),reason,"{}");
        }
        public boolean meetingInUse(Meeting meeting,UUID booking) {
            return jdbc.queryForObject("SELECT count(*) FROM booking WHERE meeting_id=? AND id<>?",Integer.class,meeting.meetingId(),booking)>0;
        }
        public void confirmedEffects(Booking original,Booking confirmed) {
            Booking.ConfirmationBasis basis=operation==Operation.APPROVE_BOOKING?Booking.ConfirmationBasis.MANUAL_APPROVAL:Booking.ConfirmationBasis.EXTERNAL_PAYMENT;
            if(baseline==null || operation==Operation.RECORD_RECEIPT || effects++>0 || !baseline.equals(original) || confirmed.state()!=Booking.State.CONFIRMED
                    || confirmed.confirmationBasis()!=basis || confirmed.version()!=original.version()+1
                    || !tx.booking(bookingId).filter(confirmed::equals).isPresent())throw new Failure(Failure.Kind.STORAGE_FAILURE);
            event("booking-confirmed",basis==Booking.ConfirmationBasis.MANUAL_APPROVAL?"APPROVED":"CONFIRMED_EXTERNAL_PAYMENT",confirmed.id(),confirmed.version(),"",
                    "{\"basis\":\""+basis+"\"}");
        }
        public boolean referenceUsed(PaymentLedger.Kind kind,String method,String reference) {
            return jdbc.queryForObject("SELECT count(*) FROM payment_entry WHERE kind=? AND method=? AND reference=?",Integer.class,kind.name(),method,reference)>0;
        }
        public void receiptRecorded(PaymentLedger.Entry receipt,boolean late) {
            if(baseline==null || operation!=Operation.RECORD_RECEIPT || effects++>0 || receipt.kind()!=PaymentLedger.Kind.RECEIPT
                    || !receipt.bookingId().equals(bookingId))throw new Failure(Failure.Kind.STORAGE_FAILURE);
            insert(receipt);
            if(late)jdbc.update("INSERT INTO refund_obligation(receipt_id,due_minor,reason) VALUES(?,?,'LATE_RECEIPT')",receipt.id(),receipt.amount().minor());
            event(late?"payment-reconciliation":"payment-recorded",late?"PAYMENT_RECONCILIATION_REQUIRED":"PAYMENT_RECORDED",receipt.id(),baseline.version(),"",
                    "{\"currency\":\""+receipt.amount().currency()+"\",\"amountMinor\":"+receipt.amount().minor()+"}");
        }
        private void insert(PaymentLedger.Entry entry) {
            jdbc.update("INSERT INTO payment_entry(id,booking_id,kind,amount_minor,currency,method,reference,verified_at,receipt_id) VALUES(?,?,?,?,?,?,?,?,?)",
                    entry.id(),bookingId,entry.kind().name(),entry.amount().minor(),entry.amount().currency(),entry.method(),entry.reference(),
                    Timestamp.from(entry.verifiedAt()),entry.receiptId());
        }
        /** Audit and outbox share one deterministic id; the booking version stays that of the locked row. */
        private void event(String prefix,String type,UUID subject,long version,String reason,String metadata) {
            UUID event=UUID.nameUUIDFromBytes((prefix+"/"+bookingId+"/"+subject).getBytes(StandardCharsets.UTF_8));
            jdbc.update("INSERT INTO audit_event(id,booking_id,actor_id,actor_kind,type,reason,occurred_at,version,metadata) VALUES(?,?,?,'ADMIN',?,?,?,?,?::jsonb)",
                    event,bookingId,admin,type,reason,Timestamp.from(now()),version,metadata);
            jdbc.update("INSERT INTO outbox_event(id,booking_id,type,version,created_at) VALUES(?,?,?,?,?)",event,bookingId,type,version,Timestamp.from(now()));
        }
        /** A committed command carries exactly its financial effects and never changes the booking by itself. */
        void verifyEffects(Receipt receipt) {
            // Expected failures roll the attempted mutation back to its savepoint, so they can never carry effects.
            if(!receipt.success()) { if(effects!=0)throw new Failure(Failure.Kind.STORAGE_FAILURE);return; }
            // Re-requiring an existing obligation is the only command that may legitimately carry no effect.
            if(baseline==null || effects>1 || (effects==0 && operation!=Operation.REQUIRE_REFUND))throw new Failure(Failure.Kind.STORAGE_FAILURE);
            Booking stored=tx.booking(bookingId).orElseThrow(() -> new Failure(Failure.Kind.STORAGE_FAILURE));
            long expected=confirming(operation)?baseline.version()+1:baseline.version();
            if(stored.version()!=expected || receipt.version()!=expected
                    || (confirming(operation) && !bookingId.equals(receipt.entityId())))throw new Failure(Failure.Kind.STORAGE_FAILURE);
        }
    }
    private static boolean confirming(Operation operation) { return operation==Operation.APPROVE_BOOKING || operation==Operation.CONFIRM_BOOKING; }
    private static Failure unauthenticated() { return PersistedSession.unauthenticated(); }
}
