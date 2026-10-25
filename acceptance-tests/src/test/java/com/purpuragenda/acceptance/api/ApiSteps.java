package com.purpuragenda.acceptance.api;

import com.purpuragenda.acceptance.runtime.AcceptanceRuntime;
import com.purpuragenda.acceptance.runtime.AcceptanceRuntime.*;
import io.cucumber.java.*;
import io.cucumber.java.en.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

public class ApiSteps {
    static AcceptanceRuntime stack;
    Client guest;String bookingId,key;Map<String,Object> body;List<Response> race;int previousCharges;
    @BeforeAll public static void start()throws Exception{stack=new AcceptanceRuntime();try{stack.start();}catch(Exception|Error failure){stack.close();throw failure;}}
    @AfterAll public static void finish(){if(stack!=null)stack.close();}
    @Given("an isolated migrated PostgreSQL stack with two backends and captured mail") public void stack(){assertNotNull(stack.admin);}
    @When("a verified guest requests a reading and retries the same command") public void create()throws Exception{
        guest=stack.guest("guest-core@example.test");body=stack.bookingBody(AcceptanceRuntime.KARMIC,stack.start(3,15));key=UUID.randomUUID().toString();
        var created=guest.command("/bookings",body,key,null);assertEquals(201,created.status(),created.code());bookingId=created.json().get("bookingId").toString();
        var replay=guest.command("/bookings",body,key,null);assertEquals(201,replay.status(),replay.code());assertEquals(bookingId,replay.json().get("bookingId").toString());
        Map<String,Object> changed=new HashMap<>(body);changed.put("startInstant",stack.start(4,15).toString());assertEquals(409,guest.command("/bookings",changed,key,null).status());
    }
    @Then("one pending booking exists and unrelated guests cannot read it") public void pending()throws Exception{
        assertEquals("PENDING_CONFIRMATION",guest.get("/bookings/"+bookingId).json().get("status"));assertEquals("MANAGEMENT_GRANT",guest.get("/me").json().get("kind"));
        Client unrelated=stack.guest("guest-other@example.test");assertEquals(404,unrelated.get("/bookings/"+bookingId).status());
        assertEquals(403,guest.request("POST","/bookings/"+bookingId+"/cancellations",Map.of("reason",""),UUID.randomUUID().toString(),1L,false).status());
        assertEquals(1,stack.jdbc.queryForObject("SELECT count(*) FROM booking WHERE id=?::uuid",Integer.class,bookingId));
        assertEquals(0,stack.jdbc.queryForObject("SELECT count(*) FROM customer_account",Integer.class));
    }
    @When("the owner approves it using password and TOTP authentication") public void approve()throws Exception{
        assertEquals("ADMINISTRATOR",stack.admin.get("/me").json().get("kind"));
        var rejected=stack.admin.command("/admin/bookings/"+bookingId+"/approvals",Map.of("participantUrl","https://evil.example/j/12345678901"),UUID.randomUUID().toString(),1L);assertEquals(400,rejected.status());
        var approved=stack.admin.command("/admin/bookings/"+bookingId+"/approvals",Map.of("participantUrl","https://zoom.us/j/12345678901?pwd=synthetic"),UUID.randomUUID().toString(),1L);assertEquals(200,approved.status(),approved.code());
    }
    @Then("it is confirmed and unpaid with a private actual-session calendar") public void confirmed()throws Exception{
        var b=guest.get("/bookings/"+bookingId).json();assertEquals("CONFIRMED",b.get("status"));assertEquals("UNPAID",((Map<?,?>)b.get("payment")).get("status"));
        String ics=guest.get("/bookings/"+bookingId+"/calendar").text();assertTrue(ics.contains("DTSTART:"));assertTrue(ics.contains("DTEND:"));assertFalse(ics.contains("PRIVATE_"));assertFalse(ics.contains("token="));assertFalse(ics.contains("guest-core"));
        AcceptanceRuntime.await(()->stack.jdbc.queryForObject("SELECT count(*) FROM notification_job WHERE booking_id=?::uuid AND template='CONFIRMED' AND state='SENT'",Integer.class,bookingId)==2,Duration.ofSeconds(15),"Separate owner and customer confirmations must be delivered");
    }
    @When("a verified external receipt and eligible cancellation are recorded") public void cancelPaid()throws Exception{
        var payment=stack.admin.command("/admin/bookings/"+bookingId+"/payments",Map.of("amountMinor",1500,"currency","EUR","method","BANK","reference","CORE-RECEIPT","verifiedAt",Instant.now().toString(),"confirm",false),UUID.randomUUID().toString(),2L);assertEquals(200,payment.status(),payment.code());
        var cancelled=guest.command("/bookings/"+bookingId+"/cancellations",Map.of("reason",""),UUID.randomUUID().toString(),2L);assertEquals(200,cancelled.status(),cancelled.code());
    }
    @Then("the appointment is cancelled and the money requires refund follow-up") public void refundDue()throws Exception{
        var b=guest.get("/bookings/"+bookingId).json();assertEquals("CANCELLED",b.get("status"));assertEquals("REFUND_DUE",((Map<?,?>)b.get("payment")).get("status"));assertTrue(guest.get("/bookings/"+bookingId+"/calendar").text().contains("STATUS:CANCELLED"));
        assertEquals(0,stack.jdbc.queryForObject("SELECT count(*) FROM booking_occupancy WHERE booking_id=?::uuid AND released_at IS NULL",Integer.class,bookingId));assertEquals(1,stack.jdbc.queryForObject("SELECT count(*) FROM request_charge WHERE booking_id=?::uuid",Integer.class,bookingId));
    }
    @When("twenty verified customers request the same time together") public void compete()throws Exception{
        List<Client> clients=new ArrayList<>();for(int i=0;i<20;i++)clients.add(stack.guest("race-"+i+"@example.test"));previousCharges=stack.jdbc.queryForObject("SELECT count(*) FROM request_charge",Integer.class);
        try(var pool=Executors.newFixedThreadPool(20)){var barrier=new CyclicBarrier(20);List<Future<Response>> tasks=new ArrayList<>();for(int i=0;i<20;i++){int index=i;tasks.add(pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return clients.get(index).command("/bookings",stack.bookingBody(index%2==0?AcceptanceRuntime.KARMIC:AcceptanceRuntime.TRANSIT,stack.start(6,15)),UUID.randomUUID().toString(),null);}));}race=new ArrayList<>();for(var task:tasks)race.add(task.get(30,TimeUnit.SECONDS));}
    }
    @Then("exactly one request succeeds with one occupancy and submission charge") public void oneWinner(){
        assertEquals(1,race.stream().filter(r->r.status()==201).count());assertEquals(19,race.stream().filter(r->r.status()==409).count());assertEquals(previousCharges+1,stack.jdbc.queryForObject("SELECT count(*) FROM request_charge",Integer.class));assertEquals(1,stack.jdbc.queryForObject("SELECT count(*) FROM booking_occupancy WHERE occupied @> ?::timestamptz AND released_at IS NULL",Integer.class,stack.start(6,15).toString()));
    }
    @When("a guest requests and the owner approves using the selected browser and viewport") public void browser()throws Exception{BrowserJourney.run(stack);}
    @Then("the browser journey has no horizontal overflow or serious accessibility violations") public void browserPassed(){assertTrue(BrowserJourney.completed);}
    @When("nine cold mobile browser runs are measured under the specified lab limits") public void performance()throws Exception{PerformanceJourney.run(stack);}
    @Then("the browser performance budgets pass") public void performancePassed(){assertTrue(java.nio.file.Files.isRegularFile(java.nio.file.Path.of("target/performance-summary.json")));}
    @When("twenty virtual users run two minutes of warm-up and five measured minutes") public void load()throws Exception{LoadJourney.run(stack);}
    @Then("the HTTP budgets and database integrity checks pass") public void loadPassed(){assertTrue(java.nio.file.Files.isRegularFile(java.nio.file.Path.of("target/load-summary.json")));}
}
