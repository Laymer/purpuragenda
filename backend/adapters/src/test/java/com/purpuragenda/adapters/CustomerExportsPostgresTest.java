package com.purpuragenda.adapters;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.adapters.out.security.Secrets;
import com.purpuragenda.application.privacy.AdministratorExports;
import com.purpuragenda.application.port.out.CustomerExportStore.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
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
class CustomerExportsPostgresTest {
    final AdminBookingPostgresTest b=new AdminBookingPostgresTest();@TempDir Path directory;
    CustomerCreationPostgresTest c;PostgresCustomerExports store;AdministratorExports service;UUID booking,customer;Secrets secrets;
    @BeforeAll static void start(){MigrationTest.start();}
    @AfterAll static void stop(){MigrationTest.stop();}
    @BeforeEach void setup() throws Exception {
        b.directory=directory;b.setup();c=b.c;booking=b.create("create",b.request(3,10)).entityId();customer=c.load(booking).customerId();
        secrets=new Secrets(directory.resolve("delivery"),"delivery");store=new PostgresCustomerExports(c.f.source,secrets,Set.of("zoom.us"));service=new AdministratorExports(store);
    }
    @AfterEach void cleanup(){b.cleanup();}
    Receipt prepare(){return service.prepare(b.session,customer,"export","Synthetic privacy request");}
    Document preview(UUID id){return service.read(b.session,id,false);}
    @Test void encryptedCompletePreviewIncludesPrivateNotesButNoAuthenticationMaterial() throws Exception {
        c.jdbc.update("INSERT INTO booking_note VALUES(?,?,?,?,clock_timestamp())",UUID.randomUUID(),booking,b.adminId,"Synthetic customer note");
        c.jdbc.update("INSERT INTO customer_profile(customer_id,document) VALUES(?,?::jsonb)",customer,"{\"firstName\":\"Synthetic Profile\"}");
        Receipt receipt=prepare();Document doc=preview(receipt.entityId());var data=JsonMapper.builder().build().readTree(doc.json());
        assertEquals(customer.toString(),data.path("identity").path("id").asText());assertEquals("Synthetic Profile",data.path("profile").path("firstName").asText());
        assertEquals("Synthetic customer note",data.path("bookings").get(0).path("notes").get(0).path("text").asText());
        assertEquals("Synthetic intake",data.path("bookings").get(0).path("snapshot").path("intake").path("message").asText());
        for(String forbidden:List.of("synthetic-secret","synthetic-hash","token_hash","csrf","password","encrypted_envelope"))assertFalse(doc.json().contains(forbidden));
        String ciphertext=c.f.value("SELECT encrypted_document FROM privacy_export");assertFalse(ciphertext.contains("Synthetic"));assertFalse(ciphertext.contains("customer@example.test"));
        assertEquals("CustomerExport[REDACTED]",doc.toString());assertFalse(doc.released());assertEquals(Failure.Kind.FORBIDDEN,b.fail(()->service.read(b.session,doc.id(),true)).kind());
        assertEquals(1,c.count("privacy_request WHERE state='PREVIEW'"));assertEquals(1,c.count("audit_event WHERE type='EXPORT_PREPARED'"));
        assertEquals(receipt,prepare());assertEquals(1,c.count("privacy_export"));
        assertEquals(Code.IDEMPOTENCY_KEY_REUSED,service.prepare(b.session,customer,"export","Different synthetic reason").code());
    }
    @Test void explicitReviewBindsDigestAndReplaysWithoutExtendingExpiry() {
        UUID id=prepare().entityId();Document before=preview(id);
        assertEquals(Code.STALE_VERSION,service.release(b.session,id,"wrong",new Review("0".repeat(64),true)).code());
        assertFalse(preview(id).released());var review=new Review(before.digest(),true);
        Receipt result=service.release(b.session,id,"release",review);assertTrue(result.success());assertEquals(result,service.release(b.session,id,"release",review));
        Document download=service.read(b.session,id,true);assertEquals(before.json(),download.json());assertEquals(before.expiresAt(),download.expiresAt());assertTrue(download.released());
        assertEquals(1,c.count("audit_event WHERE type='EXPORT_REVIEWED'"));assertEquals(1,c.count("audit_event WHERE type='EXPORT_DOWNLOADED'"));
        assertEquals(3,c.count("audit_event WHERE metadata->>'exportId'='"+id+"' AND metadata->>'customerId'='"+customer+"'"));
        assertEquals(Code.IDEMPOTENCY_KEY_REUSED,service.release(b.session,id,"release",new Review("0".repeat(64),true)).code());
    }
    @ParameterizedTest @ValueSource(strings={"disabled","epoch","revoked","browser","missing","stale"})
    void persistedFreshAuthorityPrecedesPreviewDownloadReleaseAndReplay(String failure) {
        UUID id=prepare().entityId();Document doc=preview(id);SessionBinding binding=b.session;
        switch(failure){case "disabled"->c.jdbc.update("UPDATE admin_user SET enabled=false");case "epoch"->c.jdbc.update("UPDATE admin_user SET security_version=security_version+1");case "revoked"->c.jdbc.update("UPDATE app_session SET revoked_at=clock_timestamp()");case "browser"->binding=new SessionBinding(binding.sessionId(),"a".repeat(64));case "missing"->binding=new SessionBinding(UUID.randomUUID(),binding.browserHash());case "stale"->c.jdbc.update("UPDATE app_session SET issued_at=issued_at-interval '11 minutes',absolute_expires_at=absolute_expires_at-interval '11 minutes',authenticated_at=authenticated_at-interval '11 minutes'");}
        var denied=binding;Failure.Kind expected=failure.equals("stale")?Failure.Kind.FRESH_AUTHENTICATION_REQUIRED:Failure.Kind.AUTHENTICATION_REQUIRED;
        assertEquals(expected,b.fail(()->service.prepare(denied,customer,"export","Synthetic privacy request")).kind());
        assertEquals(expected,b.fail(()->service.release(denied,id,"release",new Review(doc.digest(),true))).kind());
        assertEquals(expected,b.fail(()->service.read(denied,id,false)).kind());assertEquals(expected,b.fail(()->service.read(denied,UUID.randomUUID(),true)).kind());
    }
    @Test void expiryEqualityRevokesEveryAccessAndPurgeRemovesOnlyExpiredCiphertext() {
        UUID id=prepare().entityId();Document doc=preview(id);var calls=new AtomicInteger();
        c.jdbc.update("UPDATE app_session SET authenticated_at=?,last_seen_at=? WHERE id=?",Timestamp.from(doc.expiresAt()),Timestamp.from(doc.expiresAt()),b.session.sessionId());
        var expired=new AdministratorExports(new PostgresCustomerExports(c.clock(doc.expiresAt(),calls),secrets,Set.of("zoom.us")));
        assertEquals(Failure.Kind.NOT_FOUND,b.fail(()->expired.read(b.session,id,false)).kind());assertEquals(1,calls.get());
        assertEquals(Failure.Kind.NOT_FOUND,b.fail(()->expired.prepare(b.session,customer,"export","Synthetic privacy request")).kind());
        assertEquals(Failure.Kind.NOT_FOUND,b.fail(()->expired.release(b.session,id,"release",new Review(doc.digest(),true))).kind());
        c.jdbc.update("UPDATE privacy_export SET expires_at=created_at-interval '1 hour',created_at=created_at-interval '2 hours' WHERE id=?",id);
        assertEquals(1,store.purge(10));assertEquals(0,store.purge(10));assertEquals(1,c.count("privacy_request"));
    }
    @Test void swappedOrCorruptedCiphertextCannotBePreviewedOrReleased() {
        UUID first=prepare().entityId(),second=service.prepare(b.session,customer,"second","Synthetic privacy request").entityId();Document doc=preview(second);
        c.jdbc.update("UPDATE privacy_export SET encrypted_document=(SELECT encrypted_document FROM privacy_export WHERE id=?) WHERE id=?",first,second);
        assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->preview(second)).kind());assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->service.release(b.session,second,"release",new Review(doc.digest(),true))).kind());
        for(String corrupt:List.of("{}","[]","[3]","[\"invalid\"]")){c.jdbc.update("UPDATE privacy_export SET encrypted_document=? WHERE id=?",corrupt,second);assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->preview(second)).kind());}
    }
    @Test void longUnicodeDocumentsRoundTripAcrossAuthenticatedChunks() {
        String text="😀".repeat(4000);for(int n=0;n<12;n++)c.jdbc.update("INSERT INTO booking_note VALUES(?,?,?,?,clock_timestamp())",UUID.randomUUID(),booking,b.adminId,text);
        UUID id=prepare().entityId();assertTrue(preview(id).json().contains(text));assertTrue(c.f.value("SELECT encrypted_document FROM privacy_export").length()>65536);
        String digest=preview(id).digest();c.jdbc.update("UPDATE privacy_export SET digest=? WHERE id=?","0".repeat(64),id);assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->preview(id)).kind());c.jdbc.update("UPDATE privacy_export SET digest=? WHERE id=?",digest,id);
        assertEquals(digest,preview(id).digest());
    }
    @Test void authenticatedManifestRejectsShortenedDocumentsAndShiftedExpiry() throws Exception {
        for(int n=0;n<4;n++)c.jdbc.update("INSERT INTO booking_note VALUES(?,?,?,?,clock_timestamp())",UUID.randomUUID(),booking,b.adminId,"Synthetic long note ".repeat(200));
        UUID id=prepare().entityId();String encrypted=c.f.value("SELECT encrypted_document FROM privacy_export WHERE id=?",id);
        var json=JsonMapper.builder().build();var parts=(com.fasterxml.jackson.databind.node.ArrayNode)json.readTree(encrypted);assertTrue(parts.size()>1);parts.remove(parts.size()-1);
        var shortened=new StringBuilder();for(var part:parts)shortened.append(json.readTree(secrets.decrypt(part.asText())).path("data").asText());
        c.jdbc.update("UPDATE privacy_export SET encrypted_document=?,digest=? WHERE id=?",parts.toString(),Secrets.hash(shortened.toString()),id);
        assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->preview(id)).kind());
        UUID other=service.prepare(b.session,customer,"other","Synthetic privacy request").entityId();
        c.jdbc.update("UPDATE privacy_export SET created_at=created_at-interval '1 minute',expires_at=expires_at-interval '1 minute' WHERE id=?",other);
        assertEquals(Failure.Kind.STORAGE_FAILURE,b.fail(()->preview(other)).kind());
    }
    @Test void concurrentSameKeyProducesOneSnapshotAndAudit() throws Exception {
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var tasks=new ArrayList<Future<Receipt>>();for(int n=0;n<20;n++)tasks.add(pool.submit(this::prepare));
            var ids=new HashSet<UUID>();for(var task:tasks)ids.add(task.get(20,TimeUnit.SECONDS).entityId());assertEquals(1,ids.size());
        }assertEquals(1,c.count("privacy_export"));assertEquals(1,c.count("audit_event WHERE type='EXPORT_PREPARED'"));
    }
    @Test void nonAdministratorAndUnknownTargetsStayPrivateAndCleanupIsBounded() {
        try(var issued=c.sessions.anonymous()){var session=c.binding(issued.session());assertEquals(Failure.Kind.FORBIDDEN,b.fail(()->service.prepare(session,customer,"export","Synthetic privacy request")).kind());}
        assertEquals(Failure.Kind.NOT_FOUND,b.fail(()->service.prepare(b.session,UUID.randomUUID(),"export","Synthetic privacy request")).kind());
        for(int n=0;n<3;n++)c.jdbc.update("INSERT INTO privacy_export(id,encrypted_document,expires_at) VALUES(?,'legacy',clock_timestamp()+interval '1 hour')",UUID.randomUUID());
        UUID id=prepare().entityId();assertEquals(2,store.purge(2));assertEquals(1,store.purge(2));assertEquals(0,store.purge(2));assertNotNull(preview(id));
        assertThrows(IllegalArgumentException.class,()->store.purge(0));assertThrows(IllegalArgumentException.class,()->store.purge(501));
        var unavailable=new org.springframework.jdbc.datasource.DelegatingDataSource(c.f.source){
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {throw new java.sql.SQLException("SYNTHETIC_PRIVATE_PROVIDER");}
        };
        var failure=b.fail(()->new PostgresCustomerExports(unavailable,secrets,Set.of("zoom.us")).purge(1));
        assertEquals(Failure.Kind.STORAGE_FAILURE,failure.kind());assertNull(failure.getCause());
    }
}
