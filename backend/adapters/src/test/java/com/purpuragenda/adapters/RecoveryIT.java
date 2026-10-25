package com.purpuragenda.adapters;

import com.purpuragenda.adapters.codec.Json;
import com.purpuragenda.adapters.out.email.PostgresNotifications;
import com.purpuragenda.adapters.out.calendar.IcalendarWriter;
import com.purpuragenda.adapters.out.security.Secrets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryIT {
    @Test void encryptedFreshDatabaseRestoreRetainsIntegrityAndSuppressesHistoricalDelivery()throws Exception{
        var fixture=new SchedulingIT();fixture.prepare();Path temp=Files.createTempDirectory("purpuragenda-restore-");String database="purpuragenda_restore_test_"+UUID.randomUUID().toString().replace("-","");JdbcTemplate restored=null;long began=System.nanoTime();
        try{
            var customer=fixture.account();var created=fixture.create(customer,fixture.start(3,15));assertTrue(created.success());
            fixture.jdbc.update("INSERT INTO notification_job(id,booking_id,audience,template,version,due_at) VALUES(?,?,'CUSTOMER','REQUESTED',1,clock_timestamp())",UUID.randomUUID(),created.bookingId());
            fixture.jdbc.update("INSERT INTO access_grant(id,customer_id,kind,expires_at) VALUES(?,?,'ACCOUNT',clock_timestamp()+interval '12 hours')",UUID.randomUUID(),customer.customerId());
            fixture.jdbc.update("INSERT INTO email_challenge(id,token_hash,email_key,purpose,expires_at) VALUES(?,?,'synthetic@example.test','CREATE',clock_timestamp()+interval '15 minutes')",UUID.randomUUID(),Secrets.hash("synthetic-only"));
            Path backup=temp.resolve("backup");Files.createDirectory(backup);Path dump=backup.resolve("database.dump"),pass=temp.resolve("password");Files.writeString(pass,Secrets.token());
            String pgBin=System.getenv().getOrDefault("PG_BIN",Path.of("../../scripts/dev/pg-tools").toAbsolutePath().normalize().toString());
            String restic=System.getenv().getOrDefault("RESTIC_BIN",Path.of("../../.cache/test-tools/restic").toAbsolutePath().normalize().toString());
            URI jdbc=URI.create(fixture.source.getUrl().substring(5));int port=jdbc.getPort();Map<String,String> env=new HashMap<>();env.put("PGPASSWORD",Objects.requireNonNullElse(fixture.source.getPassword(),""));env.put("PG_TEST_SCRATCH",temp.toString());env.put("RESTIC_REPOSITORY",temp.resolve("encrypted-repository").toString());env.put("RESTIC_PASSWORD_FILE",pass.toString());
            Instant snapshot=Instant.now();
            run(List.of(Path.of(pgBin,"pg_dump").toString(),"-h",jdbc.getHost(),"-p",Integer.toString(port),"-U",fixture.source.getUsername(),"-d","purpuragenda_test","-n",fixture.schema,"-Fc","-f",dump.toString()),env,temp);
            run(List.of(restic,"init"),env,temp);run(List.of(restic,"backup","."),env,backup);run(List.of(restic,"check","--read-data"),env,temp);
            Path recovered=temp.resolve("recovered");run(List.of(restic,"restore","latest","--target",recovered.toString()),env,temp);
            Path recoveredDump;try(var files=Files.walk(recovered)){recoveredDump=files.filter(p->p.getFileName().toString().equals("database.dump")).findFirst().orElseThrow();}
            assertArrayEquals(Files.readAllBytes(dump),Files.readAllBytes(recoveredDump));
            fixture.jdbc.execute("CREATE DATABASE "+database);
            String target="jdbc:postgresql://"+jdbc.getHost()+":"+port+"/"+database;
            var targetSource=new DriverManagerDataSource(target,fixture.source.getUsername(),fixture.source.getPassword());new JdbcTemplate(targetSource).execute("CREATE EXTENSION btree_gist");
            run(List.of(Path.of(pgBin,"pg_restore").toString(),"-h",jdbc.getHost(),"-p",Integer.toString(port),"-U",fixture.source.getUsername(),"-d",database,"--no-owner","--exit-on-error",recoveredDump.toString()),env,temp);
            targetSource=new DriverManagerDataSource(target+"?currentSchema="+fixture.schema+",public",fixture.source.getUsername(),fixture.source.getPassword());restored=new JdbcTemplate(targetSource);
            restored.execute(Files.readString(Path.of("../../scripts/ops/restore-sanitize.sql")));
            assertEquals(1,restored.queryForObject("SELECT count(*) FROM booking",Integer.class));assertEquals(1,restored.queryForObject("SELECT count(*) FROM request_charge",Integer.class));
            assertEquals(1,restored.queryForObject("SELECT count(*) FROM booking_occupancy WHERE released_at IS NULL",Integer.class));assertEquals(0,restored.queryForObject("SELECT count(*) FROM access_grant WHERE revoked_at IS NULL",Integer.class));
            assertEquals(0,restored.queryForObject("SELECT count(*) FROM email_challenge WHERE consumed_at IS NULL",Integer.class));assertEquals(0,restored.queryForObject("SELECT count(*) FROM spring_session",Integer.class));
            assertEquals(0,restored.queryForObject("SELECT count(*) FROM notification_job WHERE state<>'SUPPRESSED'",Integer.class));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->new JdbcTemplate(new DriverManagerDataSource(target+"?currentSchema="+fixture.schema+",public",fixture.source.getUsername(),fixture.source.getPassword())).update("UPDATE booking_occupancy SET occupied=tstzrange(lower(occupied),upper(occupied)+interval '1 minute','[)')"));
            Path key=temp.resolve("key");Files.write(key,new byte[32]);var queue=new PostgresNotifications(restored,new DataSourceTransactionManager(targetSource),fixture.uow,new Secrets(key,"test"),new IcalendarWriter(),"http://localhost","owner@example.test","SUPPRESSED");assertTrue(queue.claim().isEmpty());
            double seconds=(System.nanoTime()-began)/1_000_000_000d;assertTrue(seconds<14400);Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/restore-receipt.json"),Json.write(Map.of("scope","isolated local encrypted restore; not an off-host SLA","elapsedSeconds",seconds,"snapshotAgeSeconds",Duration.between(snapshot,Instant.now()).toSeconds(),"recordsRetained",true,"occupancyConstraintVerified",true,"sessionsAndTokensRevoked",true,"historicalDeliverySuppressed",true)));
        }finally{
            fixture.jdbc.execute("DROP DATABASE IF EXISTS "+database+" WITH (FORCE)");fixture.cleanup();
            try(var paths=Files.walk(temp)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}
        }
    }
    private static void run(List<String> command,Map<String,String> env,Path directory)throws Exception{var builder=new ProcessBuilder(command).directory(directory.toFile());builder.environment().putAll(env);builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);builder.redirectError(ProcessBuilder.Redirect.DISCARD);Process process=builder.start();assertTrue(process.waitFor(120,java.util.concurrent.TimeUnit.SECONDS),"Recovery command must finish");assertEquals(0,process.exitValue(),"Recovery utility failed: "+Path.of(command.getFirst()).getFileName());}
}
