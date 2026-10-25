package com.purpuragenda.acceptance.api;
import com.purpuragenda.acceptance.runtime.AcceptanceRuntime;
import com.purpuragenda.adapters.codec.Json;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class LoadJourney {
    static void run(AcceptanceRuntime stack)throws Exception{
        Path directory=Files.createTempDirectory("purpuragenda-load-"),credentials=directory.resolve("credentials.json");
        try{List<Map<String,Object>> fixtures=new ArrayList<>();for(int i=0;i<20;i++){
            var client=new AcceptanceRuntime.Client(stack.base);client.csrf();String email="load-"+i+"@example.test";assertEquals(202,client.post("/auth/account-enrollment",Map.of("email",email,"purpose","ACCOUNT")).status());String link=stack.emailLink(email);assertEquals(200,client.post("/auth/email/redemptions",Map.of("token",link.split("token=")[1])).status());client.csrf();
            Map<String,Object> entry=new HashMap<>(client.loadCredential());List<Map<String,Object>> requests=new ArrayList<>();for(int hour:List.of(9,12,15))requests.add(stack.bookingBody(AcceptanceRuntime.KARMIC,stack.start(3+i,hour)));entry.put("requests",requests);String day=stack.start(3+i,9).atZone(com.purpuragenda.domain.scheduling.SchedulingPolicy.BUSINESS_ZONE).toLocalDate().toString();entry.put("query","serviceId="+AcceptanceRuntime.KARMIC+"&from="+day+"&to="+day+"&displayZone=Europe%2FBrussels");fixtures.add(entry);
        }
            Files.writeString(credentials,Json.write(fixtures));Files.setPosixFilePermissions(credentials,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            Path k6=Path.of(System.getenv().getOrDefault("K6_BIN",stack.root.resolve(".cache/test-tools/k6").toString()));assertTrue(Files.isExecutable(k6),"Install pinned k6");Path summary=Path.of("target/load-summary.json").toAbsolutePath();var command=new ProcessBuilder(k6.toString(),"run","--quiet",stack.root.resolve("tests/load/booking.js").toString());command.environment().putAll(Map.of("LOAD_CREDENTIALS",credentials.toString(),"BASE_URL",stack.base,"LOAD_SUMMARY",summary.toString()));Process process=command.redirectErrorStream(true).redirectOutput(Path.of("target/k6-output.txt").toFile()).start();assertTrue(process.waitFor(480,java.util.concurrent.TimeUnit.SECONDS),"Seven-minute load must finish within its bounded test deadline");assertEquals(0,process.exitValue(),"k6 quantitative thresholds must pass");assertTrue(Files.isRegularFile(summary));
            assertEquals(0,stack.jdbc.queryForObject("SELECT count(*) FROM booking_occupancy a JOIN booking_occupancy b ON a.booking_id<b.booking_id AND a.resource_id=b.resource_id AND a.occupied&&b.occupied WHERE a.released_at IS NULL AND b.released_at IS NULL",Integer.class));
            assertEquals(0,stack.jdbc.queryForObject("SELECT count(*) FROM customer_request_day d WHERE d.count<>(SELECT count(*) FROM request_charge c WHERE c.customer_id=d.customer_id AND c.submission_day=d.submission_day)",Integer.class));
            assertEquals(60,stack.jdbc.queryForObject("SELECT count(*) FROM request_charge c JOIN customer_identity i ON i.id=c.customer_id WHERE i.email_key LIKE 'load-%@example.test'",Integer.class));
        }finally{try(var paths=Files.walk(directory)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}}
    }
}
