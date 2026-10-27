package com.purpuragenda.application.scheduling;

import com.purpuragenda.application.port.out.AdministratorBookingQuery;
import com.purpuragenda.application.port.out.AdministratorBookingQuery.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.purpuragenda.domain.DomainFailure.require;

/** Bounded Brussels agenda pages and private owner detail. HTTP must explicitly map records to wire documents. */
public final class AdministratorBookingView {
    private static final ZoneId BRUSSELS=ZoneId.of("Europe/Brussels");
    private final AdministratorBookingQuery query;
    public AdministratorBookingView(AdministratorBookingQuery query) { this.query=Objects.requireNonNull(query); }
    public Page agenda(SessionBinding session,LocalDate from,LocalDate through,Cursor after,int limit) {
        Objects.requireNonNull(session);Objects.requireNonNull(from);Objects.requireNonNull(through);
        long days=ChronoUnit.DAYS.between(from,through);
        require(days>=0 && days<31 && limit>=1 && limit<=100,"INVALID_AGENDA_RANGE");
        var window=new Window(from.atStartOfDay(BRUSSELS).toInstant(),through.plusDays(1).atStartOfDay(BRUSSELS).toInstant());
        require(after==null || !after.start().isBefore(window.from()) && after.start().isBefore(window.until()),"INVALID_AGENDA_CURSOR");
        return query.agenda(session,window,after,limit);
    }
    public Optional<Detail> detail(SessionBinding session,UUID bookingId) {
        return query.detail(Objects.requireNonNull(session),Objects.requireNonNull(bookingId));
    }
}
