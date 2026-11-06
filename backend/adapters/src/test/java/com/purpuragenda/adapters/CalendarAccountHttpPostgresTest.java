package com.purpuragenda.adapters;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@Timeout(240) @org.junit.jupiter.api.parallel.ResourceLock("postgres-migration-fixture")
class CalendarAccountHttpPostgresTest {
    final AccountDataHttpPostgresTest h=new AccountDataHttpPostgresTest();@TempDir Path directory;
    @BeforeAll static void start(){AccountHttpPostgresTest.start();}
    @AfterAll static void stop(){AccountHttpPostgresTest.stop();}
    @BeforeEach void setup() throws Exception {h.directory=directory;h.setup();}
    @AfterEach void cleanup(){h.cleanup();}
    @Test void realAccountLoginOwnsOnlyItsBookingsAndNeverTheOwnerAudience() throws Exception {
        var browser=h.h.login();var c=h.h.a.e.c;var session=c.sessions.inspect(browser.cookie().toCharArray()).orElseThrow();
        var binding=new SessionBinding(session.reference().id(),session.reference().browserHash());
        UUID id=c.command.create(binding,"calendar-account",c.request(3,9)).entityId();
        var rival=c.account("calendar-rival@example.test");UUID other=c.command.create(rival.binding(),"other",c.request(4,9)).entityId();
        var cookie=new Cookie(h.h.cookies.name(),browser.cookie());
        assertTrue(CalendarCustomerHttpPostgresTest.calendar(h.send(get(CalendarCustomerHttpPostgresTest.path(id.toString())).cookie(cookie))).contains(id+"-customer@purpuragenda"));
        assertEquals(404,h.send(get(CalendarCustomerHttpPostgresTest.path(other.toString())).cookie(cookie)).getStatus());
        assertEquals(403,h.send(get("/api/v1/admin/bookings/"+id+"/calendar").cookie(cookie)).getStatus());
        h.h.a.epoch();assertEquals(401,h.send(get(CalendarCustomerHttpPostgresTest.path(id.toString())).cookie(cookie)).getStatus());
    }
}
