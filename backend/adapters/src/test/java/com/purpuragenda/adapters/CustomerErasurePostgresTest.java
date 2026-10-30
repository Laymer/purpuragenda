package com.purpuragenda.adapters;
import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.application.port.out.CustomerErasureStore.*;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Code;
import com.purpuragenda.application.scheduling.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import static org.junit.jupiter.api.Assertions.*;
@Timeout(240) @ResourceLock("postgres-migration-fixture")
class CustomerErasurePostgresTest {
 final CustomerExportsPostgresTest f=new CustomerExportsPostgresTest();@TempDir Path directory;
 PostgresCustomerErasure store;EncryptedErasureJournal journal;UUID note;
 @BeforeAll static void start(){MigrationTest.start();}@AfterAll static void stop(){MigrationTest.stop();}
 @BeforeEach void setup() throws Exception {f.directory=directory;f.setup();journal=new EncryptedErasureJournal(directory.resolve("journal"),f.secrets);store=new PostgresCustomerErasure(f.c.f.source,f.secrets,journal,Set.of("zoom.us"));store.recover(false);note=new AdministratorNotes(new PostgresNotes(f.c.f.source,Set.of("zoom.us"))).append(f.b.session,f.booking,"original",new AdministratorNotes.Addition("Synthetic private note")).entityId();}
 @AfterEach void cleanup(){f.cleanup();}
 Preview prepare(Scope... scopes){UUID id=store.prepare(f.b.session,f.customer,"prepare",new Request(List.of(scopes),"Synthetic erasure request")).entityId();return store.preview(f.b.session,id);}
 @Test void reviewedNoteErasureIsDurableAndOldRetryCannotResurrectIt(){
  var before=f.c.load(f.booking);var p=prepare(new Scope(Kind.NOTE,note));assertEquals(1,p.selected().size());assertTrue(p.retained().size()>0);assertEquals(1,f.c.count("booking_note"));
  assertTrue(store.confirm(f.b.session,p.id(),"confirm",1,new Review(p.digest(),true)).success());assertEquals(0,f.c.count("booking_note"));assertFalse(store.blocked());assertEquals(1,journal.entries().size());assertEquals(1,f.c.count("erasure_tombstone"));
  assertTrue(store.confirm(f.b.session,p.id(),"confirm",1,new Review(p.digest(),true)).success());store.recover(false);assertEquals(before,f.c.load(f.booking));
  assertEquals(Code.IDEMPOTENCY_KEY_REUSED,new AdministratorNotes(new PostgresNotes(f.c.f.source,Set.of("zoom.us"))).append(f.b.session,f.booking,"original",new AdministratorNotes.Addition("Synthetic private note")).code());assertEquals(0,f.c.count("booking_note"));
 }
 @Test void intakeHasAnExplicitErasedStateAndKeepsServicePolicyMoneyAndOccupancy(){
  var before=f.c.load(f.booking);var p=prepare(new Scope(Kind.INTAKE,f.booking));store.confirm(f.b.session,p.id(),"confirm",1,new Review(p.digest(),true));var after=f.c.load(f.booking);assertTrue(after.snapshot().intakeErased());assertNull(after.snapshot().intake());assertEquals(before.snapshot().service(),after.snapshot().service());assertEquals(before.snapshot().policy(),after.snapshot().policy());assertEquals(before.interval(),after.interval());assertEquals(0,f.c.count("payment_entry"));
 }
 @Test void scopedHoldPreventsErasureUntilExplicitRelease(){
  Scope scope=new Scope(Kind.NOTE,note);UUID hold=store.hold(f.b.session,f.customer,"hold",new Hold(scope,"Synthetic necessary hold",null,true)).entityId();assertTrue(store.overview(f.b.session,f.customer).items().stream().filter(i->i.scope().equals(scope)).findFirst().orElseThrow().holds().contains(hold));assertThrows(RuntimeException.class,()->prepare(scope));store.releaseHold(f.b.session,hold,"release","Synthetic hold released",true);assertNotNull(prepare(scope));
 }
 @Test void appliedRetryRequiresJournalAndAppliedDatabaseEvidence() throws Exception {
  var p=prepare(new Scope(Kind.NOTE,note));store.confirm(f.b.session,p.id(),"confirm",1,new Review(p.digest(),true));
  f.c.jdbc.update("UPDATE erasure_tombstone SET encrypted_document='invalid' WHERE id=?",p.id());
  assertThrows(RuntimeException.class,()->store.confirm(f.b.session,p.id(),"confirm",1,new Review(p.digest(),true)));
  assertThrows(RuntimeException.class,()->store.recover(false));
 }
 @Test void appliedRetryRejectsMissingJournalAndChangedDurableBindings() throws Exception {
  var p=prepare(new Scope(Kind.NOTE,note));store.confirm(f.b.session,p.id(),"confirm",1,new Review(p.digest(),true));
  f.c.jdbc.update("UPDATE privacy_erasure SET journal_digest=repeat('0',64) WHERE id=?",p.id());
  assertThrows(RuntimeException.class,()->store.confirm(f.b.session,p.id(),"confirm",1,new Review(p.digest(),true)));
  Files.delete(directory.resolve("journal/1.enc"));
  assertThrows(RuntimeException.class,()->store.confirm(f.b.session,p.id(),"confirm",1,new Review(p.digest(),true)));
 }
 @Test void replayDoesNotEraseLaterLegitimateNotes() {
  var p=prepare(new Scope(Kind.NOTE,note));store.confirm(f.b.session,p.id(),"confirm",1,new Review(p.digest(),true));
  UUID later=new AdministratorNotes(new PostgresNotes(f.c.f.source,Set.of("zoom.us"))).append(f.b.session,f.booking,"later",new AdministratorNotes.Addition("Later synthetic note")).entityId();
  store.recover(false);assertEquals(1,f.c.count("booking_note"));assertEquals(later,f.c.jdbc.queryForObject("SELECT id FROM booking_note",UUID.class));
 }
 @Test void approvedCrashRetriesValidateEveryBindingBeforeJournalPublication() throws Exception {
  var p=prepare(new Scope(Kind.NOTE,note));Files.delete(directory.resolve("journal/head.enc"));
  assertThrows(RuntimeException.class,()->store.confirm(f.b.session,p.id(),"confirm",1,new Review(p.digest(),true)));assertTrue(store.blocked());
  f.c.jdbc.update("UPDATE privacy_erasure SET journal_digest=repeat('0',64) WHERE id=?",p.id());
  assertThrows(RuntimeException.class,()->store.confirm(f.b.session,p.id(),"confirm",1,new Review(p.digest(),true)));assertEquals(1,f.c.count("booking_note"));
 }
 @Test void identityBeforeAccountAuditIsDeterministicallyRedacted() {
  // Start with a pending booking and close it through the domain before reviewing identity.
  f.c.scheduling.execute(f.c.internal(),tx->tx.attempt(()->{var b=tx.booking(f.booking).orElseThrow().reject();tx.save(b);return new com.purpuragenda.application.port.out.SchedulingUnitOfWork.Receipt(Code.OK,b.id(),b.version());}));
  UUID account=UUID.randomUUID(),audit=UUID.randomUUID();
  f.c.jdbc.update("INSERT INTO customer_account(id,customer_id,enrolled_at) VALUES(?,?,clock_timestamp())",account,f.customer);
  f.c.jdbc.update("INSERT INTO audit_event(id,actor_id,actor_kind,type,occurred_at,reason,metadata) VALUES(?,?,'ACCOUNT','ACCOUNT_PROFILE_CHANGED',clock_timestamp(),'Synthetic account reason','{}')",audit,account);
  var p=prepare(new Scope(Kind.IDENTITY,f.customer),new Scope(Kind.AUDIT,audit));store.confirm(f.b.session,p.id(),"confirm",1,new Review(p.digest(),true));
  assertEquals("",f.c.jdbc.queryForObject("SELECT reason FROM audit_event WHERE id=?",String.class,audit));assertEquals(0,f.c.count("customer_account"));assertFalse(store.blocked());
 }
 @Test void earlierActiveBackupRetainsBarrierUntilTerminalEvidenceIsReconciled() throws Exception {
  f.c.scheduling.execute(f.c.internal(),tx->tx.attempt(()->{var b=tx.booking(f.booking).orElseThrow().reject();tx.save(b);return new com.purpuragenda.application.port.out.SchedulingUnitOfWork.Receipt(Code.OK,b.id(),b.version());}));
  var p=prepare(new Scope(Kind.IDENTITY,f.customer));store.confirm(f.b.session,p.id(),"confirm",1,new Review(p.digest(),true));
  // Restore the earlier database state while preserving the external journal.
  try(var c=f.c.f.database.connection(MigrationTest.MIGRATOR)){
   c.setAutoCommit(false);MigrationTest.execute(c,"ALTER TABLE booking DISABLE TRIGGER USER");MigrationTest.execute(c,"ALTER TABLE booking_occupancy DISABLE TRIGGER USER");
   MigrationTest.execute(c,"UPDATE booking SET state='PENDING_CONFIRMATION',meeting_erased=false WHERE id=?",f.booking);
   MigrationTest.execute(c,"UPDATE booking_occupancy SET released_at=NULL WHERE booking_id=?",f.booking);
   MigrationTest.execute(c,"DELETE FROM erasure_tombstone");MigrationTest.execute(c,"DELETE FROM privacy_erasure_target");MigrationTest.execute(c,"DELETE FROM privacy_erasure");
   MigrationTest.execute(c,"ALTER TABLE booking ENABLE TRIGGER USER");MigrationTest.execute(c,"ALTER TABLE booking_occupancy ENABLE TRIGGER USER");c.commit();
  }
  assertThrows(RuntimeException.class,()->store.recover(true));assertTrue(store.blocked());
  assertEquals("PENDING_CONFIRMATION",f.c.jdbc.queryForObject("SELECT state FROM booking WHERE id=?",String.class,f.booking));assertEquals(0,f.c.count("erasure_tombstone"));
  assertEquals(1,f.c.count("operation_control WHERE delivery_suppressed AND privacy_owner IS NOT NULL"));
  var entry=journal.entries().getFirst();assertThrows(RuntimeException.class,()->store.reconcile(p.id(),entry.digest(),"Synthetic independently reconciled facts"));
  try(var c=f.c.f.source.getConnection()){
   c.setAutoCommit(false);MigrationTest.execute(c,"UPDATE booking SET state='REJECTED' WHERE id=?",f.booking);MigrationTest.execute(c,"UPDATE booking_occupancy SET released_at=clock_timestamp() WHERE booking_id=?",f.booking);c.commit();
  }
  assertThrows(RuntimeException.class,()->store.recover(true)); // Matching rows alone are not operator acknowledgment.
  store.reconcile(p.id(),entry.digest(),"Synthetic independently reconciled facts");store.recover(true);store.recover(true);
  assertEquals(1,f.c.count("erasure_tombstone"));assertEquals(1,f.c.count("operation_control WHERE maintenance AND delivery_suppressed AND privacy_owner IS NULL"));
 }
 void corrupt(String table,String sql,Object... values){
  try(var c=f.c.f.database.connection(MigrationTest.MIGRATOR)){c.setAutoCommit(false);MigrationTest.execute(c,"ALTER TABLE "+table+" DISABLE TRIGGER USER");MigrationTest.execute(c,sql,values);MigrationTest.execute(c,"ALTER TABLE "+table+" ENABLE TRIGGER USER");c.commit();}catch(java.sql.SQLException failure){throw MigrationTest.safe(failure);}
 }
 @Test void nullErasedMarkersAndMalformedIntakeFailDeferredDatabaseValidation() {
  for(String expression:List.of("document-'intake'","jsonb_set(document,'{intake}','null')","jsonb_set(document,'{intakeErased}','null')","jsonb_set(document,'{intakeErased}','\"true\"')","jsonb_set(document,'{intake,firstName}','null')")){
   String original=f.c.jdbc.queryForObject("SELECT document::text FROM booking_snapshot WHERE booking_id=?",String.class,f.booking);
   corrupt("booking_snapshot","UPDATE booking_snapshot SET document="+expression+" WHERE booking_id=?",f.booking);
   assertThrows(RuntimeException.class,()->f.c.jdbc.update("UPDATE booking SET version=version+1 WHERE id=?",f.booking));
   corrupt("booking_snapshot","UPDATE booking_snapshot SET document=?::jsonb WHERE booking_id=?",original,f.booking);
  }
 }
 @Test void approvedCrashRecoveryPreservesPriorSuppressionAndFinancialRelationships() throws Exception {
  UUID receipt=UUID.randomUUID(),refund=UUID.randomUUID();
  f.c.jdbc.update("INSERT INTO payment_entry VALUES(?,?,'RECEIPT',1500,'EUR','BANK','synthetic-receipt',clock_timestamp(),NULL)",receipt,f.booking);
  f.c.jdbc.update("INSERT INTO payment_entry VALUES(?,?,'REFUND',500,'EUR','BANK','synthetic-refund',clock_timestamp(),?)",refund,f.booking,receipt);
  var financial=f.c.jdbc.queryForList("SELECT * FROM payment_entry ORDER BY id");
  UUID export=f.service.prepare(f.b.session,f.customer,"export","Synthetic export reason").entityId();
  var p=prepare(new Scope(Kind.NOTE,note));f.c.jdbc.update("UPDATE operation_control SET delivery_suppressed=true WHERE id=1");
  Path head=directory.resolve("journal/head.enc");String saved=Files.readString(head);Files.delete(head);
  assertThrows(RuntimeException.class,()->store.confirm(f.b.session,p.id(),"confirm",1,new Review(p.digest(),true)));assertTrue(store.blocked());
  Files.writeString(head,saved);store.recover(false);assertFalse(store.blocked());assertEquals(1,f.c.count("operation_control WHERE delivery_suppressed"));assertEquals(financial,f.c.jdbc.queryForList("SELECT * FROM payment_entry ORDER BY id"));
  assertEquals(0,f.c.count("booking_note"));assertEquals(0,f.c.count("privacy_export"));assertThrows(RuntimeException.class,()->f.service.read(f.b.session,export,false));
 }
 @Test void backupContainingPreviewIsPromotedOnlyByMatchingAuthenticatedJournal() {
  var p=prepare(new Scope(Kind.NOTE,note));String encrypted=f.c.jdbc.queryForObject("SELECT encrypted_proposal FROM privacy_erasure WHERE id=?",String.class,p.id());
  store.confirm(f.b.session,p.id(),"confirm",1,new Review(p.digest(),true));
  f.c.jdbc.update("DELETE FROM erasure_tombstone WHERE id=?",p.id());f.c.jdbc.update("DELETE FROM privacy_erasure_target WHERE request_id=?",p.id());
  f.c.jdbc.update("UPDATE privacy_erasure SET state='PREVIEW',encrypted_proposal=?,approved_at=NULL,applied_at=NULL,confirm_key=NULL,journal_digest=NULL WHERE id=?",encrypted,p.id());
  f.c.jdbc.update("INSERT INTO booking_note VALUES(?,?,?,'Restored synthetic note',clock_timestamp())",note,f.booking,f.b.adminId);
  store.recover(true);assertEquals(0,f.c.count("booking_note"));assertEquals(1,f.c.count("privacy_erasure WHERE state='APPLIED'"));store.recover(true);
 }
 @Test void profileAndCredentialWritersCannotCrossTheCommittedBarrier() throws Exception {
  f.c.jdbc.update("INSERT INTO customer_profile(customer_id,document) VALUES(?,?::jsonb)",f.customer,"{\"firstName\":\"Synthetic\",\"lastName\":\"Customer\",\"telephone\":\"\"}");
  UUID account=UUID.randomUUID();f.c.jdbc.update("INSERT INTO customer_account(id,customer_id,enrolled_at) VALUES(?,?,clock_timestamp())",account,f.customer);
  var p=prepare(new Scope(Kind.PROFILE,f.customer));var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
  try(var writer=f.c.f.source.getConnection()){
   writer.setAutoCommit(false);MigrationTest.execute(writer,"SELECT customer_id FROM customer_profile WHERE customer_id=? FOR UPDATE",f.customer);
   var applying=pool.submit(()->store.confirm(f.b.session,p.id(),"confirm",1,new Review(p.digest(),true)));
   long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);while(!store.blocked()&&System.nanoTime()<deadline)Thread.sleep(10);assertTrue(store.blocked());
   assertThrows(RuntimeException.class,()->f.c.jdbc.update("UPDATE customer_account SET credential_version=credential_version WHERE id=?",account));
   assertThrows(AssertionError.class,()->MigrationTest.execute(writer,"UPDATE customer_profile SET document='{}',version=version+1 WHERE customer_id=?",f.customer));writer.rollback();
   assertTrue(applying.get(10,java.util.concurrent.TimeUnit.SECONDS).success());assertFalse(store.blocked());
  }finally{pool.shutdownNow();}
  f.c.jdbc.update("UPDATE customer_profile SET document=?::jsonb,version=version+1 WHERE customer_id=?","{\"firstName\":\"Later synthetic profile\"}",f.customer);
  store.recover(false);assertEquals("Later synthetic profile",f.c.jdbc.queryForObject("SELECT document->>'firstName' FROM customer_profile WHERE customer_id=?",String.class,f.customer));
 }
 @Test void backupPredatingSelectedAuditTreatsItsAbsentCopyAsANoOp() {
  UUID audit=UUID.randomUUID();f.c.jdbc.update("INSERT INTO audit_event(id,actor_kind,type,reason,occurred_at,metadata) VALUES(?,'ADMIN','SYNTHETIC_REVIEW','Synthetic reason',clock_timestamp(),jsonb_build_object('customerId',?::text))",audit,f.customer);
  var p=prepare(new Scope(Kind.AUDIT,audit));store.confirm(f.b.session,p.id(),"confirm",1,new Review(p.digest(),true));
  f.c.jdbc.update("DELETE FROM erasure_tombstone WHERE id=?",p.id());f.c.jdbc.update("DELETE FROM privacy_erasure_target WHERE request_id=?",p.id());f.c.jdbc.update("DELETE FROM privacy_erasure WHERE id=?",p.id());
  corrupt("audit_event","DELETE FROM audit_event WHERE id=?",audit);
  store.recover(true);assertEquals(1,f.c.count("erasure_tombstone"));assertEquals(0,f.c.count("audit_event WHERE id='"+audit+"'"));assertEquals(1,f.c.count("booking_note"));
 }
}
