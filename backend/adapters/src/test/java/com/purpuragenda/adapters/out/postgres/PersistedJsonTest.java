package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import com.purpuragenda.domain.scheduling.SchedulingPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PersistedJsonTest {
    @Test void durationsRoundTripWithoutFrameworkTypesInTheDomain() {
        SchedulingPolicy policy=SchedulingPolicy.defaults();
        assertEquals(policy,PersistedJson.read(PersistedJson.write(policy),SchedulingPolicy.class));
        String numeric=PersistedJson.write(policy).replace("\"PT24H\"","86400").replace("\"PT1H\"","3600");
        assertEquals(policy,PersistedJson.read(numeric,SchedulingPolicy.class));
    }
    @Test void invalidNullUnknownAndUnserializableDataNeverExposeParserCauses() {
        for(String invalid:new String[]{null,"null","{}","{} {}","{\"unexpected\":true}"}) {
            Failure failure=assertThrows(Failure.class,()->PersistedJson.read(invalid,SchedulingPolicy.class));
            assertEquals("STORAGE_FAILURE",failure.getMessage());assertNull(failure.getCause());
        }
        assertThrows(Failure.class,()->PersistedJson.write(null));
        assertNull(assertThrows(Failure.class,()->PersistedJson.write(new Object())).getCause());
    }
}
