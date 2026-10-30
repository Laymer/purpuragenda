package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.adapters.out.security.Secrets;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Write-ahead approved intents outside DB backups. No old personal values are journaled. */
public final class EncryptedErasureJournal {
    private final Path directory;private final Secrets secrets;private final long capacity;
    private record Head(UUID installation,long sequence,String hash){}
    private record Frame(UUID installation,long sequence,String previous,UUID id,String digest,String recipe){}
    public record Entry(UUID id,String digest,String recipe){public Entry{Objects.requireNonNull(id);Objects.requireNonNull(recipe);if(digest==null||!digest.equals(Secrets.hash(recipe)))throw new IllegalArgumentException("INVALID_JOURNAL_ENTRY");}@Override public String toString(){return "ErasureJournalEntry[REDACTED]";}}
    public EncryptedErasureJournal(Path directory,Secrets secrets){this(directory,secrets,100000);}
    EncryptedErasureJournal(Path directory,Secrets secrets,long capacity){if(capacity<1)throw new IllegalArgumentException("INVALID_CAPACITY");this.capacity=capacity;this.directory=Objects.requireNonNull(directory).toAbsolutePath().normalize();this.secrets=Objects.requireNonNull(secrets);}
    private RuntimeException failed(){return new IllegalStateException("PRIVACY_JOURNAL_UNAVAILABLE");}
    @FunctionalInterface private interface Work<T>{T run() throws java.io.IOException;}
    private synchronized <T>T locked(Work<T> work){
        try{
            for(Path p=directory;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw failed();
            Files.createDirectories(directory);Files.setPosixFilePermissions(directory,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            try(var channel=FileChannel.open(directory.resolve("journal.lock"),CREATE,WRITE,NOFOLLOW_LINKS);var lock=channel.lock()){return work.run();}
        }catch(Exception failure){throw failed();}
    }
    public UUID initialize(UUID expected){return initialize(expected,true);}
    public UUID initializeForRestore(UUID expected){return initialize(expected,false);}
    private UUID initialize(UUID expected,boolean allowCreate){return locked(()->{
        Path head=directory.resolve("head.enc");
        if(!Files.exists(head,NOFOLLOW_LINKS)){
            try(var files=Files.list(directory)){if(files.anyMatch(p->!p.getFileName().toString().equals("journal.lock")))throw failed();}
            if(expected!=null||!allowCreate)throw failed();
            UUID id=UUID.randomUUID();write(head,secrets.encrypt(PersistedJson.write(new Head(id,0,"0".repeat(64)))));return id;
        }
        Head value=head();if(expected!=null&&!expected.equals(value.installation()))throw failed();read(value);return value.installation();
    });}
    public List<Entry> entries(){return locked(()->read(head()));}
    public void append(Entry entry){Objects.requireNonNull(entry);locked(()->{
        Head head=head();List<Entry> entries=read(head);for(Entry existing:entries)if(existing.id().equals(entry.id())){if(!existing.equals(entry))throw failed();return null;}
        // A crash may have persisted the next authenticated record before advancing its head.
        head=head();if(head.sequence()>=capacity)throw failed();long next=head.sequence()+1;String encrypted=secrets.encrypt(PersistedJson.write(new Frame(head.installation(),next,head.hash(),entry.id(),entry.digest(),entry.recipe())));
        if(encrypted.getBytes(StandardCharsets.UTF_8).length>87500)throw failed();
        write(directory.resolve(next+".enc"),encrypted);write(directory.resolve("head.enc"),secrets.encrypt(PersistedJson.write(new Head(head.installation(),next,Secrets.hash(encrypted)))));return null;
    });}
    private Head head() throws java.io.IOException{return PersistedJson.read(secrets.decrypt(readFile(directory.resolve("head.enc"))),Head.class);}
    private List<Entry> read(Head head) throws java.io.IOException{
        if(head.installation()==null||head.hash()==null||!head.hash().matches("[0-9a-f]{64}")||head.sequence()<0||head.sequence()>capacity||(head.sequence()==0&&!head.hash().equals("0".repeat(64))))throw failed();var entries=new ArrayList<Entry>();String previous="0".repeat(64);long sequence=0;
        while(sequence<Long.MAX_VALUE&&Files.exists(directory.resolve((sequence+1)+".enc"),NOFOLLOW_LINKS)){
            sequence++;if(sequence>capacity)throw failed();String encrypted=readFile(directory.resolve(sequence+".enc"));Frame frame=PersistedJson.read(secrets.decrypt(encrypted),Frame.class);
            if(!frame.installation().equals(head.installation())||frame.sequence()!=sequence||!frame.previous().equals(previous)||!Secrets.hash(frame.recipe()).equals(frame.digest())||entries.stream().anyMatch(e->e.id().equals(frame.id())))throw failed();
            previous=Secrets.hash(encrypted);entries.add(new Entry(frame.id(),frame.digest(),frame.recipe()));
            if(sequence==head.sequence()&&!previous.equals(head.hash()))throw failed();
        }
        if(sequence<head.sequence())throw failed();
        final long found=sequence;
        try(var paths=Files.list(directory)){if(paths.anyMatch(p->{String name=p.getFileName().toString();return name.endsWith(".enc")&&!name.equals("head.enc")&&(!name.matches("[1-9][0-9]*\\.enc")||Long.parseLong(name.substring(0,name.length()-4))>found);}))throw failed();}
        if(sequence>head.sequence())write(directory.resolve("head.enc"),secrets.encrypt(PersistedJson.write(new Head(head.installation(),sequence,previous))));
        return List.copyOf(entries);
    }
    private String readFile(Path path) throws java.io.IOException {if(Files.isSymbolicLink(path)||Files.size(path)>87500)throw failed();return Files.readString(path,StandardCharsets.UTF_8);}
    static void writeFully(java.nio.channels.WritableByteChannel channel,java.nio.ByteBuffer buffer) throws java.io.IOException {while(buffer.hasRemaining())channel.write(buffer);}
    private void write(Path target,String encrypted) throws java.io.IOException{
        Path temporary=directory.resolve(UUID.randomUUID()+".tmp");
        try{
            try(var file=FileChannel.open(temporary,CREATE_NEW,WRITE,NOFOLLOW_LINKS)){writeFully(file,StandardCharsets.UTF_8.encode(encrypted));file.force(true);Files.setPosixFilePermissions(temporary,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            try(var parent=FileChannel.open(directory,READ)){parent.force(true);}
        }finally{Files.deleteIfExists(temporary);}
    }
}
