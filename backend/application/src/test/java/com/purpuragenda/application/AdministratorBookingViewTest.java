package com.purpuragenda.application;

import com.purpuragenda.application.port.out.AdministratorBookingQuery;
import com.purpuragenda.application.port.out.AdministratorBookingQuery.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.scheduling.AdministratorBookingView;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.payment.PaymentLedger;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdministratorBookingViewTest {
    final SessionBinding session=new SessionBinding(UUID.randomUUID(),"a".repeat(64));
    Window requested;Cursor cursor;int requestedLimit;Optional<Detail> detail=Optional.empty();
    final Page page=new Page(List.of(),null,Instant.EPOCH);
    final AdministratorBookingView view=new AdministratorBookingView(new AdministratorBookingQuery() {
        public Page agenda(SessionBinding supplied,Window window,Cursor after,int limit) { assertSame(session,supplied);requested=window;cursor=after;requestedLimit=limit;return page; }
        public Optional<Detail> detail(SessionBinding supplied,UUID id) { assertSame(session,supplied);detail.ifPresent(value->assertEquals(value.booking().id(),id));return detail; }
    });
    @Test void inclusiveBrusselsDaysFollowSpringAndFallCivilBoundaries() {
        for(var date:List.of(LocalDate.of(2027,3,28),LocalDate.of(2027,10,31))) {
            assertSame(page,view.agenda(session,date,date,null,100));
            assertEquals(date.atStartOfDay(ZoneId.of("Europe/Brussels")).toInstant(),requested.from());
            assertEquals(date.plusDays(1).atStartOfDay(ZoneId.of("Europe/Brussels")).toInstant(),requested.until());
            assertEquals(date.getMonthValue()==3?23:25,Duration.between(requested.from(),requested.until()).toHours());
        }
        LocalDate month=LocalDate.of(2028,2,1);view.agenda(session,month,month.plusDays(30),null,1);assertEquals(31,Duration.between(requested.from(),requested.until()).toDays());
    }
    @Test void boundedRangeAndCursorRejectUnboundedOrOutOfWindowRequests() {
        LocalDate from=LocalDate.of(2027,1,31);Instant start=from.atStartOfDay(ZoneId.of("Europe/Brussels")).toInstant();
        var after=new Cursor(start,UUID.randomUUID());view.agenda(session,from,from.plusDays(6),after,20);assertSame(after,cursor);assertEquals(20,requestedLimit);
        assertThrows(DomainFailure.class,()->view.agenda(session,from,from.minusDays(1),null,20));
        assertThrows(DomainFailure.class,()->view.agenda(session,from,from.plusDays(31),null,20));
        for(int limit:new int[]{0,101})assertThrows(DomainFailure.class,()->view.agenda(session,from,from,null,limit));
        for(Instant invalid:List.of(start.minusNanos(1),start.plusSeconds(86400)))assertThrows(DomainFailure.class,()->view.agenda(session,from,from,new Cursor(invalid,UUID.randomUUID()),20));
    }
    @Test void detailRetainsImmutableBookingAndIndependentMoneyBehindRedactedContainer() {
        Booking booking=new CustomerBookingChangesTest.Fixture().current;
        detail=Optional.of(new Detail(booking,PaymentLedger.empty(),Instant.EPOCH));
        var found=view.detail(session,booking.id()).orElseThrow();assertSame(booking,found.booking());assertEquals(PaymentLedger.Status.UNPAID,found.ledger().summary(booking.snapshot().service().price()).status());
        assertEquals("AdministratorBookingDetail[REDACTED]",found.toString());detail=Optional.empty();assertTrue(view.detail(session,UUID.randomUUID()).isEmpty());
    }
    @Test void pageIsImmutableAndPrivateRecordsNeverDescribeTheirValues() {
        var entry=new Entry(UUID.randomUUID(),"synthetic-reference",1,Booking.State.CONFIRMED,Instant.EPOCH,Instant.EPOCH.plusSeconds(3600),"Synthetic service");
        var next=new Cursor(entry.start(),entry.id());var entries=new ArrayList<>(List.of(entry));var result=new Page(entries,next,Instant.EPOCH);entries.clear();
        assertEquals(1,result.entries().size());assertThrows(UnsupportedOperationException.class,()->result.entries().clear());
        assertEquals("AdministratorAgendaEntry[REDACTED]",entry.toString());assertEquals("AdministratorAgendaPage[REDACTED]",result.toString());assertEquals("AgendaCursor[REDACTED]",next.toString());assertEquals("AgendaWindow[REDACTED]",new Window(Instant.EPOCH,Instant.EPOCH.plusSeconds(1)).toString());
        assertThrows(IllegalArgumentException.class,()->new Window(Instant.EPOCH,Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,()->new Window(Instant.EPOCH,Instant.EPOCH.minusSeconds(1)));
    }
    @Test void absentRequiredArgumentsCannotReachQuery() {
        LocalDate date=LocalDate.of(2027,1,1);
        assertThrows(NullPointerException.class,()->new AdministratorBookingView(null));
        assertThrows(NullPointerException.class,()->view.agenda(null,date,date,null,1));assertThrows(NullPointerException.class,()->view.agenda(session,null,date,null,1));assertThrows(NullPointerException.class,()->view.agenda(session,date,null,null,1));
        assertThrows(NullPointerException.class,()->view.detail(null,UUID.randomUUID()));assertThrows(NullPointerException.class,()->view.detail(session,null));
        assertThrows(NullPointerException.class,()->new Cursor(null,UUID.randomUUID()));assertThrows(NullPointerException.class,()->new Cursor(Instant.EPOCH,null));
        assertThrows(NullPointerException.class,()->new Window(null,Instant.EPOCH));assertThrows(NullPointerException.class,()->new Window(Instant.EPOCH,null));
        assertThrows(NullPointerException.class,()->new Page(null,null,Instant.EPOCH));assertThrows(NullPointerException.class,()->new Page(List.of(),null,null));
        var fixture=new CustomerBookingChangesTest.Fixture();
        assertThrows(NullPointerException.class,()->new Detail(null,PaymentLedger.empty(),Instant.EPOCH));assertThrows(NullPointerException.class,()->new Detail(fixture.current,null,Instant.EPOCH));assertThrows(NullPointerException.class,()->new Detail(fixture.current,PaymentLedger.empty(),null));
    }
}
