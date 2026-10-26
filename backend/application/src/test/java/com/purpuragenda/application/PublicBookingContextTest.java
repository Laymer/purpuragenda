package com.purpuragenda.application;

import com.purpuragenda.application.scheduling.*;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.scheduling.SchedulingPolicy;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PublicBookingContextTest {
    final PublicSchedulingTest.Fixture fixture=new PublicSchedulingTest.Fixture();
    final BookingNotice notice=new BookingNotice("local-v1","Synthetic notice",true);
    final PublicBookingContext query=new PublicBookingContext(fixture,notice);
    @Test void oneReadCarriesActualServicePolicyAndNoticeWithoutReadingPrivateBookingData() {
        fixture.policy=SchedulingPolicy.defaults();
        var context=query.find(fixture.service.id());
        assertEquals(1,fixture.reads);assertNull(fixture.loaded);
        assertSame(fixture.service,context.service());assertSame(fixture.policy,context.policy());assertSame(notice,context.notice());
        assertEquals(fixture.now,context.serverNow());assertEquals(fixture.policy.horizon(fixture.now),context.horizon());
        fixture.policy=new SchedulingPolicy(2,15,5,Duration.ZERO,Duration.ofHours(12),Duration.ofMinutes(30),Duration.ofHours(48));
        fixture.services=List.of(new ServiceDefinition(fixture.service.id(),2,"Changed",30,new Money(2500,"EUR"),true));
        var changed=query.find(fixture.service.id());
        assertEquals(2,changed.service().version());assertEquals(2,changed.policy().version());
        assertEquals(Duration.ofHours(48),changed.policy().customerCutoff());
        assertEquals(1,context.service().version());assertEquals(1,context.policy().version());
    }
    @Test void horizonUsesBrusselsCivilMonthEndAndDstRatherThanElapsedThirtyDays() {
        for(String value:List.of("2026-01-31T10:00:00Z","2026-03-01T10:00:00Z","2026-10-01T10:00:00Z")) {
            fixture.now=Instant.parse(value);
            assertEquals(fixture.now.atZone(SchedulingPolicy.BUSINESS_ZONE).plusMonths(1).toInstant(),query.find(fixture.service.id()).horizon());
        }
    }
    @Test void absentAndArchivedServicesHaveTheSameSafeFailure() {
        for(UUID id:Arrays.asList(null,UUID.randomUUID()))assertEquals("SERVICE_UNAVAILABLE",assertThrows(DomainFailure.class,()->query.find(id)).code());
        fixture.services=List.of(new ServiceDefinition(fixture.service.id(),2,"Archived",30,new Money(1,"EUR"),false));
        assertEquals("SERVICE_UNAVAILABLE",assertThrows(DomainFailure.class,()->query.find(fixture.service.id())).code());
        assertThrows(NullPointerException.class,()->new PublicBookingContext(null,notice));
        assertThrows(NullPointerException.class,()->new PublicBookingContext(fixture,null));
    }
    @Test void noticeIsBoundedNormalizedPlaintextWithExplicitSyntheticClassification() {
        assertEquals("Café\ntext\tend",new BookingNotice("notice-2","  Cafe\u0301\ntext\tend  ",false).text());
        assertFalse(new BookingNotice("notice-2","text",false).synthetic());
        assertEquals(16000,new BookingNotice("v","x".repeat(16000),true).text().length());
        for(String version:List.of("","x y","x".repeat(81)))assertThrows(IllegalArgumentException.class,()->new BookingNotice(version,"text",true));
        for(String text:List.of(" ","x".repeat(16001),"x\u0000","x\u007f","x\r"))assertThrows(IllegalArgumentException.class,()->new BookingNotice("v",text,true));
        assertThrows(NullPointerException.class,()->new BookingNotice(null,"text",true));
        assertThrows(NullPointerException.class,()->new BookingNotice("v",null,true));
    }
}
