package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.adapters.out.security.Secrets;
import com.purpuragenda.application.privacy.AdministratorCorrections;
import com.purpuragenda.application.scheduling.AdministratorNotes;
import com.purpuragenda.application.port.out.CustomerCorrectionStore.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(240) @ResourceLock("postgres-migration-fixture")
class CustomerCorrectionsPostgresTest {
    final CustomerExportsPostgresTest f=new CustomerExportsPostgresTest();@TempDir Path directory;
    PostgresCustomerCorrections store;AdministratorCorrections service;UUID note;
    static final String REASON="Synthetic correction reason",ORIGINAL="Original synthetic note",CORRECTED="Corrected synthetic note";
    @BeforeAll static void start(){MigrationTest.start();}@AfterAll static void stop(){MigrationTest.stop();}
    @BeforeEach void setup() throws Exception {
        f.directory=directory;f.setup();store=new PostgresCustomerCorrections(f.c.f.source,f.secrets,Set.of("zoom.us"));service=new AdministratorCorrections(store);
        note=new AdministratorNotes(new PostgresNotes(f.c.f.source,Set.of("zoom.us"))).append(f.b.session,f.booking,"original",new AdministratorNotes.Addition(ORIGINAL)).entityId();
        f.c.jdbc.update("INSERT INTO customer_profile(customer_id,document) VALUES(?,'{}'::jsonb)",f.customer);
    }
    @AfterEach void cleanup(){f.cleanup();}
    Change note(){return new Change(Kind.NOTE,Map.of("text",CORRECTED),REASON);}
    Receipt prepare(){return service.prepare(f.b.session,f.customer,note,"prepare",note());}
    Preview preview(UUID id){return service.preview(f.b.session,id);}
    Receipt confirm(Preview p,String key){return service.confirm(f.b.session,p.id(),key,1,new Review(p.digest(),true));}
    @Test void reviewedNoteReplacementRevokesExportsAndPreservesFactsAndOriginalAppendRetry() {
        var booking=f.c.load(f.booking);UUID export=f.prepare().entityId();long outbox=f.c.count("outbox_event"),payments=f.c.count("payment_entry");
        UUID id=prepare().entityId();assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->f.c.jdbc.update("UPDATE privacy_correction SET encrypted_proposal=NULL WHERE id=?",id));Preview p=preview(id);assertEquals(ORIGINAL,p.before().values().get("text"));assertEquals(CORRECTED,p.after().get("text"));assertEquals(1,p.before().version());
        String cipher=f.c.f.value("SELECT encrypted_proposal FROM privacy_correction WHERE id=?",id);assertFalse(cipher.contains("synthetic"));assertEquals(ORIGINAL,f.c.f.value("SELECT note FROM booking_note WHERE id=?",note));assertEquals(prepare().entityId(),id);
        assertEquals(Code.STALE_VERSION,service.confirm(f.b.session,id,"wrong",1,new Review("0".repeat(64),true)).code());assertEquals(Code.STALE_VERSION,service.confirm(f.b.session,id,"version",2,new Review(p.digest(),true)).code());
        Receipt result=confirm(p,"confirm");assertTrue(result.success());assertEquals(result,confirm(p,"confirm"));assertEquals(Code.STALE_VERSION,confirm(p,"other").code());
        assertEquals(CORRECTED,f.c.f.value("SELECT note FROM booking_note WHERE id=?",note));assertEquals(2,service.target(f.b.session,f.customer,Kind.NOTE,note).version());assertNull(f.c.f.value("SELECT encrypted_proposal FROM privacy_correction WHERE id=?",id));
        assertEquals(Failure.Kind.NOT_FOUND,f.b.fail(()->f.preview(export)).kind());assertEquals(booking,f.c.load(f.booking));assertEquals(outbox,f.c.count("outbox_event"));assertEquals(payments,f.c.count("payment_entry"));
        assertEquals(note,new AdministratorNotes(new PostgresNotes(f.c.f.source,Set.of("zoom.us"))).append(f.b.session,f.booking,"original",new AdministratorNotes.Addition(ORIGINAL)).entityId());
        assertEquals(1,f.c.count("privacy_correction_fact"));assertEquals(1,f.c.count("audit_event WHERE type='CUSTOMER_CORRECTED'"));assertEquals(0,f.c.count("audit_event WHERE metadata::text LIKE '%synthetic note%' OR reason LIKE '%synthetic note%'"));
        assertEquals(Failure.Kind.NOT_FOUND,f.b.fail(()->preview(id)).kind());assertEquals(Code.IDEMPOTENCY_KEY_REUSED,service.confirm(f.b.session,id,"confirm",1,new Review("0".repeat(64),true)).code());
    }
    @Test void profileVersionAndIntakeAmendmentExposeEffectiveValuesWithoutRewritingOriginal() throws Exception {
        var original=f.c.load(f.booking);var profile=new Change(Kind.PROFILE,Map.of("firstName","Corrected","lastName","Synthetic","telephone","+32400123456"),REASON);
        UUID id=service.prepare(f.b.session,f.customer,f.customer,"profile",profile).entityId();assertTrue(confirm(preview(id),"profile").success());assertEquals(2,service.target(f.b.session,f.customer,Kind.PROFILE,f.customer).version());
        var values=new TreeMap<>(service.target(f.b.session,f.customer,Kind.INTAKE,f.booking).values());values.put("firstName","Amended");values.put("message","Corrected synthetic intake");
        UUID intake=service.prepare(f.b.session,f.customer,f.booking,"intake",new Change(Kind.INTAKE,values,REASON)).entityId();assertTrue(confirm(preview(intake),"intake").success());assertEquals(original,f.c.load(f.booking));
        assertEquals("Amended",new PostgresAdministratorBookingQuery(f.c.f.source,Set.of("zoom.us")).detail(f.b.session,f.booking).orElseThrow().effectiveIntake().firstName());
        var data=com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(f.preview(f.prepare().entityId()).json());var b=data.path("bookings").get(0);assertEquals("Amended",b.path("effectiveIntake").path("firstName").asText());assertEquals(original.snapshot().intake().firstName(),b.path("snapshot").path("intake").path("firstName").asText());assertEquals(1,b.path("amendments").size());
        assertEquals(f.customer,new PostgresAdministratorCustomers(f.c.f.source).search(f.b.session,new com.purpuragenda.application.port.out.AdministratorCustomerQuery.Search("Amended",null,10)).entries().getFirst().id());
    }
    @Test void staleConcurrentChangesRequireAnotherPreviewAndNeverRevokeExports() {
        Preview first=preview(prepare().entityId());UUID second=service.prepare(f.b.session,f.customer,note,"second",new Change(Kind.NOTE,Map.of("text","Another corrected synthetic note"),REASON)).entityId();
        assertTrue(confirm(first,"first").success());UUID export=f.prepare().entityId();assertEquals(Failure.Kind.NOT_FOUND,f.b.fail(()->preview(second)).kind());
        String digest=f.c.f.value("SELECT digest FROM privacy_correction WHERE id=?",second);assertEquals(Code.STALE_VERSION,service.confirm(f.b.session,second,"stale",1,new Review(digest,true)).code());assertNotNull(f.preview(export));
        UUID profile=service.prepare(f.b.session,f.customer,f.customer,"profile",new Change(Kind.PROFILE,Map.of("firstName","A","lastName","Synthetic","telephone",""),REASON)).entityId();Preview p=preview(profile);f.c.jdbc.update("UPDATE customer_profile SET version=version+1,document=?::jsonb WHERE customer_id=?","{\"firstName\":\"Concurrent\",\"lastName\":\"Synthetic\",\"telephone\":\"\"}",f.customer);assertEquals(Code.STALE_VERSION,confirm(p,"stale-profile").code());
    }
    @ParameterizedTest @ValueSource(strings={"disabled","epoch","revoked","browser","missing","stale"})
    void persistedFreshAuthorityPrecedesAllTargetsPreviewsCommandsAndReplay(String failure) {
        Preview p=preview(prepare().entityId());assertTrue(confirm(p,"confirm").success());SessionBinding binding=f.b.session;
        switch(failure){case "disabled"->f.c.jdbc.update("UPDATE admin_user SET enabled=false");case "epoch"->f.c.jdbc.update("UPDATE admin_user SET security_version=security_version+1");case "revoked"->f.c.jdbc.update("UPDATE app_session SET revoked_at=clock_timestamp()");case "browser"->binding=new SessionBinding(binding.sessionId(),"a".repeat(64));case "missing"->binding=new SessionBinding(UUID.randomUUID(),binding.browserHash());case "stale"->f.c.jdbc.update("UPDATE app_session SET issued_at=issued_at-interval '11 minutes',absolute_expires_at=absolute_expires_at-interval '11 minutes',authenticated_at=authenticated_at-interval '11 minutes'");}
        var denied=binding;Failure.Kind expected=failure.equals("stale")?Failure.Kind.FRESH_AUTHENTICATION_REQUIRED:Failure.Kind.AUTHENTICATION_REQUIRED;
        assertEquals(expected,f.b.fail(()->service.target(denied,f.customer,Kind.NOTE,note)).kind());assertEquals(expected,f.b.fail(()->service.prepare(denied,f.customer,note,"prepare",note())).kind());assertEquals(expected,f.b.fail(()->service.preview(denied,p.id())).kind());assertEquals(expected,f.b.fail(()->service.confirm(denied,p.id(),"confirm",1,new Review(p.digest(),true))).kind());
    }
    @Test void expiryEqualityDeniesPreviewConfirmationReplayAndPurgeIsBounded() {
        Preview p=preview(prepare().entityId());f.c.jdbc.update("UPDATE app_session SET authenticated_at=?,last_seen_at=? WHERE id=?",Timestamp.from(p.expiresAt()),Timestamp.from(p.expiresAt()),f.b.session.sessionId());var calls=new AtomicInteger();var expired=new AdministratorCorrections(new PostgresCustomerCorrections(f.c.clock(p.expiresAt(),calls),f.secrets,Set.of("zoom.us")));
        assertEquals(Failure.Kind.NOT_FOUND,f.b.fail(()->expired.preview(f.b.session,p.id())).kind());assertEquals(1,calls.get());assertEquals(Failure.Kind.NOT_FOUND,f.b.fail(()->expired.confirm(f.b.session,p.id(),"confirm",1,new Review(p.digest(),true))).kind());assertEquals(Failure.Kind.NOT_FOUND,f.b.fail(()->expired.prepare(f.b.session,f.customer,note,"prepare",note())).kind());
        f.c.jdbc.update("UPDATE privacy_correction SET expires_at=created_at-interval '1 hour',created_at=created_at-interval '2 hours' WHERE id=?",p.id());assertEquals(1,store.purge(1));assertEquals(0,store.purge(1));assertEquals(1,f.c.count("privacy_request WHERE kind='CORRECTION'"));assertThrows(IllegalArgumentException.class,()->store.purge(0));assertThrows(IllegalArgumentException.class,()->store.purge(501));
    }
    @Test void envelopeAndAuthorityMetadataTamperingFailsClosed() {
        Preview first=preview(prepare().entityId());UUID second=service.prepare(f.b.session,f.customer,note,"second",note()).entityId();
        f.c.jdbc.update("UPDATE privacy_correction SET encrypted_proposal=(SELECT encrypted_proposal FROM privacy_correction WHERE id=?),digest=(SELECT digest FROM privacy_correction WHERE id=?) WHERE id=?",first.id(),first.id(),second);
        assertEquals(Failure.Kind.STORAGE_FAILURE,f.b.fail(()->preview(second)).kind());assertEquals(Failure.Kind.STORAGE_FAILURE,f.b.fail(()->service.confirm(f.b.session,second,"confirm",1,new Review(first.digest(),true))).kind());
        f.c.jdbc.update("UPDATE privacy_correction SET created_at=created_at-interval '1 minute',expires_at=expires_at-interval '1 minute' WHERE id=?",first.id());assertEquals(Failure.Kind.STORAGE_FAILURE,f.b.fail(()->preview(first.id())).kind());
        f.c.jdbc.update("UPDATE privacy_correction SET encrypted_proposal='invalid' WHERE id=?",first.id());assertEquals(Failure.Kind.STORAGE_FAILURE,f.b.fail(()->preview(first.id())).kind());
    }
    @Test void twentyConcurrentPreparationsAndConfirmationsHaveOneEffect() throws Exception {
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var tasks=new ArrayList<Future<Receipt>>();for(int n=0;n<20;n++)tasks.add(pool.submit(this::prepare));var ids=new HashSet<UUID>();for(var t:tasks)ids.add(t.get(30,TimeUnit.SECONDS).entityId());assertEquals(1,ids.size());Preview p=preview(ids.iterator().next());tasks.clear();for(int n=0;n<20;n++)tasks.add(pool.submit(()->confirm(p,"same")));for(var t:tasks)assertTrue(t.get(30,TimeUnit.SECONDS).success());
        }assertEquals(1,f.c.count("privacy_correction_fact"));assertEquals(1,f.c.count("audit_event WHERE type='CUSTOMER_CORRECTED'"));
    }
    @Test void corruptedPrepareReceiptsCannotSubstituteAnotherTargetKindOrProposal() {
        UUID original=prepare().entityId();UUID otherNote=new AdministratorNotes(new PostgresNotes(f.c.f.source,Set.of("zoom.us"))).append(f.b.session,f.booking,"other-note",new AdministratorNotes.Addition("Another synthetic note")).entityId();
        var targets=List.of(service.prepare(f.b.session,f.customer,otherNote,"other-target",note()).entityId(),service.prepare(f.b.session,f.customer,f.customer,"other-kind",new Change(Kind.PROFILE,Map.of("firstName","A","lastName","Synthetic","telephone",""),REASON)).entityId(),service.prepare(f.b.session,f.customer,note,"other-proposal",new Change(Kind.NOTE,Map.of("text","Different synthetic correction"),REASON)).entityId());
        for(UUID replacement:targets){f.c.jdbc.update("UPDATE idempotency_record SET receipt=jsonb_set(receipt,'{entityId}',to_jsonb(?::text)) WHERE key='prepare'",replacement.toString());assertEquals(Failure.Kind.STORAGE_FAILURE,f.b.fail(this::prepare).kind());}
        f.c.jdbc.update("UPDATE idempotency_record SET receipt=jsonb_set(receipt,'{entityId}',to_jsonb(?::text)) WHERE key='prepare'",original.toString());Preview p=preview(original);assertTrue(confirm(p,"confirm").success());assertEquals(original,prepare().entityId());
        UUID pending=service.prepare(f.b.session,f.customer,note,"forged-preview",note()).entityId();Preview forged=preview(pending);
        f.c.jdbc.update("UPDATE privacy_correction SET confirmed_at=clock_timestamp(),encrypted_proposal=NULL WHERE id=?",pending);
        f.c.jdbc.update("INSERT INTO idempotency_record VALUES(?,?,?,jsonb_build_object('code','OK','entityId',?::text,'version',2),clock_timestamp())",new Scope(f.b.adminId,"CONFIRM_CORRECTION",pending).storageKey(),"forged",CommandKey.fingerprint(List.of("1",forged.digest())),pending.toString());
        assertEquals(Failure.Kind.STORAGE_FAILURE,f.b.fail(()->service.confirm(f.b.session,pending,"forged",1,new Review(forged.digest(),true))).kind());
    }
    @Test void unknownForeignAndAnonymousTargetsStayPrivateAndProviderErrorsAreSafe() {
        UUID other=UUID.randomUUID();assertEquals(Failure.Kind.NOT_FOUND,f.b.fail(()->service.target(f.b.session,other,Kind.NOTE,note)).kind());assertEquals(Failure.Kind.NOT_FOUND,f.b.fail(()->service.target(f.b.session,f.customer,Kind.PROFILE,other)).kind());assertEquals(Failure.Kind.NOT_FOUND,f.b.fail(()->service.target(f.b.session,f.customer,Kind.NOTE,other)).kind());assertEquals(Failure.Kind.NOT_FOUND,f.b.fail(()->service.target(f.b.session,f.customer,Kind.INTAKE,other)).kind());
        try(var issued=f.c.sessions.anonymous()){assertEquals(Failure.Kind.FORBIDDEN,f.b.fail(()->service.target(f.c.binding(issued.session()),f.customer,Kind.NOTE,note)).kind());}
        var source=new org.springframework.jdbc.datasource.DelegatingDataSource(f.c.f.source){@Override public java.sql.Connection getConnection() throws java.sql.SQLException{throw new java.sql.SQLException("SYNTHETIC_PRIVATE_PROVIDER");}};var unavailable=new PostgresCustomerCorrections(source,f.secrets,Set.of("zoom.us"));assertEquals(Failure.Kind.STORAGE_FAILURE,f.b.fail(()->unavailable.purge(1)).kind());assertEquals(Failure.Kind.STORAGE_FAILURE,f.b.fail(()->unavailable.target(f.b.session,f.customer,Kind.NOTE,note)).kind());
    }
}
