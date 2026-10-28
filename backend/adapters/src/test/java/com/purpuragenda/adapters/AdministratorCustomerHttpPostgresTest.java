package com.purpuragenda.adapters;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.adapters.in.rest.*;
import com.purpuragenda.adapters.out.postgres.*;
import com.purpuragenda.adapters.out.security.AdminCrypto;
import com.purpuragenda.application.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@Timeout(240)
@ResourceLock("postgres-migration-fixture")
class AdministratorCustomerHttpPostgresTest {
    static final JsonMapper JSON=JsonMapper.builder().build();static final String PATH="/api/v1/admin/customers";
    @TempDir Path directory;
    AdministratorBookingHttpPostgresTest reads;AdministratorLoginPostgresTest login;AdminMfaDatabaseTest f;MockMvc mvc;UUID booking,customer;String email;
    @BeforeAll static void start(){AdminMfaDatabaseTest.start();}
    @AfterAll static void stop(){AdminMfaDatabaseTest.stop();}
    @BeforeEach void setup() throws Exception {
        reads=new AdministratorBookingHttpPostgresTest();reads.directory=directory;reads.setup();login=reads.login;f=reads.f;booking=reads.book("CONFIRMED");
        customer=f.jdbc.queryForObject("SELECT customer_id FROM booking WHERE id=?",UUID.class,booking);email=f.jdbc.queryForObject("SELECT email_key FROM customer_identity WHERE id=?",String.class,customer);
        mvc=MockMvcBuilders.standaloneSetup(new SessionController(login.access,login.cookies),new AdministratorLoginController(new AdministratorLogin(new RequestThrottle(login.limits),f.mfa,login.sessions,new AdminCrypto()),login.cookies,Duration.ZERO),
                new AdministratorCustomerController(new PostgresAdministratorCustomers(f.source),login.access,login.cookies))
                .addFilters(new ApiGuardFilter(AdministratorLoginPostgresTest.ORIGIN,login.access,login.cookies,new ClientAddress(List.of()))).build();login.mvc=mvc;reads.mvc=mvc;
    }
    @AfterEach void cleanup(){reads.cleanup();}
    MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception{return mvc.perform(request).andReturn().getResponse();}
    String bookings(){return PATH+"/"+customer+"/bookings";}
    @Test void actualMfaSearchAndHistoryExposeOnlyMinimalFieldsWithoutTouchingSessionOrBooking() throws Exception {
        var admin=reads.administrator();String seen=f.jdbc.queryForObject("SELECT last_seen_at::text FROM app_session WHERE id=?",String.class,admin.reference().id());long version=f.jdbc.queryForObject("SELECT version FROM booking WHERE id=?",Long.class,booking);int events=f.count("outbox_event");
        var response=send(reads.privateGet(admin,PATH).param("query",email).header("If-None-Match","*"));assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(response.getHeader("ETag"));var body=JSON.readTree(response.getContentAsString());assertEquals(1,body.get("entries").size());assertEquals(customer.toString(),body.at("/entries/0/id").asText());assertEquals(3,body.at("/entries/0").size());
        var history=send(reads.privateGet(admin,bookings()));assertEquals(200,history.getStatus());assertEquals("no-store",history.getHeader("Cache-Control"));var page=JSON.readTree(history.getContentAsString());assertEquals(booking.toString(),page.at("/bookings/0/id").asText());assertEquals(9,page.at("/bookings/0").size());assertEquals(email,page.at("/customer/email").asText());
        assertEquals(seen,f.jdbc.queryForObject("SELECT last_seen_at::text FROM app_session WHERE id=?",String.class,admin.reference().id()));assertEquals(version,f.jdbc.queryForObject("SELECT version FROM booking WHERE id=?",Long.class,booking));assertEquals(events,f.count("outbox_event"));
    }
    @Test void staleFactorCanReadButRevokedSessionCannotSearchOrInspectKnownCustomer() throws Exception {
        var admin=reads.administrator();f.jdbc.update("UPDATE app_session SET issued_at=issued_at-interval '602 seconds',absolute_expires_at=absolute_expires_at-interval '602 seconds',authenticated_at=issued_at-interval '601 seconds' WHERE id=?",admin.reference().id());
        assertEquals(200,send(reads.privateGet(admin,PATH).param("query",email)).getStatus());assertEquals(200,send(reads.privateGet(admin,bookings())).getStatus());login.sessions.revoke(admin.reference());
        login.problem(send(reads.privateGet(admin,PATH).param("query",email)),401,"SESSION_REQUIRED");login.problem(send(reads.privateGet(admin,bookings())),401,"SESSION_REQUIRED");
    }
    @Test void roleAndMissingCustomerFailuresAreSafeAndMutationMethodsAreUnavailable() throws Exception {
        login.problem(send(get(PATH).param("query",email)),401,"SESSION_REQUIRED");var anonymous=login.boot();login.problem(send(reads.privateGet(anonymous,PATH).param("query",email)),403,"ADMIN_ACCESS_REQUIRED");login.problem(send(reads.privateGet(anonymous,bookings())),403,"ADMIN_ACCESS_REQUIRED");
        var admin=reads.administrator();login.problem(send(reads.privateGet(admin,PATH+"/"+UUID.randomUUID()+"/bookings")),404,"CUSTOMER_NOT_FOUND");assertEquals(405,send(login.json(admin,PATH,"{}")).getStatus());
    }
    @Test void queryValidationAndLiteralMatchingCannotBroadenSearchOrOwnership() throws Exception {
        var admin=reads.administrator();for(String term:List.of("%_","' OR 1=1 --")){var response=send(reads.privateGet(admin,PATH).param("query",term));assertEquals(200,response.getStatus());assertTrue(JSON.readTree(response.getContentAsString()).get("entries").isEmpty());}
        login.problem(send(reads.privateGet(admin,PATH)),400,"INVALID_REQUEST");login.problem(send(reads.privateGet(admin,PATH).param("query",email,email)),400,"INVALID_REQUEST");login.problem(send(reads.privateGet(admin,PATH).param("query",email).param("limit","101")),400,"INVALID_REQUEST");
        login.problem(send(reads.privateGet(admin,bookings()).param("customerId",UUID.randomUUID().toString())),400,"INVALID_REQUEST");login.problem(send(reads.privateGet(admin,bookings()).param("cursor","bad")),400,"INVALID_REQUEST");
    }
}
