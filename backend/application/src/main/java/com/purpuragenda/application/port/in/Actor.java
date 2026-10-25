package com.purpuragenda.application.port.in;

import static com.purpuragenda.domain.DomainFailure.require;
import java.util.UUID;

public record Actor(UUID id, Kind kind, UUID customerId, UUID bookingId) {
    public enum Kind { CREATION_GRANT, MANAGEMENT_GRANT, ACCOUNT, ADMINISTRATOR }
    public Actor { require(id != null && kind != null && (kind == Kind.ADMINISTRATOR || customerId != null), "UNAUTHENTICATED"); }
    public boolean admin() { return kind == Kind.ADMINISTRATOR; }
    public boolean canRead(UUID customer,UUID booking) { return admin() || (customer.equals(customerId) && (kind==Kind.ACCOUNT || (kind==Kind.MANAGEMENT_GRANT && booking.equals(bookingId)))); }
    public void requireAdmin() { require(admin(), "FORBIDDEN"); }
    public void requireCreate() { require(admin() || kind==Kind.CREATION_GRANT || kind==Kind.ACCOUNT, "FORBIDDEN"); }
}
