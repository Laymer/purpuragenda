package com.purpuragenda.application.scheduling;

import com.purpuragenda.application.port.out.GuestBookingQuery;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import java.util.Objects;
import java.util.Optional;

/** The target follows persisted guest scope, not a browser-supplied identifier. */
public final class GuestBookingView {
    private final GuestBookingQuery query;
    public GuestBookingView(GuestBookingQuery query) { this.query=Objects.requireNonNull(query); }
    public Optional<CustomerBookingView.View> find(SessionBinding session) {
        return query.find(Objects.requireNonNull(session)).map(CustomerBookingView::project);
    }
}
