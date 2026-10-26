package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Receipt;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Transaction;
import com.purpuragenda.application.scheduling.CustomerQuota;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.scheduling.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Forwards the shared scheduling transaction so typed units add only their own authorized operations. */
abstract class TransactionDelegate implements Transaction {
    protected final Transaction tx;
    TransactionDelegate(Transaction tx) { this.tx=Objects.requireNonNull(tx); }
    public Instant now() { return tx.now(); }
    public List<ServiceDefinition> services() { return tx.services(); }
    public SchedulingPolicy policy() { return tx.policy(); }
    public AvailabilityCalendar calendar() { return tx.calendar(); }
    public Optional<Booking> booking(UUID id) { return tx.booking(id); }
    public List<Booking> bookings(ReservationInterval range) { return tx.bookings(range); }
    public void save(Booking value) { tx.save(value); }
    public List<Booking> duePending() { return tx.duePending(); }
    public void expiryEffects(UUID id,long version) { tx.expiryEffects(id,version); }
    public CustomerQuota quota(UUID customer) { return tx.quota(customer); }
    public boolean chargeCreated(Booking value) { return tx.chargeCreated(value); }
    public Receipt attempt(Supplier<Receipt> mutation) { return tx.attempt(mutation); }
}
