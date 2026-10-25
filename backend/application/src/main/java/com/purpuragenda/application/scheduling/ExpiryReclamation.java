package com.purpuragenda.application.scheduling;

import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Transaction;
import com.purpuragenda.domain.booking.Booking;
import java.time.Instant;
import java.util.Objects;

/** Call before the attempted mutation's savepoint, so expected conflicts preserve reclamation. */
public final class ExpiryReclamation {
    private ExpiryReclamation() {}
    public static int reclaim(Transaction transaction) {
        Objects.requireNonNull(transaction);
        Instant now=transaction.now();
        int count=0;
        for(Booking due:transaction.duePending()) {
            Booking expired=due.expire(now);
            transaction.save(expired);
            transaction.expiryEffects(expired.id(),expired.version());
            count++;
        }
        return count;
    }
}
