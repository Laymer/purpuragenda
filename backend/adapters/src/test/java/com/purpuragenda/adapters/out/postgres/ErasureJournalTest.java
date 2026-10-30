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
 @SuppressWarnings("unchecked") java.util.Map<String,Object> object(Secrets key,Path file) throws Exception {return new java.util.LinkedHashMap<>(PersistedJson.read(key.decrypt(Files.readString(file)),java.util.Map.class));}
 void encrypted(Secrets key,Path file,Object value) throws Exception {Files.writeString(file,key.encrypt(PersistedJson.write(value)));}
 @Test void authenticatedMalformedHeadsAndFramesNeverAdvanceTheJournal() throws Exception {
  Secrets key=secrets();
  for(String defect:List.of("installation","null-hash","hash-format","negative","over-capacity","zero-hash","head-hash","frame-installation","frame-sequence","frame-previous","frame-digest","duplicate-id","extra-name","gap","record-symlink","oversize")){
   Path path=directory.resolve(defect);var journal=new EncryptedErasureJournal(path,key);journal.initialize(null);var first=entry();journal.append(first);
   Path headFile=path.resolve("head.enc"),frameFile=path.resolve("1.enc");var head=object(key,headFile);var frame=object(key,frameFile);
   switch(defect){
    case "installation"->head.put("installation",null);case "null-hash"->head.put("hash",null);case "hash-format"->head.put("hash","invalid");
    case "negative"->head.put("sequence",-1);case "over-capacity"->head.put("sequence",100001);case "zero-hash"->{head.put("sequence",0);head.put("hash","f".repeat(64));}
    case "head-hash"->head.put("hash","f".repeat(64));
    case "frame-installation"->frame.put("installation",UUID.randomUUID());case "frame-sequence"->frame.put("sequence",2);case "frame-previous"->frame.put("previous","f".repeat(64));case "frame-digest"->frame.put("digest","0".repeat(64));
    case "duplicate-id"->{journal.append(entry());head=object(key,headFile);frameFile=path.resolve("2.enc");frame=object(key,frameFile);frame.put("id",first.id());}
    case "extra-name"->Files.writeString(path.resolve("invalid.enc"),"synthetic");case "gap"->Files.writeString(path.resolve("999.enc"),"synthetic");
    case "record-symlink"->{Files.move(frameFile,path.resolve("saved-record"));Files.createSymbolicLink(frameFile,path.resolve("saved-record"));}
    case "oversize"->Files.writeString(frameFile,"x".repeat(87501));default->throw new AssertionError();
   }
   if(defect.startsWith("frame-")||defect.equals("duplicate-id")){encrypted(key,frameFile,frame);head.put("hash",Secrets.hash(Files.readString(frameFile)));}
   encrypted(key,headFile,head);String before=Files.readString(headFile);assertThrows(IllegalStateException.class,journal::entries,defect);assertEquals(before,Files.readString(headFile),defect);
  }
 }
 @Test void conflictingEntriesInvalidCapacityAndNonemptyInitializationFailWithoutPublication() throws Exception {
  Secrets key=secrets();assertThrows(IllegalArgumentException.class,()->new EncryptedErasureJournal(directory.resolve("invalid"),key,0));
  assertThrows(IllegalArgumentException.class,()->new EncryptedErasureJournal.Entry(UUID.randomUUID(),null,"synthetic"));
  Path path=directory.resolve("valid");var journal=new EncryptedErasureJournal(path,key);UUID installation=journal.initialize(null);assertEquals(installation,journal.initialize(null));var first=entry();journal.append(first);
  String different="different synthetic recipe";assertThrows(IllegalStateException.class,()->journal.append(new EncryptedErasureJournal.Entry(first.id(),Secrets.hash(different),different)));assertEquals(List.of(first),journal.entries());
  Path occupied=directory.resolve("occupied");Files.createDirectory(occupied);Files.writeString(occupied.resolve("unrelated"),"synthetic");assertThrows(IllegalStateException.class,()->new EncryptedErasureJournal(occupied,key).initialize(null));assertFalse(Files.exists(occupied.resolve("head.enc")));
 }
}
