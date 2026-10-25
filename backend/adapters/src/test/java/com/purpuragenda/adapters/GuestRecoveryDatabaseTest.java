package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.security.*;
import com.purpuragenda.application.GuestRecovery;
import com.purpuragenda.application.port.out.GuestAccessStore.*;
import com.purpuragenda.application.port.out.GuestRecoveryLookup.Request;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.postgresql.ds.PGSimpleDataSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(180)
@ResourceLock("postgres-migration-fixture")
class GuestRecoveryDatabaseTest {
    final GuestAccessDatabaseTest fixture=new GuestAccessDatabaseTest();
    @TempDir Path directory;
    PostgresGuestRecoveryLookup lookup;GuestRecovery recovery;
    @BeforeAll static void start() { MigrationTest.start(); }
    @AfterAll static void stop() { MigrationTest.stop(); }
    @BeforeEach void setup() throws Exception {
        fixture.directory=directory;fixture.setup();lookup=new PostgresGuestRecoveryLookup(fixture.source);
        recovery=new GuestRecovery(lookup,fixture.delivery);
    }
    @AfterEach void cleanup() { fixture.cleanup(); }
    UUID booking(String state,int offset) { return fixture.database.booking(MigrationTest.START.plusSeconds(offset*7200L),state,MigrationTest.snapshot()); }
    String reference(UUID booking) { return fixture.value("SELECT reference FROM booking WHERE id=?",booking); }
    Request request(UUID booking,String email) { return new Request(reference(booking),email); }
    String token() {
        var lease=fixture.queue.claimAuth().orElseThrow();var message=fixture.delivery.prepare(lease).orElseThrow();
        assertFalse(message.text().contains("browser that requested it"));assertTrue(fixture.queue.sent(lease));
        return message.text().split("#token=",2)[1].split("&",2)[0];
    }
    Optional<Grant> redeem(String token) { return fixture.access.redeem(new Redemption(token,Kind.MANAGE,GuestAccessDatabaseTest.BROWSER)); }

