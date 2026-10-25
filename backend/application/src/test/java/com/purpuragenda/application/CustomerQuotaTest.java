package com.purpuragenda.application;

import com.purpuragenda.application.scheduling.CustomerQuota;
import com.purpuragenda.domain.DomainFailure;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CustomerQuotaTest {
    @Test void submissionDayUsesBrusselsAcrossMidnightMonthEndAndBothDstOffsets() {
        for(String[] pair:new String[][]{
                {"2026-01-31T22:59:59.999999Z","2026-01-31"},{"2026-01-31T23:00:00Z","2026-02-01"},
                {"2026-03-29T00:59:59.999999Z","2026-03-29"},{"2026-03-29T01:00:00Z","2026-03-29"},
                {"2026-03-29T21:59:59.999999Z","2026-03-29"},{"2026-03-29T22:00:00Z","2026-03-30"},
                {"2026-10-25T00:30:00Z","2026-10-25"},{"2026-10-25T01:30:00Z","2026-10-25"},
                {"2026-10-25T22:59:59.999999Z","2026-10-25"},{"2026-10-25T23:00:00Z","2026-10-26"}})
            assertEquals(LocalDate.parse(pair[1]),CustomerQuota.dayAt(Instant.parse(pair[0])));
        assertThrows(NullPointerException.class,()->CustomerQuota.dayAt(null));
    }
    @Test void storedUsageMayExceedActiveAllowanceButNeverHasNegativeOrOverchargedCounters() {
        LocalDate day=LocalDate.parse("2026-01-01");var quota=new CustomerQuota(4,3,day);
        assertEquals(4,quota.active());assertEquals(3,quota.requests());assertEquals(day,quota.submissionDay());
        for(int[] counts:new int[][]{{-1,0},{0,-1},{0,4}})
            assertEquals("INVALID_QUOTA",assertThrows(DomainFailure.class,()->new CustomerQuota(counts[0],counts[1],day)).code());
        assertThrows(DomainFailure.class,()->new CustomerQuota(0,0,null));
    }
}
