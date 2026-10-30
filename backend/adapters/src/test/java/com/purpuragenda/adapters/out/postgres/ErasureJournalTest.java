package com.purpuragenda.adapters.out.postgres;
import com.purpuragenda.adapters.out.security.Secrets;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class ErasureJournalTest {
 @TempDir Path directory;
 Secrets secrets() throws Exception {Path key=directory.resolve("key");if(!Files.exists(key))Files.write(key,new byte[32]);return new Secrets(key,"privacy");}
 EncryptedErasureJournal.Entry entry(){String recipe="{\"synthetic\":\""+UUID.randomUUID()+"\"}";return new EncryptedErasureJournal.Entry(UUID.randomUUID(),Secrets.hash(recipe),recipe);}
 @Test void encryptedDurableIdempotentJournalSurvivesReopenAndRejectsLoss() throws Exception {
  var journal=new EncryptedErasureJournal(directory.resolve("journal"),secrets());UUID installation=journal.initialize(null);var entry=entry();journal.append(entry);journal.append(entry);assertEquals(List.of(entry),journal.entries());
  var reopened=new EncryptedErasureJournal(directory.resolve("journal"),secrets());assertEquals(installation,reopened.initialize(installation));assertEquals(List.of(entry),reopened.entries());assertFalse(Files.readString(directory.resolve("journal/1.enc")).contains("synthetic"));
  assertThrows(IllegalStateException.class,()->reopened.initialize(UUID.randomUUID()));Files.delete(directory.resolve("journal/1.enc"));assertThrows(IllegalStateException.class,reopened::entries);
 }
 @Test void invalidAndOverCapacityAppendNeverPoisonCommittedJournal() throws Exception {
  var journal=new EncryptedErasureJournal(directory.resolve("journal"),secrets(),1);journal.initialize(null);var entry=entry();journal.append(entry);
  assertThrows(IllegalArgumentException.class,()->new EncryptedErasureJournal.Entry(UUID.randomUUID(),"0".repeat(64),"synthetic"));assertThrows(NullPointerException.class,()->new EncryptedErasureJournal.Entry(null,entry.digest(),entry.recipe()));
  assertThrows(IllegalStateException.class,()->journal.append(entry()));assertEquals(List.of(entry),journal.entries());
 }
 @Test void crashAfterRecordBeforeHeadIsRecoveredAndWrongKeyFailsClosed() throws Exception {
  var journal=new EncryptedErasureJournal(directory.resolve("journal"),secrets());journal.initialize(null);String head=Files.readString(directory.resolve("journal/head.enc"));var entry=entry();journal.append(entry);Files.writeString(directory.resolve("journal/head.enc"),head);
  assertEquals(List.of(entry),journal.entries());assertEquals(List.of(entry),journal.entries());Files.write(directory.resolve("other"),new byte[31]);assertThrows(IllegalStateException.class,()->new Secrets(directory.resolve("other"),"privacy"));
  byte[] wrong=new byte[32];wrong[0]=1;Files.write(directory.resolve("other"),wrong);assertThrows(IllegalStateException.class,()->new EncryptedErasureJournal(directory.resolve("journal"),new Secrets(directory.resolve("other"),"privacy")).entries());
 }
 @Test void allBytesAreWrittenAcrossDeliberatelyShortWrites() throws Exception {
  var output=new java.io.ByteArrayOutputStream();WritableByteChannel shortWriter=new WritableByteChannel(){public int write(ByteBuffer b){int count=Math.min(2,b.remaining());for(int i=0;i<count;i++)output.write(b.get());return count;}public boolean isOpen(){return true;}public void close(){}};
  byte[] expected="synthetic ciphertext".getBytes(java.nio.charset.StandardCharsets.UTF_8);EncryptedErasureJournal.writeFully(shortWriter,ByteBuffer.wrap(expected));assertArrayEquals(expected,output.toByteArray());
 }
 @Test void symlinkAndMissingInitializedJournalAreRejected() throws Exception {
  var journal=new EncryptedErasureJournal(directory.resolve("missing"),secrets());assertThrows(IllegalStateException.class,()->journal.initializeForRestore(null));assertThrows(IllegalStateException.class,()->journal.initialize(UUID.randomUUID()));
  Files.createSymbolicLink(directory.resolve("link"),directory.resolve("missing"));assertThrows(IllegalStateException.class,()->new EncryptedErasureJournal(directory.resolve("link"),secrets()).initialize(null));
 }
 @Test void oversizedFrameIsRejectedBeforePublishing() throws Exception {
  var journal=new EncryptedErasureJournal(directory.resolve("journal"),secrets());journal.initialize(null);var first=entry();journal.append(first);
  String recipe="x".repeat(100000);assertThrows(IllegalStateException.class,()->journal.append(new EncryptedErasureJournal.Entry(UUID.randomUUID(),Secrets.hash(recipe),recipe)));
  assertEquals(List.of(first),journal.entries());assertFalse(Files.exists(directory.resolve("journal/2.enc")));
 }
}
