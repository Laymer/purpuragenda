package com.purpuragenda.application;

import com.purpuragenda.application.port.out.AccountDataStore;
import com.purpuragenda.application.port.out.AccountDataStore.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.*;
import java.time.Instant;
import java.util.*;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccountDataTest {
    final SessionBinding session=new SessionBinding(UUID.randomUUID(),"b".repeat(64));
    final UUID customer=UUID.randomUUID();final Instant now=Instant.parse("2026-11-01T10:00:00Z");
    Profile profile=Profile.empty();Command command;int writes,reads;Cursor cursor;int limit;
    final AccountDataStore store=new AccountDataStore() {
        public Snapshot profile(SessionBinding supplied) { assertSame(session,supplied);reads++;return new Snapshot("synthetic@example.test",profile,now); }
        public History history(SessionBinding supplied,Cursor before,int size) { assertSame(session,supplied);reads++;cursor=before;limit=size;return new History(List.of(),null,now); }
        public Receipt change(Command supplied,UnaryOperator<Profile> update) { command=supplied;profile=update.apply(profile);writes++;return new Receipt(Code.OK,customer,profile.version()); }
    };
    final AccountData app=new AccountData(store);
    @Test void optionalFieldsNormalizeWithoutAnyAutomaticBookingOrIdentityCopy() {
        assertEquals(new Fields("","",""),app.profile(session).profile().fields());assertEquals(0,writes);
        Fields fields=new Fields(" Synthe\u0301tic "," Customer "," +32 (400) 123-456 ");
        assertEquals(new Fields("Synthétic","Customer","+32400123456"),fields);
        assertEquals(2,app.update(session,"synthetic-key",1,fields).version());assertSame(session,command.session());assertEquals(fields,profile.fields());assertEquals(1,writes);
        String fingerprint=command.fingerprint();profile=Profile.empty();app.update(session,"another-key",1,new Fields("Synthétic","Customer","+32400123456"));assertEquals(fingerprint,command.fingerprint());
        profile=Profile.empty();app.update(session,"third-key",1,new Fields("Other","Customer",""));assertNotEquals(fingerprint,command.fingerprint());
    }
    @Test void staleProfilesAndInvalidVersionsCannotOverwriteCurrentData() {
        profile=new Profile(3,new Fields("Synthetic","Customer",""));
        assertEquals("STALE_VERSION",assertThrows(DomainFailure.class,()->app.update(session,"stale",2,new Fields("Other","",""))).code());assertEquals(0,writes);assertEquals(3,profile.version());
        for(long version:new long[]{0,-1,Long.MAX_VALUE})assertThrows(DomainFailure.class,()->app.update(session,"bad",version,new Fields("","","")));
        assertThrows(DomainFailure.class,()->app.update(session,"bad",1,null));
        profile=new Profile(Long.MAX_VALUE-1,new Fields("","",""));assertEquals(Long.MAX_VALUE,app.update(session,"last-safe",Long.MAX_VALUE-1,new Fields("","","")).version());
    }
    @Test void boundedHistoryDelegatesOnlyTheAuthenticatedSessionAndExplicitCursor() {
        var before=new Cursor(now,UUID.randomUUID());assertTrue(app.history(session,before,100).bookings().isEmpty());assertSame(before,cursor);assertEquals(100,limit);
        app.history(session,null,1);assertNull(cursor);assertEquals(1,limit);
        for(int size:new int[]{0,-1,101})assertThrows(DomainFailure.class,()->app.history(session,null,size));
        assertThrows(DomainFailure.class,()->new Cursor(now.plusNanos(1),UUID.randomUUID()));assertThrows(DomainFailure.class,()->new Cursor(null,UUID.randomUUID()));assertThrows(DomainFailure.class,()->new Cursor(now,null));
    }
    @Test void malformedFieldsAndCommandKeysFailBeforePersistence() {
        for(String name:new String[]{null,"x".repeat(101),"bad\u0000name"})assertThrows(DomainFailure.class,()->new Fields(name,"","") );
        assertThrows(DomainFailure.class,()->new Fields("","x".repeat(101),""));
        for(String phone:new String[]{null,"12345678","+0000000","x".repeat(41)})assertThrows(DomainFailure.class,()->new Fields("","",phone));
        for(String key:new String[]{null,""," ","x".repeat(121),"bad\nkey"})assertThrows(DomainFailure.class,()->app.update(session,key,1,new Fields("","","")));
        assertThrows(DomainFailure.class,()->new Command(session,"key","bad"));assertThrows(DomainFailure.class,()->new Profile(0,new Fields("","","")));assertThrows(DomainFailure.class,()->new Profile(1,null));assertEquals(0,writes);
    }
    @Test void historyAndProfileObjectsAreImmutableAndRedacted() {
        Fields fields=new Fields("Synthetic","Customer","+32400123456");var summary=new BookingSummary(UUID.randomUUID(),"B-synthetic",Booking.State.CONFIRMED,now,now.plusSeconds(3600),false,"Synthetic service",Money.eur(1500));
        var rows=new ArrayList<>(List.of(summary));var history=new History(rows,new Cursor(now,summary.id()),now);rows.clear();assertEquals(1,history.bookings().size());assertThrows(UnsupportedOperationException.class,()->history.bookings().clear());
        for(Object value:List.of(fields,new Profile(2,fields),new Snapshot("synthetic@example.test",profile,now),summary,history,history.next(),new Command(session,"private-key","a".repeat(64)))) {
            assertTrue(value.toString().contains("REDACTED"));assertFalse(value.toString().contains("Synthetic"));assertFalse(value.toString().contains("private-key"));
        }
        assertEquals("B-synthetic",summary.reference());assertEquals(Booking.State.CONFIRMED,summary.state());assertEquals(now,summary.start());assertEquals(now.plusSeconds(3600),summary.sessionEnd());assertFalse(summary.pendingExpired());assertEquals("Synthetic service",summary.serviceName());assertEquals(Money.eur(1500),summary.price());
        assertEquals(now,history.observedAt());assertEquals(now,history.next().start());assertEquals(summary.id(),history.next().id());
        var snapshot=app.profile(session);assertEquals("synthetic@example.test",snapshot.loginEmail());assertEquals(now,snapshot.observedAt());
    }
    @Test void missingDependenciesAndSessionsFailClosed() {
        assertThrows(NullPointerException.class,()->new AccountData(null));assertThrows(NullPointerException.class,()->app.profile(null));assertThrows(NullPointerException.class,()->app.history(null,null,1));assertThrows(NullPointerException.class,()->app.update(null,"key",1,new Fields("","","")));
    }
}
