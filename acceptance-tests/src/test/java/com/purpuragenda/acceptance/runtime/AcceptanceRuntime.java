package com.purpuragenda.acceptance.runtime;

import com.purpuragenda.adapters.codec.Json;
import com.purpuragenda.adapters.out.security.*;
import com.purpuragenda.domain.scheduling.SchedulingPolicy;
import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import liquibase.integration.spring.SpringLiquibase;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** Test artifact only. Starts two real production backend processes and never exposes fixture HTTP routes. */
public final class AcceptanceRuntime implements AutoCloseable {
    public final Path root=Path.of("..").toAbsolutePath().normalize();
    public JdbcTemplate jdbc;public String base,mail;public Client admin;public final String adminEmail="owner@example.test",adminPassword=Secrets.token()+"synthetic";
    private PostgreSQLContainer container;private final List<Process> processes=new ArrayList<>();private HttpServer proxy;private ExecutorService proxyPool;private String schema;private Secrets secrets;private UUID adminId;private Path temporary;
    public static final String KARMIC="10000000-0000-0000-0000-000000000001",TRANSIT="10000000-0000-0000-0000-000000000002";
    public void start()throws Exception{
        String url=System.getenv("PG_TEST_URL"),user=System.getenv().getOrDefault("PG_TEST_USER","postgres"),password=System.getenv().getOrDefault("PG_TEST_PASSWORD","");
        if(url==null){container=new PostgreSQLContainer("postgres:18.6-bookworm@sha256:bbf83594798e144bff365a9c191c5d779f181fb3135902064ab8ff11918020fc").withDatabaseName("purpuragenda_test");container.start();url=container.getJdbcUrl();user=container.getUsername();password=container.getPassword();}
        assertTrue(url.matches("jdbc:postgresql://[^/]+/purpuragenda_test(?:\\?.*)?"),"Acceptance uses only purpuragenda_test");
        schema="acceptance_"+UUID.randomUUID().toString().replace("-","");var initial=new JdbcTemplate(new DriverManagerDataSource(url,user,password));initial.execute("CREATE EXTENSION IF NOT EXISTS btree_gist");initial.execute("CREATE SCHEMA "+schema);
        url+=(url.contains("?")?"&":"?")+"currentSchema="+schema+",public";
        var source=new DriverManagerDataSource(url,user,password);jdbc=new JdbcTemplate(source);var migrations=new SpringLiquibase();migrations.setDataSource(source);migrations.setDefaultSchema(schema);migrations.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");migrations.afterPropertiesSet();
        temporary=Files.createTempDirectory("purpuragenda-acceptance-");Path key=temporary.resolve("key");Files.write(key,java.security.SecureRandom.getSeed(32));secrets=new Secrets(key,"acceptance");
        int web=port(),smtp=port(),mailPort=port(),first=port(),second=port();base="http://127.0.0.1:"+web;mail="http://127.0.0.1:"+mailPort;
        Path mailpit=Path.of(System.getenv().getOrDefault("MAILPIT_BIN",root.resolve(".cache/mailpit/mailpit").toString()));assertTrue(Files.isExecutable(mailpit),"Install the checksum-pinned Mailpit test binary with scripts/dev/install-test-tools.py");
        processes.add(new ProcessBuilder(mailpit.toString(),"--listen","127.0.0.1:"+mailPort,"--smtp","127.0.0.1:"+smtp).redirectOutput(temporary.resolve("mailpit.log").toFile()).redirectErrorStream(true).start());
        var identities=new PostgresIdentity(jdbc,new DataSourceTransactionManager(source),secrets,base);adminId=identities.bootstrap(adminEmail,adminPassword.toCharArray());identities.completeEnrollment(adminId,totp(-1));identities.recoveryCodes(adminId);
        Path jar=root.resolve("backend/bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar");assertTrue(Files.isRegularFile(jar),"Build backend first");
        for(int backend:List.of(first,second)){
            var command=new ArrayList<String>();command.add(Path.of(System.getProperty("java.home"),"bin/java").toString());command.add("-Xmx256m");
            Path agent=root.resolve(".cache/m2/org/jacoco/org.jacoco.agent/0.8.15/org.jacoco.agent-0.8.15-runtime.jar");if(Files.isRegularFile(agent))command.add("-javaagent:"+agent+"=destfile="+root.resolve("backend/bootstrap/target/acceptance-"+backend+".exec"));
            command.addAll(List.of("-jar",jar.toString()));var builder=new ProcessBuilder(command);builder.directory(root.toFile());var env=builder.environment();env.putAll(Map.of("DATABASE_URL",url,"DATABASE_USER",user,"DATABASE_PASSWORD",password,"SERVER_PORT",Integer.toString(backend),"APP_MODE","test","APP_KEY_FILE",key.toString(),"APP_KEY_ID","acceptance","PUBLIC_BASE_URL",base,"SMTP_PORT",Integer.toString(smtp),"DELIVERY_MODE","CAPTURE"));
            processes.add(builder.redirectOutput(temporary.resolve("backend-"+backend+".log").toFile()).redirectErrorStream(true).start());
        }
        HttpClient forwarding=HttpClient.newBuilder().build();var round=new java.util.concurrent.atomic.AtomicInteger();Path frontend=root.resolve("frontend/dist/purpuragenda/browser");assertTrue(Files.isRegularFile(frontend.resolve("index.html")),"Build frontend first");
        proxy=HttpServer.create(new InetSocketAddress("127.0.0.1",web),0);proxyPool=Executors.newCachedThreadPool();proxy.setExecutor(proxyPool);proxy.createContext("/",exchange->{try{
            String path=exchange.getRequestURI().getPath();byte[] data;int status=200;
            if(path.startsWith("/api/")){
                int target=round.getAndIncrement()%2==0?first:second;var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+target+exchange.getRequestURI())).method(exchange.getRequestMethod(),HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes()));
                exchange.getRequestHeaders().forEach((name,values)->{if(!Set.of("host","connection","content-length","upgrade","http2-settings").contains(name.toLowerCase(Locale.ROOT)))for(String value:values)request.header(name,value);});
                var response=forwarding.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());status=response.statusCode();data=response.body();response.headers().map().forEach((name,values)->{if(!Set.of("connection","content-length","transfer-encoding").contains(name.toLowerCase(Locale.ROOT)))exchange.getResponseHeaders().put(name,values);});
            }else{
                Path file=frontend.resolve(path.substring(1)).normalize();if(!file.startsWith(frontend)){exchange.sendResponseHeaders(404,-1);exchange.close();return;}if(!Files.isRegularFile(file))file=frontend.resolve("index.html");data=Files.readAllBytes(file);exchange.getResponseHeaders().set("Content-Type",file.toString().endsWith(".js")?"text/javascript":file.toString().endsWith(".css")?"text/css":"text/html; charset=UTF-8");exchange.getResponseHeaders().set("Referrer-Policy","no-referrer");
            }
            exchange.sendResponseHeaders(status,data.length==0?-1:data.length);if(data.length>0)exchange.getResponseBody().write(data);
        }catch(Exception failure){exchange.sendResponseHeaders(502,-1);}finally{exchange.close();}});proxy.start();
        await(()->healthy(first)&&healthy(second),Duration.ofSeconds(30),"Both backend processes must become healthy");
        admin=new Client(base);admin.csrf();assertEquals(200,admin.post("/admin/auth/password",Map.of("identifier",adminEmail,"password",adminPassword)).status());assertEquals(200,admin.post("/admin/auth/totp",Map.of("code",totp(0))).status());admin.csrf();
    }
    public Instant start(int day,int hour){return Instant.now().atZone(SchedulingPolicy.BUSINESS_ZONE).toLocalDate().plusDays(day).atTime(hour,0).atZone(SchedulingPolicy.BUSINESS_ZONE).toInstant();}
    public Map<String,Object> bookingBody(String service,Instant when){return Map.of("serviceId",service,"serviceVersion",1,"policyVersion",1,"startInstant",when.toString(),"displayZone","Europe/Brussels","privacyNoticeVersion","local-draft","intake",Map.of("firstName","Alex","lastName","Synthetic","telephone","+32400123456","birthDate","1990-01-01","birthTime","10:30:15","birthplace","PRIVATE_BIRTHPLACE","message","PRIVATE_MESSAGE"));}
    public Client guest(String email)throws Exception{Client client=new Client(base);client.csrf();assertEquals(202,client.post("/auth/email/challenges",Map.of("email",email,"purpose","CREATE")).status());String link=emailLink(email);assertEquals(200,client.post("/auth/email/redemptions",Map.of("token",link.split("token=")[1])).status());client.csrf();return client;}
    public String emailLink(String email)throws Exception{
        final String[] found={null};await(()->{try{Map<?,?> messages=Json.read(HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(mail+"/api/v1/messages")).GET().build(),HttpResponse.BodyHandlers.ofString()).body(),Map.class);for(Object value:(List<?>)messages.get("messages")){Map<?,?> message=(Map<?,?>)value;boolean target=((List<?>)message.get("To")).stream().map(v->(Map<?,?>)v).anyMatch(v->email.equals(v.get("Address")));if(!target)continue;Map<?,?> detail=Json.read(HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(mail+"/api/v1/message/"+message.get("ID"))).GET().build(),HttpResponse.BodyHandlers.ofString()).body(),Map.class);Matcher matcher=Pattern.compile("https?://[^\\s]+/auth#token=[A-Za-z0-9_-]{43}").matcher(detail.get("Text").toString());if(matcher.find()){found[0]=matcher.group();return true;}}return false;}catch(Exception e){return false;}},Duration.ofSeconds(25),"Expected a captured verification email");return found[0];
    }
    public String totp(int offset)throws Exception{String encrypted=jdbc.queryForObject("SELECT totp_secret FROM admin_user WHERE id=?",String.class,adminId);var key=new SecretKeySpec(Base64.getDecoder().decode(secrets.decrypt(encrypted)),"HmacSHA1");long step=Instant.now().getEpochSecond()/30+offset;return String.format(Locale.ROOT,"%06d",new TimeBasedOneTimePasswordGenerator().generateOneTimePassword(key,Instant.ofEpochSecond(step*30)));}
    private static boolean healthy(int port){try{return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health")).timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200;}catch(Exception e){return false;}}
    public static void await(Callable<Boolean> condition,Duration limit,String message)throws Exception{long end=System.nanoTime()+limit.toNanos();while(System.nanoTime()<end){if(condition.call())return;Thread.sleep(100);}fail(message);}
    private static int port()throws Exception{try(var socket=new java.net.ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))){return socket.getLocalPort();}}
    @Override public void close(){if(proxy!=null)proxy.stop(0);if(proxyPool!=null)proxyPool.shutdownNow();for(Process p:processes){p.destroy();try{if(!p.waitFor(10,TimeUnit.SECONDS))p.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();}}if(jdbc!=null&&schema!=null)jdbc.execute("DROP SCHEMA "+schema+" CASCADE");if(container!=null)container.stop();if(temporary!=null)try(var paths=Files.walk(temporary)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}catch(Exception ignored){}}
    public record Response(int status,String text){public Map<?,?> json(){return Json.read(text,Map.class);}public String code(){return Objects.toString(json().get("code"),"");}}
    public static final class Client {
        private final HttpClient http;private final String base;private String csrf;private final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        public Client(String base){this.base=base;http=HttpClient.newBuilder().cookieHandler(cookies).build();}
        public Map<String,String> loadCredential(){return Map.of("cookie",cookies.getCookieStore().getCookies().stream().map(c->c.getName()+"="+c.getValue()).collect(java.util.stream.Collectors.joining("; ")),"csrf",csrf);}
        public void csrf()throws Exception{csrf=get("/security/csrf").json().get("token").toString();}
        public Response get(String path)throws Exception{return request("GET",path,null,null,null,true);}
        public Response post(String path,Object body)throws Exception{return request("POST",path,body,null,null,true);}
        public Response command(String path,Object body,String key,Long version)throws Exception{return request("POST",path,body,key,version,true);}
        public Response request(String method,String path,Object body,String key,Long version,boolean protectedRequest)throws Exception{
            var builder=HttpRequest.newBuilder(URI.create(base+"/api/v1"+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");if(protectedRequest&&csrf!=null)builder.header("X-CSRF-TOKEN",csrf);if(key!=null)builder.header("Idempotency-Key",key);if(version!=null)builder.header("If-Match","\""+version+"\"");builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(Json.write(body)));var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());return new Response(response.statusCode(),response.body());
        }
    }
}
