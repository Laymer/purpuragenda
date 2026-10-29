package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.application.port.out.NotesUnitOfWork;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.AdministratorNotes.Addition;
import com.purpuragenda.domain.booking.Booking;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Private note and text-free audit share the authorized scheduling transaction; no notification is produced. */
public final class PostgresNotes implements NotesUnitOfWork {
    private final JdbcTemplate jdbc;private final PostgresScheduling scheduling;
    public PostgresNotes(DataSource source,Set<String> meetingHosts) { jdbc=new JdbcTemplate(source);scheduling=new PostgresScheduling(source,meetingHosts); }
    @Override public Receipt append(Command command,Function<Notes,Receipt> work) {
        Objects.requireNonNull(command);Objects.requireNonNull(work);
        return scheduling.resolved(now->{
            UUID admin=PostgresAdminBooking.authorize(jdbc,command.session(),now);
            if(jdbc.query("SELECT id FROM booking WHERE id=? FOR UPDATE",(r,n)->r.getObject(1,UUID.class),command.bookingId()).isEmpty())throw new Failure(Failure.Kind.NOT_FOUND);
            var key=new CommandKey(new Scope(admin,"ADD_NOTE",command.bookingId()),command.key(),command.fingerprint());
            return new PostgresScheduling.Resolved(key,receipt->{
                if(receipt.success()) {
                    var stored=jdbc.query("SELECT note FROM booking_note WHERE id=? AND booking_id=? AND actor_id=?",(r,n)->r.getString(1),receipt.entityId(),command.bookingId(),admin);
                    if(receipt.version()!=1 || stored.size()!=1 || (!CommandKey.fingerprint(stored).equals(command.fingerprint()) && jdbc.queryForObject("SELECT count(*) FROM privacy_correction_fact WHERE kind='NOTE' AND target_id=? AND prior_fingerprint=?",Integer.class,receipt.entityId(),command.fingerprint())==0))throw storage();
                } else if(receipt.code()!=Code.IDEMPOTENCY_KEY_REUSED)throw storage();
                return receipt;
            },tx->{var state=new State(tx,command,admin);Receipt receipt=Objects.requireNonNull(work.apply(state));state.verify(receipt);return receipt;});
        });
    }
    private final class State extends TransactionDelegate implements Notes {
        private final Command command;private final UUID admin;private final Map<UUID,Long> reclaimed=new HashMap<>();
        private Booking baseline;private UUID note;private int attempts;private boolean attempting;
        State(Transaction tx,Command command,UUID admin) { super(tx);this.command=command;this.admin=admin; }
        public Receipt attempt(Supplier<Receipt> mutation) {
            if(attempts++!=0 || !reclaimed.isEmpty())throw storage();
            return tx.attempt(()->{attempting=true;try{return mutation.get();}finally{attempting=false;}});
        }
        public Booking authorizedBooking() {
            if(!attempting || baseline!=null)throw storage();
            baseline=tx.booking(command.bookingId()).orElseThrow(PostgresNotes::storage);return baseline;
        }
        public void noteAdded(UUID id,String text) {
            if(!attempting || baseline==null || note!=null || id==null)throw storage();
            String normalized;
            try { normalized=new Addition(text).text(); } catch(RuntimeException invalid) { throw storage(); }
            if(!normalized.equals(text) || !CommandKey.fingerprint(List.of(normalized)).equals(command.fingerprint()))throw storage();
            note=id;
            if(jdbc.update("INSERT INTO booking_note(id,booking_id,actor_id,note,created_at) VALUES(?,?,?,?,?)",id,baseline.id(),admin,text,Timestamp.from(now()))!=1)throw storage();
            if(jdbc.update("INSERT INTO audit_event(id,booking_id,actor_id,actor_kind,type,occurred_at,version) VALUES(?,?,?,'ADMIN','NOTE_ADDED',?,?)",
                    id,baseline.id(),admin,Timestamp.from(now()),baseline.version())!=1)throw storage();
        }
        public void save(Booking value) {
            if(attempts!=0)throw storage();
            Booking original=tx.booking(value.id()).orElseThrow(PostgresNotes::storage);
            if(original.state()!=Booking.State.PENDING_CONFIRMATION || now().isBefore(original.expiresAt()) || !original.expire(now()).equals(value))throw storage();
            tx.save(value);reclaimed.put(value.id(),value.version());
        }
        public void expiryEffects(UUID id,long version) {
            if(attempts!=0 || !Objects.equals(reclaimed.remove(id),version))throw storage();tx.expiryEffects(id,version);
        }
        public boolean chargeCreated(Booking value) { throw storage(); }
        void verify(Receipt receipt) {
            if(attempts!=1 || baseline==null || note==null || !receipt.success() || !note.equals(receipt.entityId()) || receipt.version()!=1
                    || !tx.booking(command.bookingId()).filter(baseline::equals).isPresent())throw storage();
        }
    }
    private static Failure storage() { return new Failure(Failure.Kind.STORAGE_FAILURE); }
}