    @Test void exactReferenceAndNormalizedCurrentContactQueueOnlyOneManagementProof() {
        UUID first=booking("PENDING_CONFIRMATION",0);booking("PENDING_CONFIRMATION",1);
        assertDoesNotThrow(()->recovery.request(new Request("unknown-reference","synthetic@example.test")));
        assertDoesNotThrow(()->recovery.request(request(first,"wrong@example.test")));
        assertDoesNotThrow(()->recovery.request(request(first,"synthetic+other@example.test")));
        assertDoesNotThrow(()->recovery.request(new Request("' OR '1'='1","synthetic@example.test")));
        assertEquals(0,fixture.count("email_challenge"));assertEquals(0,fixture.count("notification_job"));
        recovery.request(new Request(" "+reference(first)+" "," SYNTHETIC@EXAMPLE.TEST "));
        assertEquals(1,fixture.count("email_challenge"));assertEquals(1,fixture.count("notification_job"));
        assertEquals("MANAGE",fixture.value("SELECT purpose FROM email_challenge"));
        assertTrue(first.toString().equals(fixture.value("SELECT booking_id FROM email_challenge")));
        assertNull(fixture.value("SELECT browser_hash FROM email_challenge"));
        assertEquals(0,fixture.count("access_grant"));assertEquals(0,fixture.count("customer_account"));
        assertEquals(2,fixture.count("customer_identity"));
    }
    @Test void changedDeliveryContactCanRecoverOnlyOriginalOwnersExactBooking() {
        UUID id=booking("PENDING_CONFIRMATION",0);String owner=fixture.value("SELECT customer_id FROM booking WHERE id=?",id);
        fixture.sql("UPDATE booking SET delivery_email='changed@example.test' WHERE id=?",id);
        recovery.request(request(id,"synthetic@example.test"));assertEquals(0,fixture.count("email_challenge"));
        recovery.request(request(id,"CHANGED@EXAMPLE.TEST"));String token=token();String browser=Secrets.hash("synthetic-different-recovery-browser");
        assertTrue(fixture.access.redeem(new Redemption(token,Kind.CREATE,browser)).isEmpty());
        var grant=fixture.access.redeem(new Redemption(token,Kind.MANAGE,browser)).orElseThrow();
        assertEquals(id,grant.bookingId());assertTrue(owner.equals(grant.customerId().toString()));assertEquals(Kind.MANAGE,grant.kind());
        assertEquals(grant.issuedAt().plusSeconds(7200),grant.expiresAt());assertTrue(redeem(token).isEmpty());
        assertTrue(browser.equals(fixture.value("SELECT browser_hash FROM access_grant WHERE id=?",grant.id())));
        assertEquals(1,fixture.count("customer_identity"));assertEquals(0,fixture.count("customer_account"));assertEquals(0,fixture.count("customer_profile"));
    }
    @Test void terminalBookingsRemainRecoverableForStatusWithoutReopeningOrChangingHistory() {
        int offset=0;
        for(String state:List.of("CONFIRMED","CANCELLED","REJECTED","EXPIRED","COMPLETED","NO_SHOW")) {
            UUID id=booking(state,offset++);recovery.request(request(id,"synthetic@example.test"));
            assertEquals(id,redeem(token()).orElseThrow().bookingId());
            assertEquals(state,fixture.value("SELECT state FROM booking WHERE id=?",id));
            assertEquals("1",fixture.value("SELECT version FROM booking WHERE id=?",id));
        }
        assertEquals(6,fixture.count("access_grant"));assertEquals(0,fixture.count("customer_account"));
    }
    @Test void contactChangeBetweenLookupAndIssueProducesOnlyAnInertOldContactLink() {
        UUID id=booking("PENDING_CONFIRMATION",0);
        var raced=new GuestRecovery(request->{
            var result=lookup.find(request);fixture.sql("UPDATE booking SET delivery_email='changed@example.test' WHERE id=?",id);return result;
        },fixture.delivery);
        raced.request(request(id,"synthetic@example.test"));assertEquals(1,fixture.count("email_challenge"));
        assertTrue(redeem(token()).isEmpty());assertEquals(0,fixture.count("access_grant"));
        assertNull(fixture.value("SELECT consumed_at FROM email_challenge"));assertEquals(1,fixture.count("customer_identity"));
    }
    @Test void failedQueueInsertRollsBackChallengeAndLeavesBookingAndAuthorityUntouched() {
        UUID id=booking("PENDING_CONFIRMATION",0);
        try(var c=fixture.database.connection(MigrationTest.MIGRATOR)) {
            MigrationTest.execute(c,"CREATE FUNCTION reject_recovery_job() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'SYNTHETIC_FAILURE'; END $$");
            MigrationTest.execute(c,"CREATE TRIGGER reject_recovery_job BEFORE INSERT ON notification_job FOR EACH ROW EXECUTE FUNCTION reject_recovery_job()");
        } catch(SQLException failure) { throw MigrationTest.safe(failure); }
        var failure=assertThrows(IllegalStateException.class,()->recovery.request(request(id,"synthetic@example.test")));
        assertEquals("AUTH_DELIVERY_STORAGE_FAILED",failure.getMessage());assertNull(failure.getCause());
        assertEquals(0,fixture.count("email_challenge"));assertEquals(0,fixture.count("notification_job"));assertEquals(0,fixture.count("access_grant"));
        assertEquals("1",fixture.value("SELECT version FROM booking WHERE id=?",id));
    }
    @Test void realQueryAndConnectionFailuresNeverExposeProviderDetailsOrReturnMatches() {
        var request=new Request("unknown-reference","synthetic@example.test");
        fixture.source.setCurrentSchema("public");safe(()->lookup.find(request));
        var unavailable=new PGSimpleDataSource() {
            @Override public Connection getConnection() throws SQLException { throw new SQLException("SYNTHETIC_PRIVATE_PROVIDER_ERROR"); }
        };
        safe(()->new PostgresGuestRecoveryLookup(unavailable).find(request));
        assertThrows(NullPointerException.class,()->new PostgresGuestRecoveryLookup(null));
        assertThrows(NullPointerException.class,()->lookup.find(null));assertEquals("GuestRecoveryLookup[REDACTED]",lookup.toString());
    }
    void safe(org.junit.jupiter.api.function.Executable action) {
        var failure=assertThrows(IllegalStateException.class,action);assertEquals("GUEST_RECOVERY_UNAVAILABLE",failure.getMessage());
        assertNull(failure.getCause());assertEquals(0,failure.getSuppressed().length);
    }
}
