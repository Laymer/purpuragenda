package com.purpuragenda.application;

import com.purpuragenda.application.scheduling.DueBookingExpiry;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DueBookingExpiryTest {
    @Test void maintenanceReusesCapturedTransactionExpiryWithoutReceiptsOrQuotas() {
        var tx=new ExpiryReclamationTest.Fake();var pending=tx.pending(Instant.parse("2026-01-02T08:00:00Z"));
        tx.now=pending.expiresAt();tx.due=List.of(pending);
        assertEquals(1,new DueBookingExpiry(work->work.applyAsInt(tx)).tick());
        assertEquals(1,tx.timeReads);assertEquals(List.of("save","effects"),tx.steps);
        tx.failEffects=true;
        assertEquals(Failure.Kind.STORAGE_FAILURE,assertThrows(Failure.class,()->new DueBookingExpiry(work->work.applyAsInt(tx)).tick()).kind());
        assertThrows(NullPointerException.class,()->new DueBookingExpiry(null));
    }
}
