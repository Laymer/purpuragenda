package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.adapters.out.security.Secrets;
import com.purpuragenda.application.port.out.CustomerErasureStore;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.domain.customer.Input;
import com.purpuragenda.domain.privacy.PrivacyReviewPolicy;
import com.purpuragenda.domain.privacy.TemporaryExport;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Three durable phases: approved owned barrier, fsynced external intent, deterministic DB apply. */
public final class PostgresCustomerErasure implements CustomerErasureStore {
    private final JdbcTemplate jdbc;private final TransactionTemplate transaction;private final PostgresScheduling scheduling;
    private final Secrets secrets;private final EncryptedErasureJournal journal;
    private record Payload(UUID id,UUID customer,UUID actor,Request request,String baseline,Instant created,Instant expires){@Override public String toString(){return "ErasurePayload[REDACTED]";}}
    private record Terminal(UUID booking,String state) {}
    private record Recipe(String purpose,UUID id,UUID customer,UUID actor,List<Scope> scopes,Instant approved,String proposalDigest,String confirmKey,List<Terminal> terminals){
        public Recipe{
            Objects.requireNonNull(id);Objects.requireNonNull(customer);Objects.requireNonNull(actor);Objects.requireNonNull(approved);
            if(!"PURPURAGENDA_ERASURE_V1".equals(purpose)||proposalDigest==null||!proposalDigest.matches("[0-9a-f]{64}")||confirmKey==null||confirmKey.isBlank())throw storage();
            scopes=new Request(scopes,"Approved restore replay").scopes();terminals=List.copyOf(terminals);
            if(terminals.stream().anyMatch(t->t.booking()==null||!Set.of("CANCELLED","REJECTED","EXPIRED","COMPLETED","NO_SHOW").contains(t.state()))||terminals.stream().map(Terminal::booking).distinct().count()!=terminals.size())throw storage();
        }
    }
    private record Row(UUID id,UUID customer,UUID actor,Instant created,Instant expires,String digest,String encrypted,String state,String key,String journalDigest,Instant approved,Instant applied){}
    public PostgresCustomerErasure(DataSource source,Secrets secrets,EncryptedErasureJournal journal,Set<String> hosts){
        jdbc=new JdbcTemplate(Objects.requireNonNull(source));transaction=new TransactionTemplate(new DataSourceTransactionManager(source));transaction.setPropagationBehavior(3);transaction.setTimeout(20);scheduling=new PostgresScheduling(source,hosts);this.secrets=Objects.requireNonNull(secrets);this.journal=Objects.requireNonNull(journal);
    }
    private static Timestamp stamp(Instant value){return Timestamp.from(value);}
    private static Failure missing(){return new Failure(Failure.Kind.NOT_FOUND);}
    private static Failure storage(){return new Failure(Failure.Kind.STORAGE_FAILURE);}
    private <T>T locked(Function<Instant,T> work){try{return transaction.execute(status->{jdbc.execute("SET LOCAL lock_timeout='5s'");jdbc.queryForObject("SELECT id FROM scheduling_resource WHERE id=1 FOR UPDATE",Integer.class);Instant now=jdbc.queryForObject("SELECT clock_timestamp()",(r,n)->r.getTimestamp(1).toInstant());return work.apply(now);});}catch(Failure failure){throw failure;}catch(RuntimeException failure){throw storage();}}
    public boolean blocked(){try{return !Boolean.FALSE.equals(jdbc.queryForObject("SELECT maintenance FROM operation_control WHERE id=1",Boolean.class));}catch(RuntimeException unavailable){return true;}}
    @Override public Overview overview(SessionBinding session,UUID customer){return scheduling.locked(tx->{PostgresAdminBooking.authorize(jdbc,session,tx.now());return inventory(customer,tx.now());});}
    private Overview inventory(UUID customer,Instant now){
        if(jdbc.queryForObject("SELECT count(*) FROM customer_identity WHERE id=?",Integer.class,customer)!=1)throw missing();
        var items=jdbc.query("""
            WITH owned AS (SELECT id,created_at FROM booking WHERE customer_id=?), account AS (SELECT id,enrolled_at FROM customer_account WHERE customer_id=?)
            SELECT 'INTAKE' kind,b.id,b.created_at created,GREATEST(b.created_at,COALESCE((SELECT max(created_at) FROM booking_amendment WHERE booking_id=b.id),b.created_at),COALESCE((SELECT max(occurred_at) FROM audit_event WHERE booking_id=b.id AND type IN ('CANCELLED','REJECTED','EXPIRED','COMPLETED','NO_SHOW')),b.created_at)) activity FROM owned b JOIN booking_snapshot s ON s.booking_id=b.id WHERE COALESCE((s.document->>'intakeErased')::boolean,false)=false
            UNION ALL SELECT 'NOTE',n.id,n.created_at,GREATEST(n.created_at,COALESCE((SELECT max(confirmed_at) FROM privacy_correction WHERE target_id=n.id AND kind='NOTE'),n.created_at)) FROM booking_note n JOIN owned b ON b.id=n.booking_id
            UNION ALL SELECT 'PROFILE',p.customer_id,COALESCE((SELECT enrolled_at FROM account),i.verified_at),GREATEST(i.verified_at,COALESCE((SELECT enrolled_at FROM account),i.verified_at),COALESCE((SELECT max(occurred_at) FROM audit_event WHERE actor_id IN(SELECT id FROM account) AND type='ACCOUNT_PROFILE_CHANGED'),i.verified_at),COALESCE((SELECT max(confirmed_at) FROM privacy_correction WHERE target_id=p.customer_id AND kind='PROFILE'),i.verified_at)) FROM customer_profile p JOIN customer_identity i ON i.id=p.customer_id WHERE p.customer_id=? AND p.document NOT IN ('{}'::jsonb,'{"firstName":"","lastName":"","telephone":""}'::jsonb)
            UNION ALL SELECT 'IDENTITY',i.id,i.verified_at,GREATEST(i.verified_at,COALESCE((SELECT max(created_at) FROM owned),i.verified_at),COALESCE((SELECT max(occurred_at) FROM audit_event WHERE booking_id IN(SELECT id FROM owned) OR actor_id IN(SELECT id FROM account)),i.verified_at),COALESCE((SELECT max(last_seen_at) FROM app_session WHERE customer_id=i.id),i.verified_at)) FROM customer_identity i WHERE i.id=? AND NOT i.privacy_erased
            UNION ALL SELECT 'AUDIT',a.id,a.occurred_at,a.occurred_at FROM audit_event a WHERE (a.booking_id IN(SELECT id FROM owned) OR a.actor_id IN(SELECT id FROM account) OR a.metadata->>'customerId'=?::text) AND (a.reason<>'' OR a.metadata<>'{}'::jsonb) AND a.type NOT LIKE '%PAYMENT%' AND a.type NOT LIKE '%REFUND%'
            UNION ALL SELECT 'REQUEST',id,created_at,created_at FROM privacy_request WHERE customer_id=? AND reason<>'[erased]'
            UNION ALL SELECT 'FINANCIAL',p.id,p.verified_at,p.verified_at FROM payment_entry p JOIN owned b ON b.id=p.booking_id
            ORDER BY kind,id
            """,(r,n)->{
                Kind kind=Kind.valueOf(r.getString("kind"));UUID id=r.getObject("id",UUID.class);Instant created=r.getTimestamp("created").toInstant(),activity=r.getTimestamp("activity").toInstant();
                var holdRows=jdbc.query("SELECT id,reason,starts_on,through FROM privacy_hold WHERE customer_id=? AND kind=? AND target_id=? AND released_at IS NULL",(h,k)->Map.entry(h.getObject(1,UUID.class),new PrivacyReviewPolicy.Hold(new PrivacyReviewPolicy.Scope(category(kind),id),h.getString(2),h.getObject(3,LocalDate.class),h.getObject(4,LocalDate.class))),customer,kind.name(),id);
                var assessed=PrivacyReviewPolicy.assess(new PrivacyReviewPolicy.Scope(category(kind),id),new PrivacyReviewPolicy.Activity(created,activity,null),holdRows.stream().map(Map.Entry::getValue).toList(),now);
                LocalDate today=now.atZone(ZoneId.of("Europe/Brussels")).toLocalDate();var holds=holdRows.stream().filter(h->!today.isBefore(h.getValue().startsOn())&&(h.getValue().through()==null||!today.isAfter(h.getValue().through()))).map(Map.Entry::getKey).toList();
                return new Item(new Scope(kind,id),assessed.status(),assessed.reviewOn(),activity,effect(kind),holds);
            },customer,customer,customer,customer,customer,customer);
        if(items.size()>500)throw storage();return new Overview(customer,items,now);
    }
    private static PrivacyReviewPolicy.Category category(Kind kind){return switch(kind){case INTAKE->PrivacyReviewPolicy.Category.INTAKE;case NOTE->PrivacyReviewPolicy.Category.PRIVATE_NOTE;case PROFILE->PrivacyReviewPolicy.Category.PROFILE;case IDENTITY->PrivacyReviewPolicy.Category.BOOKING_HISTORY;case AUDIT,REQUEST->PrivacyReviewPolicy.Category.AUDIT;case FINANCIAL->PrivacyReviewPolicy.Category.FINANCIAL;};}
    private static String effect(Kind kind){return switch(kind){case INTAKE->"Erase original/corrected intake and override reasons; preserve service and policy facts.";case NOTE->"Erase this private note and its correction fingerprints; deny old command retries.";case PROFILE->"Clear profile fields and advance its version; later new profile entry remains possible.";case IDENTITY->"Remove account access and contact/history identifiers and historic meeting credentials; financial and unselected records remain.";case AUDIT->"Erase this ordinary audit reason and metadata; preserve event type/time.";case REQUEST->"Erase this privacy-request reason.";case FINANCIAL->"Retain necessary financial evidence including references; ten-year rule awaits accounting confirmation.";};}
    private String baseline(UUID customer){return Secrets.hash(jdbc.queryForObject("""
        SELECT jsonb_build_object('identity',(SELECT to_jsonb(i) FROM customer_identity i WHERE id=?),
          'bookings',(SELECT jsonb_agg(to_jsonb(b) ORDER BY b.id) FROM booking b WHERE customer_id=?),
          'snapshots',(SELECT jsonb_agg(to_jsonb(s) ORDER BY s.booking_id) FROM booking_snapshot s JOIN booking b ON b.id=s.booking_id WHERE b.customer_id=?),
          'notes',(SELECT jsonb_agg(to_jsonb(n) ORDER BY n.id) FROM booking_note n JOIN booking b ON b.id=n.booking_id WHERE b.customer_id=?),
          'profile',(SELECT to_jsonb(p) FROM customer_profile p WHERE customer_id=?),
          'holds',(SELECT jsonb_agg(to_jsonb(h) ORDER BY h.id) FROM privacy_hold h WHERE customer_id=?),
          'corrections',(SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id) FROM privacy_correction c WHERE customer_id=? AND confirmed_at IS NOT NULL))::text
        """,String.class,customer,customer,customer,customer,customer,customer,customer));}
    @Override public Receipt prepare(SessionBinding session,UUID customer,String key,Request request){
        return scheduling.resolved(now->{UUID actor=PostgresAdminBooking.authorize(jdbc,session,now);Overview view=inventory(customer,now);validate(view,request.scopes());String fingerprint=Secrets.hash(PersistedJson.write(request));
            return new PostgresScheduling.Resolved(new CommandKey(new com.purpuragenda.application.port.out.SchedulingUnitOfWork.Scope(actor,"PREPARE_ERASURE",customer),key,fingerprint),receipt->{if(receipt.success()){Row row=row(receipt.entityId(),actor,now,true);if(!row.customer().equals(customer)||!Secrets.hash(PersistedJson.write(payload(row).request())).equals(fingerprint))throw storage();}return receipt;},tx->{
                UUID id=UUID.randomUUID();Instant expires=new TemporaryExport(now).expiresAt();String plain=PersistedJson.write(new Payload(id,customer,actor,request,baseline(customer),now,expires));String digest=Secrets.hash(plain);
                jdbc.update("INSERT INTO privacy_erasure(id,customer_id,actor_id,created_at,expires_at,digest,encrypted_proposal,state) VALUES(?,?,?,?,?,?,?,'PREVIEW')",id,customer,actor,stamp(now),stamp(expires),digest,secrets.encrypt(plain));
                jdbc.update("INSERT INTO privacy_request VALUES(?,?,'ERASURE','PREVIEW',?,?)",id,customer,request.reason(),stamp(now));return new Receipt(Code.OK,id,1);
            });});
    }
    private void validate(Overview view,List<Scope> scopes){for(Scope scope:scopes){Item item=view.items().stream().filter(i->i.scope().equals(scope)).findFirst().orElseThrow(PostgresCustomerErasure::missing);if(!item.holds().isEmpty()||scope.kind()==Kind.FINANCIAL)throw new Failure(Failure.Kind.FORBIDDEN);}
        if(scopes.stream().anyMatch(s->s.kind()==Kind.IDENTITY)&&jdbc.queryForObject("SELECT count(*) FROM booking WHERE customer_id=? AND state IN ('PENDING_CONFIRMATION','CONFIRMED')",Integer.class,view.customerId())!=0)throw new Failure(Failure.Kind.FORBIDDEN);
    }
    @Override public Preview preview(SessionBinding session,UUID id){return scheduling.locked(tx->{UUID actor=PostgresAdminBooking.authorize(jdbc,session,tx.now());Row row=row(id,actor,tx.now(),true);Payload p=payload(row);if(!row.state().equals("PREVIEW")||!baseline(row.customer()).equals(p.baseline()))throw missing();Overview view=inventory(row.customer(),tx.now());return new Preview(id,row.customer(),view.items().stream().filter(i->p.request().scopes().contains(i.scope())).toList(),view.items().stream().filter(i->!p.request().scopes().contains(i.scope())).toList(),p.request().reason(),row.digest(),row.expires(),1);});}
    private Row row(UUID id,UUID actor,Instant now,boolean expiring){return jdbc.query("SELECT * FROM privacy_erasure WHERE id=? AND actor_id=?"+(expiring?" AND created_at<=? AND expires_at>?":"")+" FOR UPDATE",(r,n)->new Row(r.getObject("id",UUID.class),r.getObject("customer_id",UUID.class),r.getObject("actor_id",UUID.class),r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant(),r.getString("digest"),r.getString("encrypted_proposal"),r.getString("state"),r.getString("confirm_key"),r.getString("journal_digest"),r.getTimestamp("approved_at")==null?null:r.getTimestamp("approved_at").toInstant(),r.getTimestamp("applied_at")==null?null:r.getTimestamp("applied_at").toInstant()),expiring?new Object[]{id,actor,stamp(now),stamp(now)}:new Object[]{id,actor}).stream().findFirst().orElseThrow(PostgresCustomerErasure::missing);}
    private Payload payload(Row row){String plain=secrets.decrypt(row.encrypted());if(!Secrets.hash(plain).equals(row.digest()))throw storage();Payload p=PersistedJson.read(plain,Payload.class);if(!p.id().equals(row.id())||!p.actor().equals(row.actor())||!p.customer().equals(row.customer())||!p.created().equals(row.created())||!p.expires().equals(row.expires()))throw storage();return p;}
    @Override public Receipt confirm(SessionBinding session,UUID id,String key,long version,Review review){
        new CommandKey(new com.purpuragenda.application.port.out.SchedulingUnitOfWork.Scope(session.sessionId(),"CONFIRM_ERASURE",id),key,review.digest());
        Recipe recipe=locked(now->{UUID actor=PostgresAdminBooking.authorize(jdbc,session,now);Row row=row(id,actor,now,false);
            if(version!=1||!row.digest().equals(review.digest()))throw new Failure(Failure.Kind.FORBIDDEN);
            if(!row.state().equals("PREVIEW")){
                if(!Objects.equals(row.key(),key))throw new Failure(Failure.Kind.FORBIDDEN);
                Recipe prior=approved(row);
                if(row.state().equals("APPLIED")){verifyApplied(prior);return null;}
                return prior;
            }
            if(now.isBefore(row.created())||!now.isBefore(row.expires()))throw missing();Payload p=payload(row);
            var control=jdbc.queryForMap("SELECT maintenance,delivery_suppressed,privacy_owner FROM operation_control WHERE id=1 FOR UPDATE");
            if(Boolean.TRUE.equals(control.get("maintenance"))||control.get("privacy_owner")!=null)throw new Failure(Failure.Kind.MAINTENANCE);
            if(!p.baseline().equals(baseline(row.customer())))throw new Failure(Failure.Kind.RETRY_LATER);validate(inventory(row.customer(),now),p.request().scopes());
            List<Terminal> terminals=p.request().scopes().stream().anyMatch(t->t.kind()==Kind.IDENTITY)?jdbc.query("SELECT id,state FROM booking WHERE customer_id=? ORDER BY id",(r,n)->new Terminal(r.getObject(1,UUID.class),r.getString(2)),row.customer()):List.of();
            Recipe value=new Recipe("PURPURAGENDA_ERASURE_V1",id,row.customer(),actor,p.request().scopes(),now,row.digest(),key,terminals);String encoded=PersistedJson.write(value);
            jdbc.update("UPDATE operation_control SET maintenance=true,delivery_suppressed=true,privacy_owner=? WHERE id=1",id);
            jdbc.update("UPDATE privacy_erasure SET state='APPROVED',approved_at=?,encrypted_proposal=?,confirm_key=?,journal_digest=?,previous_maintenance=?,previous_suppressed=? WHERE id=?",stamp(now),secrets.encrypt(encoded),key,Secrets.hash(encoded),control.get("maintenance"),control.get("delivery_suppressed"),id);
            for(Scope scope:value.scopes())jdbc.update("INSERT INTO privacy_erasure_target VALUES(?,?,?)",id,scope.kind().name(),scope.id());
            jdbc.update("UPDATE privacy_request SET state='APPROVED' WHERE id=?",id);return value;
        });
        if(recipe!=null){append(recipe);apply(recipe,false);}return new Receipt(Code.OK,id,2);
    }
    private Recipe approved(Row row){
        String encoded;
        if(row.state().equals("APPLIED"))encoded=journal.entries().stream().filter(e->e.id().equals(row.id())).findFirst().orElseThrow(PostgresCustomerErasure::storage).recipe();
        else encoded=secrets.decrypt(row.encrypted());
        Recipe recipe=PersistedJson.read(encoded,Recipe.class);
        if(!recipe.id().equals(row.id())||!recipe.customer().equals(row.customer())||!recipe.actor().equals(row.actor())||!recipe.proposalDigest().equals(row.digest())||!recipe.confirmKey().equals(row.key())||!Secrets.hash(encoded).equals(row.journalDigest())||!recipe.approved().equals(row.approved()))throw storage();
        var targets=jdbc.query("SELECT kind,target_id FROM privacy_erasure_target WHERE request_id=?",(r,n)->new Scope(Kind.valueOf(r.getString(1)),r.getObject(2,UUID.class)),row.id());
        if(!new HashSet<>(targets).equals(new HashSet<>(recipe.scopes())))throw storage();
        return recipe;
    }
    private void verifyApplied(Recipe recipe){
        String encoded=PersistedJson.write(recipe);
        if(journal.entries().stream().noneMatch(e->e.id().equals(recipe.id())&&e.recipe().equals(encoded)))throw storage();
        var evidence=jdbc.query("SELECT customer_id,encrypted_document,created_at FROM erasure_tombstone WHERE id=? AND applied_at IS NOT NULL",(r,n)->r.getObject(1,UUID.class).equals(recipe.customer())&&secrets.decrypt(r.getString(2)).equals(encoded)&&r.getTimestamp(3).toInstant().equals(recipe.approved()),recipe.id());
        if(evidence.size()!=1||!evidence.getFirst())throw storage();
        if(jdbc.queryForObject("SELECT count(*) FROM customer_identity WHERE id=?",Integer.class,recipe.customer())==0)return;
        var applied=jdbc.query("SELECT actor_id FROM privacy_erasure WHERE id=? AND state='APPLIED' AND applied_at IS NOT NULL",(r,n)->r.getObject(1,UUID.class),recipe.id());
        if(applied.size()!=1||!approved(row(recipe.id(),applied.getFirst(),Instant.EPOCH,false)).equals(recipe))throw storage();
    }
    private void append(Recipe recipe){String encoded=PersistedJson.write(recipe);journal.append(new EncryptedErasureJournal.Entry(recipe.id(),Secrets.hash(encoded),encoded));}
    @Override public Receipt hold(SessionBinding session,UUID customer,String key,Hold hold){return scheduling.resolved(now->{UUID actor=PostgresAdminBooking.authorize(jdbc,session,now);if(inventory(customer,now).items().stream().noneMatch(i->i.scope().equals(hold.scope())))throw missing();LocalDate today=now.atZone(ZoneId.of("Europe/Brussels")).toLocalDate();new PrivacyReviewPolicy.Hold(new PrivacyReviewPolicy.Scope(category(hold.scope().kind()),hold.scope().id()),hold.reason(),today,hold.through());
        return new PostgresScheduling.Resolved(new CommandKey(new com.purpuragenda.application.port.out.SchedulingUnitOfWork.Scope(actor,"PRIVACY_HOLD",customer),key,Secrets.hash(PersistedJson.write(hold))),Function.identity(),tx->{UUID id=UUID.randomUUID();jdbc.update("INSERT INTO privacy_hold VALUES(?,?,?,?,?,?,?,NULL)",id,customer,hold.scope().kind().name(),hold.scope().id(),hold.reason(),today,hold.through());audit(actor,customer,id,"PRIVACY_HOLD_CREATED",hold.reason(),now);return new Receipt(Code.OK,id,1);});});}
    @Override public Receipt releaseHold(SessionBinding session,UUID id,String key,String reason,boolean confirmed){String normalized=Input.reason(reason);if(!confirmed)throw new IllegalArgumentException("REVIEW_REQUIRED");return scheduling.resolved(now->{UUID actor=PostgresAdminBooking.authorize(jdbc,session,now);UUID customer=jdbc.query("SELECT customer_id FROM privacy_hold WHERE id=? FOR UPDATE",(r,n)->r.getObject(1,UUID.class),id).stream().findFirst().orElseThrow(PostgresCustomerErasure::missing);return new PostgresScheduling.Resolved(new CommandKey(new com.purpuragenda.application.port.out.SchedulingUnitOfWork.Scope(actor,"RELEASE_PRIVACY_HOLD",id),key,Secrets.hash(normalized)),Function.identity(),tx->{jdbc.update("UPDATE privacy_hold SET released_at=COALESCE(released_at,?) WHERE id=?",stamp(now),id);audit(actor,customer,id,"PRIVACY_HOLD_RELEASED",normalized,now);return new Receipt(Code.OK,id,2);});});}
    private void audit(UUID actor,UUID customer,UUID id,String type,String reason,Instant now){jdbc.update("INSERT INTO audit_event(id,actor_id,actor_kind,type,occurred_at,reason,metadata) VALUES(?,?,'ADMIN',?,?,?,jsonb_build_object('customerId',?::text,'privacyId',?::text))",UUID.randomUUID(),actor,type,stamp(now),reason,customer.toString(),id.toString());}
    @Override public int purge(int limit){if(limit<1||limit>500)throw new IllegalArgumentException("INVALID_PURGE_LIMIT");return jdbc.update("DELETE FROM privacy_erasure WHERE id IN(SELECT id FROM privacy_erasure WHERE state='PREVIEW' AND expires_at<=clock_timestamp() ORDER BY id LIMIT ?)",limit);}
    public void recover(boolean restoreMode){
        if(restoreMode)locked(now->{jdbc.update("UPDATE operation_control SET maintenance=true,delivery_suppressed=true WHERE id=1");return null;});
        UUID expected=jdbc.query("SELECT installation_id FROM privacy_journal_state WHERE id=1",(r,n)->r.getObject(1,UUID.class)).stream().findFirst().orElse(null);
        UUID installation=restoreMode?journal.initializeForRestore(expected):journal.initialize(expected);jdbc.update("INSERT INTO privacy_journal_state VALUES(1,?) ON CONFLICT(id) DO NOTHING",installation);
        if(!installation.equals(jdbc.queryForObject("SELECT installation_id FROM privacy_journal_state WHERE id=1",UUID.class)))throw storage();
        if(restoreMode)locked(now->{jdbc.update("UPDATE operation_control SET maintenance=true,delivery_suppressed=true WHERE id=1");return null;});
        var pending=jdbc.query("SELECT id,encrypted_proposal,journal_digest FROM privacy_erasure WHERE state='APPROVED' ORDER BY approved_at,id",(r,n)->{
            UUID id=r.getObject(1,UUID.class);UUID actor=jdbc.queryForObject("SELECT actor_id FROM privacy_erasure WHERE id=?",UUID.class,id);return approved(row(id,actor,Instant.EPOCH,false));});
        pending.forEach(this::append);
        for(var entry:journal.entries()){
            Recipe recipe=PersistedJson.read(entry.recipe(),Recipe.class);if(!recipe.id().equals(entry.id())||!"PURPURAGENDA_ERASURE_V1".equals(recipe.purpose()))throw storage();new Request(recipe.scopes(),"Approved restore replay");apply(recipe,restoreMode);
        }
    }
    private void apply(Recipe recipe,boolean restoreMode){
        String encoded=PersistedJson.write(recipe),digest=Secrets.hash(encoded);
        // Establish a committed barrier before waiting on any independent account/profile row.
        boolean needed=locked(now->{
            var existing=jdbc.query("SELECT encrypted_document FROM erasure_tombstone WHERE id=? AND applied_at IS NOT NULL",(r,n)->r.getString(1),recipe.id());
            if(!existing.isEmpty()){verifyApplied(recipe);return false;}
            var control=jdbc.queryForMap("SELECT maintenance,delivery_suppressed,privacy_owner FROM operation_control WHERE id=1 FOR UPDATE");
            UUID owner=(UUID)control.get("privacy_owner");if(owner!=null&&!owner.equals(recipe.id()))throw storage();
            if(jdbc.queryForObject("SELECT count(*) FROM customer_identity WHERE id=?",Integer.class,recipe.customer())==0){jdbc.update("INSERT INTO erasure_tombstone VALUES(?,?,?,?,?) ON CONFLICT(id) DO NOTHING",recipe.id(),recipe.customer(),secrets.encrypt(encoded),stamp(recipe.approved()),stamp(now));return false;}
            jdbc.update("INSERT INTO privacy_erasure(id,customer_id,actor_id,created_at,expires_at,digest,encrypted_proposal,state,approved_at,previous_maintenance,previous_suppressed,journal_digest,confirm_key) VALUES(?,?,?,?,?,?,?,'APPROVED',?,?,?,?,?) ON CONFLICT(id) DO NOTHING",recipe.id(),recipe.customer(),recipe.actor(),stamp(recipe.approved()),stamp(recipe.approved().plusSeconds(3600)),recipe.proposalDigest(),secrets.encrypt(encoded),stamp(recipe.approved()),control.get("maintenance"),control.get("delivery_suppressed"),digest,recipe.confirmKey());
            Row restored=row(recipe.id(),recipe.actor(),now,false);
            if(restored.state().equals("APPLIED"))throw storage(); // Lost applied evidence must never erase later data again.
            if(restored.state().equals("PREVIEW")){
                Payload proposal=payload(restored);
                if(!proposal.customer().equals(recipe.customer())||!proposal.actor().equals(recipe.actor())||!restored.digest().equals(recipe.proposalDigest())||!new HashSet<>(proposal.request().scopes()).equals(new HashSet<>(recipe.scopes())))throw storage();
                jdbc.update("UPDATE privacy_erasure SET state='APPROVED',approved_at=?,encrypted_proposal=?,confirm_key=?,journal_digest=?,previous_maintenance=?,previous_suppressed=? WHERE id=?",stamp(recipe.approved()),secrets.encrypt(encoded),recipe.confirmKey(),digest,control.get("maintenance"),control.get("delivery_suppressed"),recipe.id());
            }
            for(Scope scope:recipe.scopes())jdbc.update("INSERT INTO privacy_erasure_target VALUES(?,?,?) ON CONFLICT DO NOTHING",recipe.id(),scope.kind().name(),scope.id());
            approved(row(recipe.id(),recipe.actor(),now,false));
            jdbc.update("UPDATE operation_control SET maintenance=true,delivery_suppressed=true,privacy_owner=? WHERE id=1",recipe.id());return true;
        });
        if(!needed)return;
        // Leave the committed access/delivery barrier in place for an older active backup.
        boolean reconciled=locked(now->{
            for(Terminal terminal:recipe.terminals()){
                var states=jdbc.query("SELECT state FROM booking WHERE id=? AND customer_id=?",(r,n)->r.getString(1),terminal.booking(),recipe.customer());
                if(!states.isEmpty()&&!states.getFirst().equals(terminal.state())){
                    jdbc.update("UPDATE privacy_erasure SET reconciliation_required=true WHERE id=?",recipe.id());return false;
                }
            }
            return Boolean.FALSE.equals(jdbc.queryForObject("SELECT reconciliation_required FROM privacy_erasure WHERE id=?",Boolean.class,recipe.id()));
        });
        if(!reconciled)throw new IllegalStateException("PRIVACY_RECONCILIATION_REQUIRED");
        locked(now->{
            var control=jdbc.queryForMap("SELECT privacy_owner FROM operation_control WHERE id=1");if(!recipe.id().equals(control.get("privacy_owner")))throw storage();
            jdbc.queryForObject("SELECT set_config('purpuragenda.erasure',?,true)",String.class,recipe.id().toString());
            validateOwnership(recipe);
            // Scrub command body hashes but retain exact-key denial receipts so uncertain old commands cannot resurrect data.
            for(Scope scope:recipe.scopes().stream().sorted(Comparator.comparingInt(t->t.kind()==Kind.IDENTITY?2:t.kind()==Kind.AUDIT?0:1)).toList()){
                jdbc.update("UPDATE idempotency_record SET fingerprint=repeat('0',64),receipt=jsonb_build_object('code','IDEMPOTENCY_KEY_REUSED','entityId',NULL,'version',0) WHERE (receipt->>'entityId'=?::text OR scope LIKE '%:'||?::text) AND scope NOT LIKE '%PAYMENT%' AND scope NOT LIKE '%REFUND%'",scope.id(),scope.id());
                jdbc.update("UPDATE privacy_correction_fact SET prior_fingerprint=repeat('0',64),request_fingerprint=repeat('0',64) WHERE customer_id=? AND kind=? AND target_id=?",recipe.customer(),scope.kind().name(),scope.id());
                jdbc.update("UPDATE privacy_correction SET erased=true,digest=repeat('0',64),encrypted_proposal=CASE WHEN confirmed_at IS NULL THEN '' ELSE NULL END WHERE customer_id=? AND kind=? AND target_id=?",recipe.customer(),scope.kind().name(),scope.id());
                switch(scope.kind()){
                    case INTAKE->{
                        jdbc.update("UPDATE booking_snapshot SET document=jsonb_set(jsonb_set(document,'{intake}','null'::jsonb),'{intakeErased}','true'::jsonb) WHERE booking_id=? AND booking_id IN(SELECT id FROM booking WHERE customer_id=?)",scope.id(),recipe.customer());
                        jdbc.update("DELETE FROM booking_amendment WHERE booking_id=? AND booking_id IN(SELECT id FROM booking WHERE customer_id=?)",scope.id(),recipe.customer());
                        jdbc.update("UPDATE booking_override SET reason='' WHERE booking_id=?",scope.id());
                    }
                    case NOTE->jdbc.update("DELETE FROM booking_note WHERE id=? AND booking_id IN(SELECT id FROM booking WHERE customer_id=?)",scope.id(),recipe.customer());
                    case PROFILE->jdbc.update("UPDATE customer_profile SET document=' {\"firstName\":\"\",\"lastName\":\"\",\"telephone\":\"\"}'::jsonb,version=version+1 WHERE customer_id=? AND customer_id=?",scope.id(),recipe.customer());
                    case IDENTITY->identity(recipe.customer());
                    case AUDIT->jdbc.update("UPDATE audit_event SET reason='',metadata='{}'::jsonb WHERE id=?",scope.id());
                    case REQUEST->jdbc.update("UPDATE privacy_request SET reason='[erased]' WHERE id=? AND customer_id=?",scope.id(),recipe.customer());
                    case FINANCIAL->throw storage();
                }
            }
            jdbc.update("DELETE FROM privacy_export WHERE customer_id=?",recipe.customer());
            jdbc.update("UPDATE privacy_correction SET erased=true,encrypted_proposal=CASE WHEN confirmed_at IS NULL THEN '' ELSE NULL END WHERE customer_id=?",recipe.customer());
            jdbc.update("UPDATE privacy_erasure SET encrypted_proposal=NULL,state='APPLIED',applied_at=? WHERE id=? AND state='APPROVED'",stamp(now),recipe.id());
            jdbc.update("UPDATE privacy_request SET state='APPLIED' WHERE id=?",recipe.id());
            jdbc.update("INSERT INTO erasure_tombstone VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET applied_at=EXCLUDED.applied_at",recipe.id(),recipe.customer(),secrets.encrypt(encoded),stamp(recipe.approved()),stamp(now));
            audit(recipe.actor(),recipe.customer(),recipe.id(),"PRIVACY_ERASURE_APPLIED","",now);
            if(restoreMode)jdbc.update("UPDATE operation_control SET maintenance=true,delivery_suppressed=true,privacy_owner=NULL WHERE id=1 AND privacy_owner=?",recipe.id());
            else jdbc.update("UPDATE operation_control o SET maintenance=e.previous_maintenance,delivery_suppressed=e.previous_suppressed,privacy_owner=NULL FROM privacy_erasure e WHERE o.id=1 AND o.privacy_owner=e.id AND e.id=?",recipe.id());
            return null;
        });
    }
    /** Private operator acknowledgment only after independently recovered business facts match the approval evidence. */
    public String reconciliationEvidence(UUID id){
        var entry=journal.entries().stream().filter(e->e.id().equals(id)).findFirst().orElseThrow(PostgresCustomerErasure::missing);
        Recipe recipe=PersistedJson.read(entry.recipe(),Recipe.class);
        return entry.digest()+"\n"+recipe.terminals().stream().map(t->t.booking()+" "+t.state()).collect(java.util.stream.Collectors.joining("\n"));
    }
    public void reconcile(UUID id,String journalDigest,String reason){
        String normalized=Input.reason(reason);
        var entry=journal.entries().stream().filter(e->e.id().equals(id)&&e.digest().equals(journalDigest)).findFirst().orElseThrow(PostgresCustomerErasure::missing);
        Recipe recipe=PersistedJson.read(entry.recipe(),Recipe.class);
        locked(now->{
            if(!id.equals(jdbc.queryForObject("SELECT privacy_owner FROM operation_control WHERE id=1 AND maintenance AND delivery_suppressed",UUID.class)))throw storage();
            approved(row(id,recipe.actor(),now,false));
            for(Terminal terminal:recipe.terminals()){
                var states=jdbc.query("SELECT state FROM booking WHERE id=? AND customer_id=?",(r,n)->r.getString(1),terminal.booking(),recipe.customer());
                if(!states.isEmpty()&&!states.getFirst().equals(terminal.state()))throw new IllegalStateException("PRIVACY_RECONCILIATION_REQUIRED");
            }
            jdbc.update("UPDATE privacy_erasure SET reconciliation_required=false WHERE id=?",id);
            audit(recipe.actor(),recipe.customer(),id,"PRIVACY_RESTORE_RECONCILED",normalized,now);return null;
        });
    }
    private void validateOwnership(Recipe recipe){
        // Resolve every target before identity erasure removes account-to-audit linkage.
        for(Scope scope:recipe.scopes()){
            String sql=switch(scope.kind()){
                case INTAKE->"SELECT count(*) FROM booking WHERE id=? AND customer_id=?";
                case NOTE->"SELECT count(*) FROM booking_note n JOIN booking b ON b.id=n.booking_id WHERE n.id=? AND b.customer_id=?";
                case PROFILE,IDENTITY->"SELECT count(*) FROM customer_identity WHERE id=? AND id=?";
                case REQUEST->"SELECT count(*) FROM privacy_request WHERE id=? AND customer_id=?";
                case AUDIT->"SELECT count(*) FROM audit_event a WHERE a.id=? AND (a.booking_id IN(SELECT id FROM booking WHERE customer_id=?) OR a.actor_id IN(SELECT id FROM customer_account WHERE customer_id=?) OR a.metadata->>'customerId'=?::text)";
                case FINANCIAL->throw storage();
            };
            int count=scope.kind()==Kind.AUDIT?jdbc.queryForObject(sql,Integer.class,scope.id(),recipe.customer(),recipe.customer(),recipe.customer()):jdbc.queryForObject(sql,Integer.class,scope.id(),recipe.customer());
            if(count!=1){
                String table=switch(scope.kind()){case INTAKE->"booking";case NOTE->"booking_note";case AUDIT->"audit_event";case REQUEST->"privacy_request";default->throw storage();};
                if(jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE id=?",Integer.class,scope.id())!=0)throw storage();
            }
        }
    }
    private void identity(UUID customer){
        if(jdbc.queryForObject("SELECT count(*) FROM booking WHERE customer_id=? AND state IN ('PENDING_CONFIRMATION','CONFIRMED')",Integer.class,customer)!=0)throw storage();
        // Capture related authentication jobs before removing the identity's old mailbox.
        jdbc.update("DELETE FROM delivery_attempt WHERE job_id IN(SELECT n.id FROM notification_job n JOIN email_challenge c ON c.id=n.challenge_id WHERE c.booking_id IN(SELECT id FROM booking WHERE customer_id=?) OR c.email_key=(SELECT email_key FROM customer_identity WHERE id=?))",customer,customer);
        jdbc.update("DELETE FROM notification_job WHERE challenge_id IN(SELECT id FROM email_challenge WHERE booking_id IN(SELECT id FROM booking WHERE customer_id=?) OR email_key=(SELECT email_key FROM customer_identity WHERE id=?))",customer,customer);
        jdbc.update("DELETE FROM app_session WHERE customer_id=?",customer);
        jdbc.update("DELETE FROM account_authentication_proof WHERE account_id IN(SELECT id FROM customer_account WHERE customer_id=?)",customer);
        jdbc.update("DELETE FROM access_grant WHERE customer_id=?",customer);
        jdbc.update("DELETE FROM email_challenge WHERE booking_id IN(SELECT id FROM booking WHERE customer_id=?) OR email_key=(SELECT email_key FROM customer_identity WHERE id=?)",customer,customer);
        jdbc.update("DELETE FROM customer_account WHERE customer_id=?",customer);
        jdbc.update("UPDATE notification_job SET state='SUPPRESSED',lease_until=NULL,lease_token=NULL,encrypted_envelope=NULL,safe_error='PRIVACY_ERASED' WHERE booking_id IN(SELECT id FROM booking WHERE customer_id=?)",customer);
        jdbc.update("UPDATE outbox_event SET expanded_at=COALESCE(expanded_at,clock_timestamp()),payload='{}'::jsonb WHERE booking_id IN(SELECT id FROM booking WHERE customer_id=?)",customer);
        jdbc.update("UPDATE booking_contact_change SET old_email='erased@privacy.invalid',new_email='erased@privacy.invalid' WHERE booking_id IN(SELECT id FROM booking WHERE customer_id=?)",customer);
        jdbc.update("UPDATE booking_snapshot SET document=jsonb_set(jsonb_set(document,'{creationEmail}','\"erased@privacy.invalid\"'::jsonb),'{displayZone}','\"Europe/Brussels\"'::jsonb) WHERE booking_id IN(SELECT id FROM booking WHERE customer_id=?)",customer);
        jdbc.update("UPDATE booking SET delivery_email='erased@privacy.invalid',meeting_id=NULL,meeting_url=NULL,meeting_erased=true,version=version+1,calendar_revision=calendar_revision+1 WHERE customer_id=?",customer);
        jdbc.update("UPDATE customer_identity SET email_key=id::text||'@privacy.invalid',privacy_erased=true WHERE id=?",customer);
    }
}
